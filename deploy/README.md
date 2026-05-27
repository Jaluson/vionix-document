# 部署目录

本目录存放可执行部署资产和环境配置。

```text
deploy/
└── local/
    ├── docker-compose.yml
    ├── influxdb/
    ├── mosquitto/
    └── telegraf/
```

当前只有 `local/` 最小基础设施环境。测试、预发、生产环境配置应在对应环境目录下新增，不放入 `docs/`。
