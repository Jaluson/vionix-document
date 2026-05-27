# Vionix Web 监控前端方案

## 1. 秒级实时数据

```
设备 -> Mosquitto -> Spring Boot 4 MQTT Consumer -> WebSocket/STOMP -> Vue 图表
```

后端收到 MQTT 消息后先解析并校验 `tenant_id`、`device_id`、`source` 和指标字段，再按 WebSocket 订阅关系推送给有权限的前端连接。前端可以继续按设备和指标做展示过滤，但安全边界必须在服务端完成。

## 2. 分钟/小时/天级历史查询

```
Vue -> HTTP GET /api/metrics -> Spring Boot 4 -> InfluxDB
```

API：

```
GET /api/metrics

参数:
  level:       raw | min | hour | day
  measurement: device_metrics（默认固定）
  fields:      temperature,humidity（不带后缀）
  start:       -2h | -24h | -7d
  end:         now() 或 RFC3339 时间
  agg:         mean | sum | max | min | count（默认 mean）
  deviceId:    设备标识（可选，tag 过滤）
  source:      mqtt | modbus | http（可选，tag 过滤）
```

租户过滤：

- `tenant_id` 从 JWT 中解析，普通请求不能传入或覆盖。
- `deviceId` 必须在当前租户的数据权限范围内。
- 后端根据时间范围选择 bucket，并按 `fields + agg` 读取聚合字段，例如 `temperature_mean`。

返回格式：

```json
{
  "level": "min",
  "field": "temperature",
  "agg": "mean",
  "deviceId": "sensor-001",
  "data": [
    {"time": "2026-05-26T10:01:00Z", "value": 25.3},
    {"time": "2026-05-26T10:02:00Z", "value": 25.5}
  ]
}
```

## 3. WebSocket 协议

### 3.1 握手鉴权

推荐使用 STOMP over WebSocket：

```
CONNECT /ws
Authorization: Bearer {accessToken}
```

服务端在握手或 STOMP `CONNECT` 阶段校验 Token，把 `tenant_id`、`user_id`、角色和数据权限绑定到 WS session。Token 过期后，前端需要刷新 Token 并重连。

如果使用原生 WebSocket，可使用：

```
ws://host/ws?access_token={accessToken}
```

该方式必须只在 HTTPS/WSS 下使用，并避免在日志中记录 query token。

### 3.2 订阅路径

```
/topic/tenant/{tenantId}/device/{deviceId}/metrics
/topic/tenant/{tenantId}/metrics
/topic/tenant/{tenantId}/alerts
/topic/tenant/{tenantId}/device/{deviceId}/alerts
```

服务端订阅校验：

- `{tenantId}` 必须等于当前 Token 中的租户，超级管理员跨租户会话除外。
- 设备级订阅必须校验用户是否有该设备的数据权限。
- 租户级订阅只能推送当前租户内的数据。
- 前端 `$device + metric` 过滤只是展示优化，不能替代服务端过滤。

### 3.3 实时指标消息

```json
{
  "type": "METRIC_POINT",
  "tenantId": 1,
  "deviceId": "sensor-001",
  "source": "mqtt",
  "time": "2026-05-26T10:01:00Z",
  "metrics": {
    "temperature": 25.3,
    "humidity": 60.5
  }
}
```

### 3.4 告警消息

```json
{
  "type": "ALERT_FIRING",
  "tenantId": 1,
  "data": {
    "alertId": 123,
    "ruleName": "高温告警",
    "severity": "CRITICAL",
    "deviceId": "sensor-001",
    "triggerValue": 92.5
  }
}
```

告警恢复和升级分别使用 `ALERT_RESOLVED`、`ALERT_ESCALATED`。

## 4. 前端页面布局

```
┌──────────────────────────────────────────────────────────────┐
│  Vionix 设备监控中心   [告警 3]                [设备筛选▼]  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  温度实时曲线（秒级，自动滚动）                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │     ╱╲    ╱╲                                         │   │
│  │   ╱    ╲╱    ╲       <- WebSocket                    │   │
│  │ ╱              ╲                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│  实时 25.3°C   最高 28.1   最低 22.5                       │
│                                                              │
├───────┬───────────┬──────────────────────────────────────────┤
│ 分钟  │ 小时趋势   │ 天趋势                                  │
│ 表格  │ 折线图     │ 柱状图                                  │
└───────┴───────────┴──────────────────────────────────────────┘

侧边导航：
  ├── 实时监控
  ├── 仪表盘
  ├── 规则管理
  ├── 告警中心
  └── 设备分组
```

## 5. 关键流程

### 5.1 页面加载

1. 根据当前用户权限加载设备列表。
2. 建立 WebSocket 连接并完成 Token 鉴权。
3. 按当前设备筛选订阅实时指标和告警 topic。
4. 并行请求分钟/小时/天级历史数据填充图表。
5. 请求 `/api/alerts/firing` 获取当前告警，更新告警角标。

### 5.2 实时运行

1. WebSocket 每秒推送新点，秒级图表追加，保留最近 120 个点。
2. 分钟/小时图表定时刷新，默认每 30 秒或 1 分钟请求一次 API。
3. 收到 `ALERT_FIRING`、`ALERT_RESOLVED`、`ALERT_ESCALATED` 后更新告警角标和通知。
4. 用户切换设备时取消旧订阅，重新订阅新设备 topic，并刷新历史数据。

### 5.3 切换时间粒度

1. 用户点击「实时/分钟/小时/天」tab。
2. 实时粒度使用 WebSocket 订阅。
3. 历史粒度调用 `/api/metrics`，传入 `level`、`fields`、`agg`、`deviceId` 和时间范围。
4. 后端查对应 bucket，返回已按租户过滤的数据。
5. 图表重新渲染。
