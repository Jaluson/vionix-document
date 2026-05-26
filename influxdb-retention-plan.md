# InfluxDB 降采样与保留策略

## 1. Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级 | 2 分钟 | 实时大屏 |
| `device_min` | 分钟级 | 2 小时 | 短时趋势/故障排查 |
| `device_hour` | 小时级 | 90 天 | 日报/周报/月报 |
| `device_day` | 天级 | 永久 | 年报/同比分析 |

## 2. 创建 Bucket（InfluxDB CLI）

```bash
influx bucket create -n device_raw   -r 2m    -o <org>
influx bucket create -n device_min   -r 2h    -o <org>
influx bucket create -n device_hour  -r 90d   -o <org>
influx bucket create -n device_day   -r 0     -o <org>
```

## 3. 降采样 Task

### 3.1 秒 → 分钟（每 1 分钟执行）

```flux
option task = { name: "downsample-sec-to-min", every: 1m }

from(bucket: "device_raw")
  |> range(start: -2m)
  |> filter(fn: (r) => r._measurement == "device_metrics")
  |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
  |> to(bucket: "device_min", org: "<org>")
```

### 3.2 分钟 → 小时（每 1 小时执行）

```flux
option task = { name: "downsample-min-to-hour", every: 1h }

from(bucket: "device_min")
  |> range(start: -2h)
  |> filter(fn: (r) => r._measurement == "device_metrics")
  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
  |> to(bucket: "device_hour", org: "<org>")
```

### 3.3 小时 → 天（每 1 天执行）

```flux
option task = { name: "downsample-hour-to-day", every: 1d }

from(bucket: "device_hour")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "device_metrics")
  |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
  |> to(bucket: "device_day", org: "<org>")
```

## 4. 查询路由

```
时间跨度 < 2分钟  → device_raw
时间跨度 < 2小时  → device_min
时间跨度 < 90天   → device_hour
时间跨度 >= 90天  → device_day
```

后端根据查询的时间范围自动选择对应 Bucket。

## 5. 写入流程

```
MQTT 消息 → 消费端 → 批量写入 device_raw（建议 500ms 或 500 条一批）
```

降采样由 InfluxDB Task 自动完成，写入端只需写秒级原始数据。
