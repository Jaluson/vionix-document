# Vionix Web 监控前端方案

## 1. 秒级实时 — MQTT WebSocket 直推

```
设备 → Mosquitto → Spring Boot (MQTT Client) → SockJS/STOMP → Vue 图表
```

- **后端**：订阅 MQTT topic，收到消息通过 STOMP over WebSocket 广播给前端，纯转发，毫秒级延迟
- **前端**：通过 SockJS + @stomp/stompjs 接收数据追加到图表，保留最近 120 个点（2 分钟），超出自动滚动

**STOMP Topic 命名规范**：

```
/topic/tenant/{tenantId}/device/{deviceId}/metrics    秒级实时数据
/topic/tenant/{tenantId}/alerts                        告警推送
```

- 前端连接 WS 时通过 STOMP CONNECT headers 携带 accessToken 认证
- STOMP 心跳配置：前端 10 秒发送心跳、10 秒接收超时检测。后端 Spring WebSocket 配置 `setHeartbeatTime(10000)` 对应。心跳机制确保静默期间连接异常能被及时发现
- 订阅时指定完整 topic 路径（含 tenantId 和 deviceId），后端 ChannelInterceptor 校验用户只能订阅自己租户的 topic
- 多设备监控时前端可同时订阅多个 device topic

## 2. 分钟/小时/天级 — InfluxDB 查询

```
Vue → HTTP GET → Spring Boot → InfluxDB
```

**API 设计：**

```
GET /api/metrics

参数:
  level:       min | hour | day
  measurement: device_metrics（固定）
  fields:      temperature,humidity（不带后缀，后端自动拼接）
  start:       -2h | -24h | -7d
  agg:         mean | sum | max | min | count（默认 mean）
  device:      设备标识（可选，tag 过滤）
```

**返回格式：**

```json
// 单字段查询（fields=temperature）
{
  "level": "min",
  "fields": ["temperature"],
  "agg": "mean",
  "data": {
    "temperature": [
      {"time": "2026-05-26T10:01:00Z", "value": 25.3},
      {"time": "2026-05-26T10:02:00Z", "value": 25.5}
    ]
  }
}

// 多字段查询（fields=temperature,humidity）
{
  "level": "min",
  "fields": ["temperature", "humidity"],
  "agg": "mean",
  "data": {
    "temperature": [
      {"time": "2026-05-26T10:01:00Z", "value": 25.3},
      {"time": "2026-05-26T10:02:00Z", "value": 25.5}
    ],
    "humidity": [
      {"time": "2026-05-26T10:01:00Z", "value": 60.1},
      {"time": "2026-05-26T10:02:00Z", "value": 61.2}
    ]
  }
}
```

## 3. 前端页面布局

```
┌──────────────────────────────────────────────────────────────┐
│  Vionix 设备监控中心   [告警 3]                   [设备筛选▼]  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  温度实时曲线（秒级，自动滚动）                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │     ╱╲    ╱╲                                         │   │
│  │   ╱    ╲╱    ╲       ← WebSocket                     │   │
│  │ ╱              ╲                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│  ● 实时 25.3°C   ▲ 最高 28.1   ▼ 最低 22.5                 │
│                                                              │
├───────┬───────────┬──────────────────────────────────────────┤
│ 分钟  │ 小时趋势   │ 天趋势                                  │
│ ┌───┐ │ ┌───────┐ │ ┌────────────────┐                     │
│ │   │ │ │       │ │ │                │                     │
│ │   │ │ │       │ │ │                │                     │
│ └───┘ │ └───────┘ │ └────────────────┘                     │
│ 表格   │ 折线图    │ 柱状图                                  │
└───────┴───────────┴──────────────────────────────────────────┘

侧边导航：
  ├── 实时监控（上方主页面）
  ├── 规则管理（规则列表 + 编辑）
  ├── 告警中心（告警记录 + 统计）
  └── 设备分组（分组管理）
```

## 4. 状态管理

使用 **Pinia** 管理全局状态：

| Store | 职责 |
|-------|------|
| `useAuthStore` | 用户信息、Token、权限列表、登录/登出 |
| `useWebSocketStore` | SockJS/STOMP 连接管理、消息分发、自动重连 |
| `useAlertStore` | 实时告警角标、告警列表缓存 |

组件内局部状态（如图表数据）使用 Composition API `ref/reactive` 管理，不放入全局 Store。

## 5. 关键流程

### 5.1 页面加载

1. 建立 WebSocket 连接 → 开始接收秒级实时数据
2. 并行请求分钟/小时/天级历史数据填充图表
3. 请求 `/api/alerts/firing` 获取当前告警，更新告警角标

### 5.2 实时运行

1. WebSocket 每秒推送新点 → 秒级图表追加，超出 120 点移除最老的
2. 分钟/小时图表定时刷新（每 30 秒或 1 分钟请求一次 API）
3. WebSocket 收到 `ALERT_FIRING`/`ALERT_RESOLVED`/`ALERT_ESCALATED` → 更新告警角标 + 弹窗通知

### 5.3 切换时间粒度

1. 用户点击「分钟/小时/天」tab
2. 调用 API，传 `level=min|hour|day` + 时间范围
3. 后端查对应 bucket，返回数据
4. 图表重新渲染

## 6. 容错与性能

### 6.1 WebSocket 断线重连

```
断线检测 → 延迟 1s 首次重连
→ 失败 → 2s, 4s, 8s, 16s, 32s, 60s（上限）指数退避
→ 重连成功 → 重新发送 STOMP SUBSCRIBE
→ 重连期间，页面上显示"连接中断，正在重连..."提示
```

`useWebSocketStore` 负责：
- 维护连接状态（connected / reconnecting / disconnected）
- 断线时缓冲最近 10 条消息，重连后补发
- 最大重连次数无上限，直到用户离开页面

### 6.2 API 请求容错

- 历史数据 API 请求失败不重试（定时轮询本身会自动重试）
- 失败时图表显示错误状态（灰色背景 + "数据加载失败" 提示）
- 网络断开时页面顶部显示全局断网提示条

### 6.3 ECharts 实时更新性能

秒级推送使用"维护数据数组 + 节流 setOption"策略：

```javascript
const data = ref([])
let updateTimer = null

function onWsMessage(point) {
  data.value.push(point)
  if (data.value.length > 120) data.value.shift()
  // 节流：每 200ms 真正更新一次 ECharts
  if (!updateTimer) {
    updateTimer = setTimeout(() => {
      chart.setOption({ series: [{ data: data.value }] }, { replaceMerge: ['series'] })
      updateTimer = null
    }, 200)
  }
}
```

仪表盘多组件并发场景使用 `requestAnimationFrame` 批量更新，避免同一帧内多次 setOption。
