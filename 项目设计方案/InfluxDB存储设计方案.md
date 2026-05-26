# Vionix InfluxDB 存储设计方案

## 1. Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级（原始值） | 2 分钟 | 实时大屏 |
| `device_min` | 分钟级 | 2 小时 | 短时趋势/故障排查 |
| `device_hour` | 小时级 | 90 天 | 日报/周报/月报 |
| `device_day` | 天级 | 永久 | 年报/同比分析 |

## 2. 聚合策略

**每个降采样级别同时存储 5 种聚合值**，字段自动加后缀：

```
原始字段: temperature

device_min / device_hour / device_day 中生成:
  temperature_mean   均值
  temperature_sum    总和
  temperature_max    最大值
  temperature_min    最小值
  temperature_count  数据点数
```

`device_raw` 存原始值，字段名不带后缀。

降采样聚合是不可逆的（无法从均值反推总和），所以一开始就全存。

## 3. 降采样 Task

由 InfluxDB 内置 Task Scheduler 自动执行，无需外部定时器。

```
device_raw (每秒原值)
    ↓ Task 每 1m 执行，aggregateWindow(every: 1m)
device_min (每整分钟一条，5 种聚合)
    ↓ Task 每 1h 执行，aggregateWindow(every: 1h)
device_hour (每整小时一条，5 种聚合)
    ↓ Task 每 1d 执行，aggregateWindow(every: 1d)
device_day (每天一条，5 种聚合)
```

## 4. 查询路由

后端根据查询时间范围自动选择 Bucket：

```
时间跨度 ≤ 2分钟  → device_raw
时间跨度 ≤ 2小时  → device_min
时间跨度 ≤ 90天   → device_hour
时间跨度 > 90天   → device_day
```

## 5. 写入流程

```
任何数据源 → Telegraf → device_raw（批量写入，建议 500ms 或 500 条一批）
```

降采样由 InfluxDB Task 自动完成，写入端只需写秒级原始数据。
