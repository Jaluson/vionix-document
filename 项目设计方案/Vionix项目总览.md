# Vionix 项目总览

## 一句话概述

IoT 设备监控平台，支持多数据源（MQTT / Modbus / HTTP）采集、多粒度存储、实时监控与历史查询，内置规则引擎实现实时告警检测与动作分发。

## 系统架构概览

```
设备(MQTT/Modbus/HTTP) → Telegraf → InfluxDB(多粒度存储)
                              ↓
                        Mosquitto(MQTT Broker)
                              ↓
                  Spring Boot(MQTT→WS桥接 + 规则引擎)
                       ↓              ↓
                  WebSocket        MySQL(规则/告警)
                       ↓
                  Vue 前端(ECharts)
```

## 设计文档索引

| 文档 | 说明 |
|------|------|
| [系统架构设计方案](系统架构设计方案.md) | 整体架构图、技术选型 |
| [基础设施与部署方案](基础设施与部署方案.md) | Docker Compose 编排、服务配置、一键部署 |
| [InfluxDB存储设计方案](InfluxDB存储设计方案.md) | Bucket 规划、降采样策略、聚合规则、查询路由 |
| [规则引擎设计方案](规则引擎设计方案.md) | 规则条件、告警管理、动作分发、API 设计 |
| [Web监控前端方案](Web监控前端方案.md) | 实时监控、历史查询、页面布局、关键流程 |
| [RBAC设计方案](RBAC设计方案.md) | 多租户隔离、RBAC 权限控制、安全架构 |
