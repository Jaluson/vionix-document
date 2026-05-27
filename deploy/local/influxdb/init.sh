#!/bin/bash
set -euo pipefail

INFLUX_URL="${INFLUX_URL:-http://localhost:8086}"
TOKEN="${DOCKER_INFLUXDB_INIT_ADMIN_TOKEN:?DOCKER_INFLUXDB_INIT_ADMIN_TOKEN is required}"
ORG="${DOCKER_INFLUXDB_INIT_ORG:-vionix}"
export INFLUX_HOST="${INFLUX_URL}"

until curl -sf "${INFLUX_URL}/health" > /dev/null; do
  echo "Waiting for InfluxDB..."
  sleep 2
done

until influx org list --token "${TOKEN}" > /dev/null 2>&1; do
  echo "Waiting for InfluxDB setup..."
  sleep 2
done

echo "InfluxDB is ready. Creating buckets and tasks..."

RAW_BUCKET_ID="$(influx bucket list --token "${TOKEN}" --org "${ORG}" --hide-headers | awk '$2 == "device_raw" {print $1; exit}')"
if [[ -n "${RAW_BUCKET_ID}" ]]; then
  # InfluxDB OSS 2.7 rejects bucket retention below one hour.
  influx bucket update \
    --id "${RAW_BUCKET_ID}" \
    --retention 1h \
    --shard-group-duration 0 \
    --token "${TOKEN}"
fi

bucket_exists() {
  influx bucket list --token "${TOKEN}" --org "${ORG}" --name "$1" --hide-headers \
    | awk -v name="$1" '$2 == name {found = 1} END {exit(found ? 0 : 1)}'
}

create_bucket_if_missing() {
  if ! bucket_exists "$1"; then
    influx bucket create -n "$1" -r "$2" -o "${ORG}" --token "${TOKEN}"
  fi
}

create_bucket_if_missing device_min 2h
create_bucket_if_missing device_hour 90d
create_bucket_if_missing device_day 0

echo "Buckets created."

task_exists() {
  influx task list --token "${TOKEN}" --org "${ORG}" --hide-headers | grep -q "$1"
}

if ! task_exists "downsample-raw-to-min"; then
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  - <<FLUX
option task = {name: "downsample-raw-to-min", every: 1m}

source = from(bucket: "device_raw")
  |> range(start: -2m)
  |> filter(fn: (r) => r._measurement == "device_metrics")

agg_mean = source
  |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r._field + "_mean"}))

agg_sum = source
  |> aggregateWindow(every: 1m, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r._field + "_sum"}))

agg_max = source
  |> aggregateWindow(every: 1m, fn: max, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r._field + "_max"}))

agg_min = source
  |> aggregateWindow(every: 1m, fn: min, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r._field + "_min"}))

agg_count = source
  |> aggregateWindow(every: 1m, fn: count, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r._field + "_count"}))

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_min", org: "${ORG}")
FLUX
fi

if ! task_exists "downsample-min-to-hour"; then
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  - <<FLUX
import "strings"

option task = {name: "downsample-min-to-hour", every: 1h}

source = from(bucket: "device_min")
  |> range(start: -2h)
  |> filter(fn: (r) => r._measurement == "device_metrics")

sum_for_mean = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_sum"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_sum")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: sum, createEmpty: false)
  |> keep(columns: ["_time", "_value", "_measurement", "tenant_id", "device_id", "source", "metric"])

count_for_mean = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_count"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_count")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: sum, createEmpty: false)
  |> keep(columns: ["_time", "_value", "_measurement", "tenant_id", "device_id", "source", "metric"])

agg_mean = join(
    tables: {sum: sum_for_mean, count: count_for_mean},
    on: ["_time", "_measurement", "tenant_id", "device_id", "source", "metric"],
  )
  |> map(fn: (r) => ({
      _time: r._time,
      _measurement: r._measurement,
      tenant_id: r.tenant_id,
      device_id: r.device_id,
      source: r.source,
      _field: r.metric + "_mean",
      _value: if r._value_count == 0 then 0.0 else r._value_sum / float(v: r._value_count),
    }))

agg_sum = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_sum"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_sum")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_sum"}))
  |> drop(columns: ["metric"])

agg_max = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_max"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_max")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: max, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_max"}))
  |> drop(columns: ["metric"])

agg_min = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_min"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_min")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: min, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_min"}))
  |> drop(columns: ["metric"])

agg_count = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_count"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_count")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1h, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_count"}))
  |> drop(columns: ["metric"])

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_hour", org: "${ORG}")
FLUX
fi

if ! task_exists "downsample-hour-to-day"; then
influx task create \
  --token "${TOKEN}" \
  --org "${ORG}" \
  - <<FLUX
import "strings"

option task = {name: "downsample-hour-to-day", every: 1d}

source = from(bucket: "device_hour")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "device_metrics")

sum_for_mean = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_sum"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_sum")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
  |> keep(columns: ["_time", "_value", "_measurement", "tenant_id", "device_id", "source", "metric"])

count_for_mean = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_count"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_count")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
  |> keep(columns: ["_time", "_value", "_measurement", "tenant_id", "device_id", "source", "metric"])

agg_mean = join(
    tables: {sum: sum_for_mean, count: count_for_mean},
    on: ["_time", "_measurement", "tenant_id", "device_id", "source", "metric"],
  )
  |> map(fn: (r) => ({
      _time: r._time,
      _measurement: r._measurement,
      tenant_id: r.tenant_id,
      device_id: r.device_id,
      source: r.source,
      _field: r.metric + "_mean",
      _value: if r._value_count == 0 then 0.0 else r._value_sum / float(v: r._value_count),
    }))

agg_sum = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_sum"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_sum")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_sum"}))
  |> drop(columns: ["metric"])

agg_max = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_max"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_max")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_max"}))
  |> drop(columns: ["metric"])

agg_min = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_min"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_min")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_min"}))
  |> drop(columns: ["metric"])

agg_count = source
  |> filter(fn: (r) => strings.hasSuffix(v: r._field, suffix: "_count"))
  |> map(fn: (r) => ({r with metric: strings.trimSuffix(v: r._field, suffix: "_count")}))
  |> group(columns: ["_measurement", "tenant_id", "device_id", "source", "metric"])
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({r with _field: r.metric + "_count"}))
  |> drop(columns: ["metric"])

union(tables: [agg_mean, agg_sum, agg_max, agg_min, agg_count])
  |> to(bucket: "device_day", org: "${ORG}")
FLUX
fi

echo "All tasks created. Initialization complete."
