# Vionix

Vionix 是 IoT 设备监控平台。当前仓库处于基础设施与开发骨架阶段，已保留后端、前端、数据库、部署、脚本、测试和文档目录，便于后续按模块推进实现。

## 当前可运行范围

当前可运行的最小链路：

```text
设备(MQTT JSON) -> Mosquitto -> Telegraf -> InfluxDB
```

启动命令：

```bash
docker compose -f deploy/local/docker-compose.yml up -d
```

当前不包含 Backend、Frontend、MySQL、Redis、RBAC、规则引擎、WebSocket 和低代码仪表盘实现，这些能力按 `docs/development/` 中的开发基线逐步补齐。

## 目录树

```text
vionix/
├── backend/              # Spring Boot 4 后端预留目录
├── frontend/             # Vue 前端预留目录
├── database/             # 数据库迁移与初始化脚本
├── deploy/               # 部署编排与环境配置
│   └── local/            # 本地最小基础设施
├── docs/                 # 文档中心
│   ├── design/           # 架构与专题设计方案
│   ├── development/      # 开发基线说明书
│   ├── prd/              # 产品需求与版本需求预留
│   └── review/           # 审查报告
├── scripts/              # 工程脚本预留目录
└── tests/                # 跨模块测试资产预留目录
```

## 文档入口

- [文档中心](docs/README.md)
- [设计文档](docs/design/README.md)
- [开发文档总览](docs/development/README.md)
- [本地部署说明](deploy/local/README.md)
