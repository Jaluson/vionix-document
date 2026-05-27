# Backend

后端服务按 `docs/development/` 的 M1 基线建设，当前包含 Spring Boot 4 工程、统一响应、基础异常处理、traceId、Actuator 健康检查和 Flyway 迁移入口。

## 技术栈

- JDK 25
- Spring Boot 4.0.6
- MySQL
- Redis
- InfluxDB health endpoint
- MQTT broker connectivity check
- Flyway

## 运行配置

敏感值只允许通过环境变量或 Secret 注入，不写入 `application.yml`。

| 变量 | 说明 |
|------|------|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | MySQL 用户 |
| `SPRING_DATASOURCE_PASSWORD` | MySQL 密码 |
| `VIONIX_FLYWAY_ENABLED` | 是否启用 Flyway，默认 `false` |
| `VIONIX_FLYWAY_LOCATIONS` | 迁移脚本位置，默认读取 `database/migrations` |
| `SPRING_DATA_REDIS_HOST` | Redis host |
| `SPRING_DATA_REDIS_PORT` | Redis port |
| `INFLUXDB_URL` | InfluxDB 地址 |
| `INFLUXDB_ORG` | InfluxDB org |
| `INFLUXDB_BUCKET` | 默认 raw bucket |
| `INFLUXDB_TOKEN` | InfluxDB token |
| `MQTT_BROKER` | MQTT broker，如 `tcp://localhost:1883` |
| `AUTH_JWT_SECRET` | JWT 签名密钥 |
| `AUTH_TOKEN_HASH_SALT` | refreshToken hash 盐 |

## 本地命令

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml spring-boot:run
```

本机需要 JDK 25 和 Maven 3.6.3+。当前 M1 只交付后端骨架，RBAC、设备目录 API、指标查询、WebSocket、规则引擎和前端页面仍属于后续里程碑。
