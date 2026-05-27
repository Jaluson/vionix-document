# Vionix InfluxDB 存储设计方案

## 1. Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级原始值 | 2 分钟 | 实时大屏、规则引擎最近窗口 |
| `device_min` | 分钟级聚合 | 2 小时 | 短时趋势、故障排查 |
| `device_hour` | 小时级聚合 | 90 天 | 日报、周报、月报 |
| `device_day` | 天级聚合 | 永久 | 年报、同比分析 |

## 2. Measurement、Tag 和 Field

统一 measurement：

```
device_metrics
```

必选 tag：

| Tag | 说明 |
|-----|------|
| `tenant_id` | 租户边界，来自 topic、设备注册表或后端写入网关 |
| `device_id` | 设备唯一标识，历史查询、前端筛选和规则匹配都依赖该字段 |
| `source` | 数据来源，如 `mqtt`、`modbus`、`http` |

可选 tag：

| Tag | 说明 |
|-----|------|
| `group_id` | 设备分组冗余标签，只用于查询加速 |
| `location` | 位置维度，适合低基数场景 |

field 为设备指标，如 `temperature`、`humidity`、`voltage`。需要进入降采样链路的 field 必须是数值类型；字符串状态适合另建状态流或只保留在原始层。

## 3. 聚合字段规范

`device_raw` 存原始字段，字段名不带聚合后缀。

`device_min`、`device_hour`、`device_day` 均使用同一套输出字段名，不叠加二次后缀：

```
原始字段: temperature

聚合字段:
  temperature_mean
  temperature_sum
  temperature_max
  temperature_min
  temperature_count
```

后端查询历史聚合数据时按 `metric + "_" + agg` 生成字段名。例如 `metric=temperature&agg=mean` 查询 `temperature_mean`。任何层级都不应出现 `temperature_mean_mean`、`temperature_sum_sum` 这类字段。

## 4. 降采样口径

### 4.1 秒级到分钟级

输入：`device_raw.temperature`

输出：

| 输出字段 | 计算方式 |
|----------|----------|
| `temperature_mean` | `mean(temperature)` |
| `temperature_sum` | `sum(temperature)` |
| `temperature_max` | `max(temperature)` |
| `temperature_min` | `min(temperature)` |
| `temperature_count` | `count(temperature)` |

### 4.2 分钟级到小时级

输入：`device_min.temperature_*`

输出：

| 输出字段 | 计算方式 |
|----------|----------|
| `temperature_mean` | `sum(temperature_sum) / sum(temperature_count)` |
| `temperature_sum` | `sum(temperature_sum)` |
| `temperature_max` | `max(temperature_max)` |
| `temperature_min` | `min(temperature_min)` |
| `temperature_count` | `sum(temperature_count)` |

### 4.3 小时级到天级

输入：`device_hour.temperature_*`

输出方式与分钟到小时一致：

| 输出字段 | 计算方式 |
|----------|----------|
| `temperature_mean` | `sum(temperature_sum) / sum(temperature_count)` |
| `temperature_sum` | `sum(temperature_sum)` |
| `temperature_max` | `max(temperature_max)` |
| `temperature_min` | `min(temperature_min)` |
| `temperature_count` | `sum(temperature_count)` |

均值必须按 count 加权，不能对上一层的 `*_mean` 直接取普通平均值；否则缺点或采集频率不均时会产生统计偏差。

## 5. Task 实现要求

InfluxDB Task 需要按字段后缀过滤后分别计算，不能对上一层所有字段一概 `aggregateWindow + rename`。

推荐处理流程：

```
device_raw
  -> 对原始数值字段分别生成 *_mean/*_sum/*_max/*_min/*_count
  -> 写入 device_min

device_min
  -> 只读取 *_sum 和 *_count 计算加权 mean
  -> 只读取 *_sum 计算 sum
  -> 只读取 *_max 计算 max
  -> 只读取 *_min 计算 min
  -> 只读取 *_count 计算 count
  -> 写入 device_hour，字段名仍为 *_mean/*_sum/*_max/*_min/*_count

device_hour
  -> 按同样规则写入 device_day
```

当前 `influxdb/init.sh` 如直接对 `device_min` 和 `device_hour` 的所有字段再次追加后缀，会生成错误字段名。实现时必须按本节字段映射修正 Task。

## 6. 查询路由

后端根据查询时间范围自动选择 bucket：

```
时间跨度 <= 2分钟  -> device_raw
时间跨度 <= 2小时  -> device_min
时间跨度 <= 90天   -> device_hour
时间跨度 > 90天    -> device_day
```

查询参数中的 `tenant_id` 来自认证上下文，不由普通用户请求体指定。`device_id`、`source`、`group_id` 等筛选条件必须编译为 tag 过滤。

## 7. 写入流程

```
MQTT / Modbus / HTTP
  -> Telegraf 或后端写入网关
  -> 补齐 tenant_id、device_id、source tag
  -> 写入 device_raw
  -> InfluxDB Task 自动降采样
```

写入端只写秒级原始数据。降采样由 InfluxDB Task 完成，规则引擎需要实时评估时优先消费 MQTT 消息；需要巡检聚合指标时再查询相应 bucket。
