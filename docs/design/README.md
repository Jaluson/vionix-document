# Vionix 设计文档索引

设计文档描述目标平台能力和当前阶段边界，部署配置文件已移动到 `deploy/local/`。

| 文档 | 说明 |
|------|------|
| [Vionix项目总览](Vionix项目总览.md) | 项目范围、当前阶段边界、统一设计约束 |
| [系统架构设计方案](系统架构设计方案.md) | 当前/目标架构、核心数据流、技术选型 |
| [基础设施与部署方案](基础设施与部署方案.md) | 当前最小环境、目标完整环境、Compose 边界 |
| [InfluxDB存储设计方案](InfluxDB存储设计方案.md) | Bucket 规划、tag/field 模型、降采样口径、查询路由 |
| [influxdb-retention-plan](influxdb-retention-plan.md) | InfluxDB 降采样与保留策略 |
| [RBAC设计方案](RBAC设计方案.md) | 多租户隔离、认证、权限、用户和角色模型 |
| [规则引擎设计方案](规则引擎设计方案.md) | 规则、告警、分组、动作和 WebSocket 告警推送 |
| [Web监控前端方案](Web监控前端方案.md) | 实时监控、历史查询和 WebSocket 协议 |
| [低代码仪表盘设计方案](低代码仪表盘设计方案.md) | 仪表盘元数据、组件体系、变量绑定和共享边界 |

## 相关目录

- 本地最小部署：`deploy/local/`
- 开发基线说明书：`docs/development/`
- 设计审查记录：`docs/review/`
