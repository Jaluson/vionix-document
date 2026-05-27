# InfluxDB 降采样与保留策略

本文件是 `InfluxDB存储设计方案.md` 的可执行口径摘要。若两者不一致，以 `InfluxDB存储设计方案.md` 为准。

## 1. Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级原始值 | 35 分钟 | 实时大屏，为 30 分钟查询路由留出容错窗口 |
| `device_min` | 分钟级聚合 | 2 小时 | 短时趋势、故障排查 |
| `device_hour` | 小时级聚合 | 90 天 | 日报、周报、月报 |
| `device_day` | 天级聚合 | 永久 | 年报、同比分析 |

## 2. 聚合字段

所有聚合层固定输出同一组字段名：

```text
temperature_mean
temperature_sum
temperature_max
temperature_min
temperature_count
```

禁止在小时层或天层再次追加后缀，例如 `temperature_mean_mean`、`temperature_sum_sum`。

## 3. 降采样口径

### 3.1 秒到分钟

从 `device_raw` 读取原始字段，分别计算：

| 输出字段 | 计算方式 |
|----------|----------|
| `*_mean` | 原始值 mean |
| `*_sum` | 原始值 sum |
| `*_max` | 原始值 max |
| `*_min` | 原始值 min |
| `*_count` | 原始值 count |

### 3.2 分钟到小时

从 `device_min` 读取上一层聚合字段，输出字段名保持不变：

| 输出字段 | 计算方式 |
|----------|----------|
| `*_mean` | `sum(*_sum) / sum(*_count)` |
| `*_sum` | `sum(*_sum)` |
| `*_max` | `max(*_max)` |
| `*_min` | `min(*_min)` |
| `*_count` | `sum(*_count)` |

### 3.3 小时到天

从 `device_hour` 读取上一层聚合字段，使用同样的加权均值和汇总规则写入 `device_day`。

## 4. 查询路由

```text
时间跨度 <= 30 分钟 -> device_raw
时间跨度 <= 2 小时  -> device_min
时间跨度 <= 90 天   -> device_hour
时间跨度 > 90 天    -> device_day
```

后端查询历史聚合数据时按 `metric + "_" + agg` 生成字段名。例如 `metric=temperature&agg=mean` 查询 `temperature_mean`。
