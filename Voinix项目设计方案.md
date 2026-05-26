# Voinix 项目设计方案

## 1. 项目概述

IoT 设备监控平台，支持多数据源（MQTT / Modbus / HTTP）采集、多粒度存储、实时监控与历史查询。

## 2. 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                          Vue 前端                             │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐                │
│  │ 秒级实时图 │  │ 分钟趋势图 │  │ 小时趋势图 │                │
│  │ WebSocket │  │ HTTP 查询  │  │ HTTP 查询  │                │
│  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘                │
└────────┼──────────────┼──────────────┼───────────────────────┘
         │ ws           │ http         │ http
┌────────┴──────────────┴──────────────┴───────────────────────┐
│                      Spring Boot 后端                         │
│  ┌──────────────┐  ┌─────────────────────────────────┐       │
│  │ MQTT→WS 桥接 │  │ InfluxDB 查询 API               │       │
│  │ (实时推送)    │  │ GET /api/metrics?level=...       │       │
│  └──────┬───────┘  └──────────┬──────────────────────┘       │
└─────────┼─────────────────────┼─────────────────────────────┘
          │                     │
    ┌─────┴──────┐       ┌──────┴────────┐
    │  Mosquitto │       │   InfluxDB    │
    │   :1883    │       │   :8086       │
    └────────────┘       └───────────────┘
          ▲                     ▲
          │                     │
    ┌─────┴─────────────────────┴─────┐
    │          Telegraf               │
    │  MQTT / Modbus / HTTP → 统一写入  │
    └─────────────────────────────────┘
```

## 3. 基础设施（Docker Compose）

### 3.1 服务组件

| 服务 | 镜像 | 端口 | 职责 |
|------|------|------|------|
| Mosquitto | eclipse-mosquitto:2 | 1883 | MQTT Broker |
| InfluxDB | influxdb:2.7 | 8086 | 时序数据库 |
| Telegraf | telegraf:latest | — | 数据采集网关，统一写入 InfluxDB |

### 3.2 文件结构

```
vionix/
├── docker-compose.yml
├── mosquitto/config/mosquitto.conf
├── telegraf/telegraf.conf
└── influxdb/init.sh
```

### 3.3 Telegraf 多数据源配置

所有数据源统一 `name_override = "device_metrics"`，保证降采样 Task 统一覆盖。

```toml
# MQTT（IoT 设备上报）
[[inputs.mqtt_consumer]]
  servers = ["tcp://mosquitto:1883"]
  topics = ["vionix/+/+", "sensors/#"]
  data_format = "json"
  name_override = "device_metrics"

# Modbus TCP（工业 PLC / 传感器）— 按需启用
# [[inputs.modbus]]
#   name_override = "device_metrics"
#   controller = "tcp://192.168.1.100:502"

# HTTP 推送（应用主动上报）— 按需启用
# [[inputs.http_listener_v2]]
#   name_override = "device_metrics"
#   listen = ":8080"
#   data_format = "json"
```

JSON 结构不要求完全相同，共同字段可跨数据源对比，独有字段各存各的。

## 4. InfluxDB 降采样与保留策略

### 4.1 Bucket 规划

| Bucket 名称 | 粒度 | 保留时长 | 用途 |
|-------------|------|---------|------|
| `device_raw` | 秒级（原始值） | 2 分钟 | 实时大屏 |
| `device_min` | 分钟级 | 2 小时 | 短时趋势/故障排查 |
| `device_hour` | 小时级 | 90 天 | 日报/周报/月报 |
| `device_day` | 天级 | 永久 | 年报/同比分析 |

### 4.2 聚合策略

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

### 4.3 降采样 Task

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

### 4.4 查询路由

后端根据查询时间范围自动选择 Bucket：

```
时间跨度 ≤ 2分钟  → device_raw
时间跨度 ≤ 2小时  → device_min
时间跨度 ≤ 90天   → device_hour
时间跨度 > 90天   → device_day
```

### 4.5 写入流程

```
任何数据源 → Telegraf → device_raw（批量写入，建议 500ms 或 500 条一批）
```

降采样由 InfluxDB Task 自动完成，写入端只需写秒级原始数据。

## 5. Web 监控方案

### 5.1 秒级实时 — MQTT WebSocket 直推

```
设备 → Mosquitto → Spring Boot (MQTT Client) → WebSocket → Vue 图表
```

- **后端**：订阅 MQTT topic，收到消息通过 WebSocket 广播给前端，纯转发，毫秒级延迟
- **前端**：WebSocket 收到数据追加到图表，保留最近 120 个点（2 分钟），超出自动滚动

### 5.2 分钟/小时/天级 — InfluxDB 查询

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
{
  "level": "min",
  "field": "temperature",
  "agg": "mean",
  "data": [
    {"time": "2026-05-26T10:01:00Z", "value": 25.3},
    {"time": "2026-05-26T10:02:00Z", "value": 25.5}
  ]
}
```

