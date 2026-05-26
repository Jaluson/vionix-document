# Vionix InfluxDB 存储设计方案

## 1. Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级（原始值） | 35 分钟 | 实时大屏，为降采样留出容错窗口（保留时间大于查询路由阈值 30 分钟，避免边界数据丢失） |
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
时间跨度 ≤ 30分钟  → device_raw（后端对原始值做内存聚合）
时间跨度 ≤ 2小时   → device_min
时间跨度 ≤ 90天   → device_hour
时间跨度 > 90天   → device_day
```

## 5. 写入流程

```
任何数据源 → Telegraf → device_raw（批量写入，建议 500ms 或 500 条一批）
```

降采样由 InfluxDB Task 自动完成，写入端只需写秒级原始数据。

## 6. Measurement 数据模型

所有 bucket 使用统一 measurement `device_metrics`，结构如下：

**Tags（索引字段，用于过滤和 GROUP BY）：**

| Tag | 说明 | 示例 |
|-----|------|------|
| `tenant_id` | 租户 ID，行级隔离 | `1` |
| `device_id` | 设备标识 | `sensor-001` |
| `device_type` | 设备类型（可选） | `thermometer` |

**Fields（数据值，不建索引）：**

| Field | 类型 | 说明 |
|-------|------|------|
| `temperature` | float | 温度（原始值） |
| `humidity` | float | 湿度（原始值） |
| ... | ... | 其他业务指标 |

> 降采样后字段名加后缀：`temperature_mean`、`temperature_max` 等。

## 7. 多租户隔离策略

采用 **共享 Bucket + `tenant_id` Tag 过滤** 策略：

- 所有租户数据写入同一组 Bucket，通过 `tenant_id` tag 区分
- 查询时后端自动拼接 `|> filter(fn: (r) => r.tenant_id == "{tenantId}")`
- 降采样 Task 会保留 `tenant_id` tag，聚合后的数据仍可按租户过滤

**选择理由：**

| 策略 | 选择 | 原因 |
|------|------|------|
| 按 Bucket 隔离 | 未选择 | 每租户 4 个 Bucket，管理复杂 |
| 按 Organization 隔离 | 未选择 | Org 数量有限制，不适合大量租户 |
| **按 Tag 隔离** | **选择** | 运维简单，查询性能通过 tag 索引保证 |

> 若未来单租户数据量极大（亿级数据点/天），可针对该租户迁移为独立 Bucket。
