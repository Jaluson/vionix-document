# Vionix

Vionix 是 IoT 设备监控平台。当前仓库处于基础设施与应用骨架阶段，已具备本地最小基础设施、Spring Boot 后端骨架、数据库迁移入口、部署脚本和开发文档，便于后续按模块推进实现。

## 当前可运行范围

当前可运行的最小链路：

```text
设备(MQTT JSON) -> Mosquitto -> Telegraf -> InfluxDB
```

启动命令：

```powershell
docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml up -d
```

当前默认 Compose 承诺 Mosquitto、Telegraf、InfluxDB 可运行。`m1` profile 可额外启动 MySQL、Redis、Backend 和 Frontend，提供 RBAC、设备目录、指标查询、WebSocket、规则告警和低代码仪表盘的完整应用骨架。

## 目录树

```text
vionix/
├── backend/              # Spring Boot 4 后端骨架
├── frontend/             # Vue 3 Web 前端应用
├── database/             # 数据库迁移与初始化脚本
├── deploy/               # 部署编排与环境配置
│   └── local/            # 本地最小基础设施
├── docs/                 # 文档中心
│   ├── design/           # 架构与专题设计方案
│   ├── development/      # 开发基线说明书
│   ├── prd/              # 产品需求与版本需求预留
│   └── review/           # 审查报告
├── scripts/              # 工程脚本
└── tests/                # 跨模块测试资产预留目录
```

## 文档入口

- [文档中心](docs/README.md)
- [设计文档](docs/design/README.md)
- [开发文档总览](docs/development/README.md)
- [本地部署说明](deploy/local/README.md)