### 5.3 前端页面布局

```
┌──────────────────────────────────────────────┐
│  Voinix 设备监控中心               [设备筛选▼] │
├──────────────────────────────────────────────┤
│                                              │
│  温度实时曲线（秒级，自动滚动）                 │
│  ┌──────────────────────────────────────┐    │
│  │     ╱╲    ╱╲                         │    │
│  │   ╱    ╲╱    ╲       ← WebSocket     │    │
│  │ ╱              ╲                      │    │
│  └──────────────────────────────────────┘    │
│  ● 实时 25.3°C   ▲ 最高 28.1   ▼ 最低 22.5   │
│                                              │
├───────┬───────────┬──────────────────────────┤
│ 分钟  │ 小时趋势   │ 天趋势                    │
│ ┌───┐ │ ┌───────┐ │ ┌────────────────┐       │
│ │   │ │ │       │ │ │                │       │
│ │   │ │ │       │ │ │                │       │
│ └───┘ │ └───────┘ │ └────────────────┘       │
│ 表格   │ 折线图    │ 柱状图                    │
└───────┴───────────┴──────────────────────────┘
```

### 5.4 关键流程

**页面加载：**
1. 建立 WebSocket 连接 → 开始接收秒级实时数据
2. 并行请求分钟/小时/天级历史数据填充图表

**实时运行：**
1. WebSocket 每秒推送新点 → 秒级图表追加，超出 120 点移除最老的
2. 分钟/小时图表定时刷新（每 30 秒或 1 分钟请求一次 API）

**切换时间粒度：**
1. 用户点击「分钟/小时/天」tab
2. 调用 API，传 `level=min|hour|day` + 时间范围
3. 后端查对应 bucket，返回数据
4. 图表重新渲染

## 6. Docker 部署

### 6.1 完整服务组件

| 服务 | 镜像 | 端口 | 职责 |
|------|------|------|------|
| Mosquitto | eclipse-mosquitto:2 | 1883 | MQTT Broker |
| InfluxDB | influxdb:2.7 | 8086 | 时序数据库 |
| Telegraf | telegraf:latest | — | 数据采集网关 |
| **backend** | 自建 (Java 17) | 8080 | Spring Boot API + MQTT→WS 桥接 |
| **frontend** | 自建 (Nginx) | 80 | Vue 静态资源 + 反向代理 |

### 6.2 项目文件结构

```
vionix/
├── docker-compose.yml              # 一键编排所有服务
├── mosquitto/
│   └── config/mosquitto.conf
├── telegraf/
│   └── telegraf.conf
├── influxdb/
│   └── init.sh
├── backend/                        # Spring Boot 项目
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
├── frontend/                       # Vue 项目
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── package.json
│   └── src/
└── Voinix项目设计方案.md
```

### 6.3 Backend Dockerfile

```dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 6.4 Frontend Dockerfile

```dockerfile
# 构建阶段
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 运行阶段
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### 6.5 Frontend Nginx 配置

