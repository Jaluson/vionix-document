# Vionix 项目总览

## 一句话概述

Vionix 是 IoT 设备监控平台，目标能力包括多数据源采集、多粒度时序存储、实时监控、历史查询、规则告警、多租户 RBAC 和低代码仪表盘。

## 当前阶段边界

当前仓库已交付可运行最小基础设施：

```
设备(MQTT JSON) -> Mosquitto -> Telegraf -> InfluxDB
```

当前可验证：

- Mosquitto MQTT Broker
- Telegraf MQTT JSON 采集
- InfluxDB bucket 初始化
- InfluxDB 降采样 Task

目标完整平台还需要补齐：

- Spring Boot Backend
- Vue Frontend
- MySQL 初始化脚本
- Redis 编排
- RBAC、规则引擎、WebSocket、低代码仪表盘实现

## 统一设计约束

| 约束 | 说明 |
|------|------|
| 阶段边界 | 当前 Compose 只承诺基础设施；完整 Compose 需等待应用目录补齐 |
| 设备标识 | MQTT、InfluxDB、WebSocket、规则引擎统一使用 `tenant_id` + `device_id` |
| 多租户 | 业务表直接携带 `tenant_id`，普通用户不能通过请求参数切换租户 |
| 时序聚合 | 分钟/小时/天 bucket 使用同一套 `*_mean/*_sum/*_max/*_min/*_count` 字段，不叠加二次后缀 |
| WebSocket | 服务端按租户、设备和权限过滤，前端过滤只用于展示 |
| 安全依赖 | 生产和多实例环境必须部署 Redis；密钥和盐值来自 Secret/环境变量/KMS |

## 设计文档索引

| 文档 | 说明 |
|------|------|
| [系统架构设计方案](系统架构设计方案.md) | 当前/目标架构、核心数据流、技术选型 |
| [基础设施与部署方案](基础设施与部署方案.md) | 当前最小环境、目标完整环境、Compose 边界 |
| [InfluxDB存储设计方案](InfluxDB存储设计方案.md) | Bucket 规划、tag/field 模型、降采样口径、查询路由 |
| [规则引擎设计方案](规则引擎设计方案.md) | 租户隔离、规则条件、告警管理、动作分发、API 设计 |
| [Web监控前端方案](Web监控前端方案.md) | 实时监控、历史查询、WebSocket 鉴权订阅、页面流程 |
| [RBAC设计方案](RBAC设计方案.md) | 多租户隔离、RBAC 权限控制、认证与安全架构 |
| [低代码仪表盘设计方案](低代码仪表盘设计方案.md) | 拖拽式仪表盘编辑器、组件体系、全局变量、租户内共享 |
