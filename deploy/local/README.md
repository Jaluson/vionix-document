# 本地最小基础设施

本目录用于启动当前已交付的 Mosquitto、InfluxDB 和 Telegraf 链路。

## 环境变量

本地启动前需要提供 `deploy/local/.env`，字段参考 `deploy/local/.env.example`。该文件被 `.gitignore` 排除，不应提交到仓库。

必填项：

| 变量 | 说明 |
|------|------|
| `VIONIX_INFLUXDB_INIT_USERNAME` | InfluxDB 本地管理员用户名 |
| `VIONIX_INFLUXDB_INIT_PASSWORD` | InfluxDB 本地管理员密码 |
| `VIONIX_INFLUXDB_ADMIN_TOKEN` | InfluxDB 本地管理员 token |
| `VIONIX_INFLUXDB_ORG` | InfluxDB org，默认 `vionix` |
| `VIONIX_DEV_TENANT_ID` | `sensors/{device_id}` 开发兼容 topic 使用的默认租户 |
| `MYSQL_ROOT_PASSWORD` | M1 profile 使用的 MySQL root 密码 |
| `MYSQL_USER` | M1 profile 使用的应用数据库用户 |
| `MYSQL_PASSWORD` | M1 profile 使用的应用数据库密码 |
| `AUTH_JWT_SECRET` | M1 profile 使用的 JWT 签名密钥 |
| `AUTH_TOKEN_HASH_SALT` | M1 profile 使用的 refreshToken hash 盐 |

## 启动

```powershell
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml up -d
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml ps
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml logs -f telegraf
```

当前范围：

- Mosquitto MQTT Broker：`localhost:1883`
- InfluxDB UI：`http://localhost:8086`
- Telegraf MQTT JSON 采集

当前 Compose 不包含 Backend、Frontend、MySQL、Redis 和 WebSocket 服务。`backend/` 已有 M1 骨架，但尚未纳入本地基础设施编排。

## M1 后端骨架 Profile

显式启用 `m1` profile 时，Compose 会额外启动 MySQL、Redis，并构建 Backend 骨架：

```powershell
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml --profile m1 up -d --build
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml --profile m1 ps
```

Backend 暴露：

| 服务 | 地址 |
|------|------|
| Backend Actuator | `http://localhost:8080/actuator/health` |
| Backend Ping | `http://localhost:8080/api/system/ping` |

该 profile 只用于 M1 骨架验证，不代表 RBAC、指标查询、WebSocket、规则引擎或前端能力已经完成。

## MQTT topic

生产 topic：

```text
vionix/{tenant_id}/{device_id}/metrics
```

Telegraf 会从 topic 中提取 `tenant_id` 和 `device_id`，并把 `source` 作为 tag 写入 InfluxDB。payload 中未提供 `source` 时，默认使用 `mqtt`。

开发兼容 topic：

```text
sensors/{device_id}
```

该 topic 只用于单租户本地开发，`tenant_id` 使用 `VIONIX_DEV_TENANT_ID`。

## 本地验证

静态配置和 Compose 插值检查：

```powershell
pwsh -File scripts/verify-m0-infrastructure.ps1
```

发布一条生产 topic 测试消息：

```powershell
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml exec -T mosquitto mosquitto_pub -h localhost -t vionix/1/sensor-001/metrics -m '{"timestamp":"2026-05-26T10:01:00Z","source":"mqtt","temperature":25.3,"humidity":60.5,"light":320}'
```

查询 raw 写入结果：

```powershell
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml exec -T influxdb sh -lc 'influx query --org "$DOCKER_INFLUXDB_INIT_ORG" --token "$DOCKER_INFLUXDB_INIT_ADMIN_TOKEN" "from(bucket: \"device_raw\") |> range(start: -5m) |> filter(fn: (r) => r._measurement == \"device_metrics\") |> filter(fn: (r) => r.tenant_id == \"1\" and r.device_id == \"sensor-001\")"'
```

预期结果应包含 `tenant_id=1`、`device_id=sensor-001`、`source=mqtt`，以及 `temperature`、`humidity`、`light` 数值字段。分钟聚合 task 运行后，`device_min` 中应出现 `temperature_mean`、`temperature_sum`、`temperature_max`、`temperature_min`、`temperature_count` 等字段。
