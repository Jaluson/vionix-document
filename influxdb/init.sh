#!/bin/bash
set -e

INFLUX_URL="http://localhost:8086"
TOKEN="my-super-secret-token"
ORG="vionix"

# Wait for InfluxDB to be fully ready
until curl -sf "${INFLUX_URL}/health" > /dev/null; do
  echo "Waiting for InfluxDB..."
  sleep 2
done

echo "InfluxDB is ready. Creating buckets and tasks..."

# ── Create buckets ──
# device_raw is already created by DOCKER_INFLUXDB_INIT_BUCKET (retention set via CLI below)

# Update device_raw retention to 2m
influx bucket update \
  --name device_raw \
  --retention 2m \
  --token "${TOKEN}" \
  --org "${ORG}" || true

influx bucket create -n device_min  -r 2h   -o "${ORG}" --token "${TOKEN}" || true
influx bucket create -n device_hour -r 90d  -o "${ORG}" --token "${TOKEN}" || true
influx bucket create -n device_day  -r 0    -o "${ORG}" --token "${TOKEN}" || true

echo "Buckets created."

# ── Helper: multi-aggregate Flux template ──
# Reads from $src, aggregates with mean/sum/max/min/count, writes to $dst
# Each aggregation adds suffix to _field (e.g. "temperature" → "temperature_mean")

# ── Task 1: second → minute (every 1m) ──
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  --name "downsample-sec-to-min" \
  --every 1m \
  - <<'FLUX'
source = from(bucket: "device_raw")
  |> range(start: -2m)
  |> filter(fn: (r) => r._measurement == "device_metrics")

agg_mean  = source |> aggregateWindow(every: 1m, fn: mean,  createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_mean"}))
agg_sum   = source |> aggregateWindow(every: 1m, fn: sum,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_sum"}))
agg_max   = source |> aggregateWindow(every: 1m, fn: max,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_max"}))
agg_min   = source |> aggregateWindow(every: 1m, fn: min,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_min"}))
agg_count = source |> aggregateWindow(every: 1m, fn: count, createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_count"}))

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_min", org: "vionix")
FLUX

# ── Task 2: minute → hour (every 1h) ──
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  --name "downsample-min-to-hour" \
  --every 1h \
  - <<'FLUX'
source = from(bucket: "device_min")
  |> range(start: -2h)
  |> filter(fn: (r) => r._measurement == "device_metrics")

agg_mean  = source |> aggregateWindow(every: 1h, fn: mean,  createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_mean"}))
agg_sum   = source |> aggregateWindow(every: 1h, fn: sum,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_sum"}))
agg_max   = source |> aggregateWindow(every: 1h, fn: max,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_max"}))
agg_min   = source |> aggregateWindow(every: 1h, fn: min,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_min"}))
agg_count = source |> aggregateWindow(every: 1h, fn: count, createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_count"}))

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_hour", org: "vionix")
FLUX

# ── Task 3: hour → day (every 1d) ──
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  --name "downsample-hour-to-day" \
  --every 1d \
  - <<'FLUX'
source = from(bucket: "device_hour")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "device_metrics")

agg_mean  = source |> aggregateWindow(every: 1d, fn: mean,  createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_mean"}))
agg_sum   = source |> aggregateWindow(every: 1d, fn: sum,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_sum"}))
agg_max   = source |> aggregateWindow(every: 1d, fn: max,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_max"}))
agg_min   = source |> aggregateWindow(every: 1d, fn: min,   createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_min"}))
agg_count = source |> aggregateWindow(every: 1d, fn: count, createEmpty: false) |> rename(fn: (r) => ({r with _field: r._field + "_count"}))

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_day", org: "vionix")
FLUX

echo "All tasks created. Initialization complete."
