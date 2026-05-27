# 本地最小基础设施

本目录用于启动当前已交付的 Mosquitto、InfluxDB 和 Telegraf 链路。

```bash
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/docker-compose.yml ps
docker compose -f deploy/local/docker-compose.yml logs -f telegraf
```

当前范围：

- Mosquitto MQTT Broker：`localhost:1883`
- InfluxDB UI：`http://localhost:8086`
- Telegraf MQTT JSON 采集

当前不包含 Backend、Frontend、MySQL、Redis 和 WebSocket 服务。