```nginx
server {
    listen 80;

    # Vue 静态资源
    location / {
        root   /usr/share/nginx/html;
        index  index.html;
        try_files $uri $uri/ /index.html;   # Vue Router history 模式
    }

    # 反向代理后端 API
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # WebSocket 代理（秒级实时推送）
    location /ws {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400s;
    }
}
```

### 6.6 docker-compose.yml（完整版）

```yaml
version: "3.8"

services:
  # ── 基础设施 ──
  mosquitto:
    image: eclipse-mosquitto:2
    container_name: mosquitto
    ports:
      - "1883:1883"
    volumes:
      - ./mosquitto/config:/mosquitto/config
      - mosquitto_data:/mosquitto/data
      - mosquitto_log:/mosquitto/log
    restart: unless-stopped

  influxdb:
    image: influxdb:2.7
    container_name: influxdb
    ports:
      - "8086:8086"
    environment:
      - DOCKER_INFLUXDB_INIT_MODE=setup
      - DOCKER_INFLUXDB_INIT_USERNAME=admin
      - DOCKER_INFLUXDB_INIT_PASSWORD=admin123456
      - DOCKER_INFLUXDB_INIT_ORG=vionix
      - DOCKER_INFLUXDB_INIT_BUCKET=device_raw
      - DOCKER_INFLUXDB_INIT_ADMIN_TOKEN=my-super-secret-token
    volumes:
      - influxdb_data:/var/lib/influxdb2
      - ./influxdb/init.sh:/docker-entrypoint-initdb.d/init.sh:ro
    restart: unless-stopped

  telegraf:
    image: telegraf:latest
    container_name: telegraf
    depends_on:
      - mosquitto
      - influxdb
    volumes:
      - ./telegraf/telegraf.conf:/etc/telegraf/telegraf.conf:ro
    restart: unless-stopped

  # ── 应用层 ──
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: backend
    ports:
      - "8080:8080"
    environment:
      - MQTT_BROKER=tcp://mosquitto:1883
      - INFLUXDB_URL=http://influxdb:8086
      - INFLUXDB_TOKEN=my-super-secret-token
      - INFLUXDB_ORG=vionix
      - INFLUXDB_BUCKET=device_raw
    depends_on:
      - mosquitto
      - influxdb
    restart: unless-stopped

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: frontend
    ports:
      - "80:80"
    depends_on:
      - backend
    restart: unless-stopped

volumes:
  mosquitto_data:
  mosquitto_log:
  influxdb_data:
```

### 6.7 部署命令

```bash
# 一键构建并启动所有服务
docker-compose up -d --build

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f backend
docker-compose logs -f frontend

# 停止所有服务
docker-compose down

# 停止并清除数据卷（重置数据库）
docker-compose down -v
```

### 6.8 服务依赖与启动顺序

```
InfluxDB ──→ init.sh (创建 bucket + task)
   ↓
Mosquitto
   ↓
Telegraf ──→ 需要 Mosquitto + InfluxDB
   ↓
Backend  ──→ 需要 Mosquitto + InfluxDB
   ↓
Frontend ──→ 需要 Backend
```

启动后访问：
- **前端页面**：http://localhost
- **后端 API**：http://localhost/api/
- **InfluxDB 管理界面**：http://localhost:8086
- **WebSocket**：ws://localhost/ws

## 7. 技术选型

| 层 | 技术 | 说明 |
|----|------|------|
| 实时推送 | WebSocket (STOMP) | Spring 内置，SockJS 兼容降级 |
| MQTT 客户端 | Eclipse Paho / Spring Integration MQTT | 订阅 Mosquitto |
| InfluxDB 查询 | influxdb-client-java | 官方 Java SDK，支持 Flux 查询 |
| 前端图表 | ECharts | 实时刷新性能好，数据量大不卡 |
| 前端 WS | native WebSocket 或 sockjs-client | 秒级数据接收 |
| 容器化 | Docker Compose + 多阶段构建 | 前后端统一编排，一键部署 |
