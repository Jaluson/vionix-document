# Vionix 规则引擎设计文档

## 1. 概述

Vionix 物联网监控平台自建轻量规则引擎，支持设备数据的实时条件检测、定时巡检、告警管理与动作分发。

### 核心能力

- **触发条件**：复合条件（AND/OR 分组）+ 持续时间 + 变化率
- **执行动作**：平台告警、MQTT 下发、HTTP 回调、IM 推送（可多选组合）
- **规则范围**：单设备 + 设备分组 + 全局
- **执行模式**：实时（MQTT 消费）+ 定时巡检（InfluxDB 查询）
- **告警策略**：频率抑制 + 恢复通知 + 告警升级

### 架构选型

规则编译执行模式：规则存 MySQL，运行时编译为内存 Predicate 缓存。MQTT 消息到来时通过匹配索引快速定位相关规则并评估。规则变更时刷新缓存，无需重启。

---

## 2. 系统架构

```
MQTT 消息 ──→ Spring Boot MQTT Consumer
                    │
                    ▼
            ┌──────────────┐
            │ RuleEngine   │ ← 内存缓存编译后规则 + 匹配索引
            │ (核心引擎)    │
            └──────┬───────┘
                   │ 匹配成功
                   ▼
            ┌──────────────┐
            │ AlertManager │ ← 告警抑制/恢复/升级状态管理
            └──────┬───────┘
                   │
                   ▼
            ┌──────────────┐
            │ActionDispatcher│
            └──┬───┬───┬──┬─┘
               │   │   │  │
               ▼   ▼   ▼  ▼
            Alert  MQTT HTTP IM
            记录   下发 回调 推送
              │
              ▼
           WebSocket → 前端实时告警

定时巡检（@Scheduled）：
  InfluxDB 查询 → RuleEngine 评估 → AlertManager → ActionDispatcher
```

---

## 3. 数据模型

### 3.1 设备分组 `device_group`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT NOT NULL | 租户 ID，行级隔离 |
| name | VARCHAR(100) NOT NULL | 分组名称 |
| description | VARCHAR(500) | 描述 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 3.2 分组设备关联 `device_group_member`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| group_id | BIGINT NOT NULL | 分组 ID |
| device_id | VARCHAR(64) NOT NULL | 设备 ID |
| tenant_id | BIGINT NOT NULL | 租户 ID（行级隔离，与 device_group.tenant_id 保持一致） |

唯一约束：`(group_id, device_id)`

### 3.3 规则主表 `rule`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT NOT NULL | 租户 ID，行级隔离 |
| name | VARCHAR(100) NOT NULL | 规则名称 |
| description | VARCHAR(500) | 描述 |
| scope | ENUM('DEVICE','GROUP','GLOBAL') | 规则范围 |
| device_id | VARCHAR(64) NULL | 设备 ID（scope=DEVICE 时） |
| group_id | BIGINT NULL | 分组 ID（scope=GROUP 时） |
| enabled | BOOLEAN DEFAULT TRUE | 是否启用 |
| severity | ENUM('INFO','WARNING','CRITICAL') | 告警级别 |
| suppress_minutes | INT DEFAULT 5 | 频率抑制窗口（分钟） |
| escalation_minutes | INT DEFAULT 30 | 持续未恢复升级时间（分钟） |
| cooldown_seconds | INT DEFAULT 10 | 两次评估最小间隔（秒） |
| resolve_confirm_seconds | INT DEFAULT 60 | 恢复确认窗口（秒），0 表示立即恢复 |
| schedule_cron | VARCHAR(50) NULL | 定时巡检 cron 表达式，NULL 表示仅实时 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 3.4 规则条件 `rule_condition`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| rule_id | BIGINT NOT NULL | 关联规则 |
| condition_type | ENUM('THRESHOLD','RATE') | 条件类型 |
| group_index | INT DEFAULT 0 | 条件分组号，同组 AND，组间 OR |
| logic_op | ENUM('AND','OR') | 与上一组的连接运算符 |
| metric | VARCHAR(64) NOT NULL | 指标名 |
| operator | ENUM('GT','GTE','LT','LTE','EQ','NEQ') | 比较运算符 |
| threshold | DOUBLE NOT NULL | 阈值 |
| duration_seconds | INT DEFAULT 0 | 持续时间（秒），0 表示立即 |
| rate_window_seconds | INT DEFAULT 0 | 变化率计算窗口（秒） |
| rate_direction | ENUM('UP','DOWN','ANY') | 变化方向 |

条件组合逻辑：同 `group_index` 内 AND，不同 `group_index` 间 OR。

`logic_op` 字段为预留扩展，当前版本固定为 OR（组间关系）。若未来需要支持混合 AND/OR 逻辑，可通过 `logic_op` 实现：当 `logic_op = 'AND'` 时，当前分组与上一分组为 AND 关系；当 `logic_op = 'OR'` 时保持默认。当前版本实现中可忽略此字段，始终按 OR 处理。

示例：`(温度>80 持续5分钟) OR (温度1分钟内上升>10度)` → 两个条件分别在 group_index 0 和 1。

`condition_type = THRESHOLD` 时：比较 metric 当前值与 threshold。
`condition_type = RATE` 时：在 rate_window_seconds 窗口内计算 metric 变化量，与 threshold 比较。

RATE 统一计算公式：`rate = latest_value - value_at(now - rate_window_seconds)`（计算总变化量，不含除法）。threshold 直接对应变化量绝对值。例如 `threshold: 10.0` + `rate_window_seconds: 60` 表示"60 秒内温度变化超过 10 度"

- **实时模式**：从 StateTracker 环形缓冲区取窗口首尾差值。若窗口内数据覆盖率低于 50%（如设备刚启动数据不足），跳过本次评估
- **定时巡检模式**：通过 InfluxDB Flux 查询 `difference()` 函数计算，示例：
  ```flux
  from(bucket: "device_min")
    |> range(start: -rate_window)
    |> filter(fn: (r) => r._measurement == "device_metrics" and r._field == metric)
    |> difference()
    |> last()
  ```
两种模式使用相同公式，差异仅在数据来源。

### 3.5 规则动作 `rule_action`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| rule_id | BIGINT NOT NULL | 关联规则 |
| action_type | ENUM('ALERT','MQTT','HTTP','IM') | 动作类型 |
| config | JSON NOT NULL | 动作配置 |
| enabled | BOOLEAN DEFAULT TRUE | 是否启用 |

各类型 config 结构：

```json
// ALERT - 平台告警
{ "titleTemplate": "设备 {device_id} 温度过高", "contentTemplate": "当前值 {value}°C" }

// MQTT - 下发指令
{ "topic": "vionix/{tenant_id}/{device_id}/command", "payload": {"action": "shutdown"} }

// HTTP - 回调
{ "url": "https://api.example.com/alert", "method": "POST", "headers": {}, "bodyTemplate": "{...}", "secret": "hmac-signing-secret" }

// IM - 推送
{ "type": "DINGTALK|WECHAT|FEISHU", "webhookUrl": "https://...", "contentTemplate": "..." }
```

模板变量：`{tenant_id}`、`{device_id}`、`{metric}`、`{value}`、`{threshold}`、`{trigger_time}`

**敏感字段加密存储**：

`rule_action.config` JSON 中的敏感字段在写入数据库前加密，读出后解密：

| 字段 | 加密方式 | 说明 |
|------|---------|------|
| IM 类型的 `webhookUrl` | AES-256-GCM | 含鉴权 Token，需加密存储 |
| HTTP 类型的 `headers` 中鉴权字段 | AES-256-GCM | 如 Authorization、X-API-Key 等 |
| HTTP 类型的 `secret`（签名密钥） | AES-256-GCM | 用于 HMAC-SHA256 请求签名 |

加密粒度为**字段级部分加密**：config JSON 中仅敏感字段的值被替换为密文，其余字段保持明文，便于规则展示和管理。

`alert_action_log` 中记录的 request/response 对敏感字段做脱敏处理（同审计日志脱敏规则）。

**HTTP 回调请求签名**：

配置 `secret` 字段后，ActionDispatcher 发送 HTTP 回调时自动添加签名头：

```
X-Vionix-Signature: hmac-sha256={hmac_hex}
X-Vionix-Timestamp: {unix_timestamp_ms}
```

签名计算方式：`HMAC-SHA256(secret, timestamp + "\n" + body)`。接收方通过验证签名确认请求来自 Vionix 平台。`secret` 为空时不添加签名头。

### 3.6 告警记录 `alert`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT NOT NULL | 租户 ID，行级隔离 |
| rule_id | BIGINT NOT NULL | 关联规则 |
| rule_name | VARCHAR(100) NOT NULL | 规则名称快照 |
| device_id | VARCHAR(64) NOT NULL | 触发设备 |
| severity | ENUM('INFO','WARNING','CRITICAL') | 告警级别 |
| trigger_value | DOUBLE NOT NULL | 触发时的值 |
| trigger_message | VARCHAR(500) | 触发描述信息 |
| status | ENUM('FIRING','RESOLVED','ESCALATED','ACKNOWLEDGED') | 告警状态，ACKNOWLEDGED 表示运维人员已确认处理 |
| trigger_time | DATETIME NOT NULL | 触发时间 |
| resolve_time | DATETIME NULL | 恢复时间 |
| escalate_time | DATETIME NULL | 升级时间 |
| acknowledged_by | BIGINT NULL | 确认人用户 ID（NULL 表示未确认） |
| acknowledged_at | DATETIME NULL | 确认时间 |

索引：`idx_alert_status(status)`、`idx_alert_device_time(device_id, trigger_time)`

### 3.7 动作执行日志 `alert_action_log`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| alert_id | BIGINT NOT NULL | 关联告警 |
| action_type | ENUM('ALERT','MQTT','HTTP','IM') | 动作类型 |
| request | TEXT | 请求内容 |
| response | TEXT | 响应内容 |
| success | BOOLEAN NOT NULL | 是否成功 |
| retry_count | INT DEFAULT 0 | 重试次数 |
| created_at | DATETIME | 创建时间 |

---

## 4. 核心组件

### 4.1 RuleEngine（规则引擎）

- 启动时从 MySQL 加载所有 `enabled=true` 的规则及其 conditions/actions，编译为 `CompiledRule` 对象缓存到内存
- 按 scope 建立匹配索引，MQTT 消息到来时只检查相关规则
- 规则增删改/启用禁用时通过 Spring ApplicationEvent 刷新缓存：RuleService 发出 `RuleChangedEvent`，RuleEngine 监听并重新加载对应规则

匹配索引结构：

```
按 tenant_id 分区索引：

tenantIndex = {
  tenant_1: {
    GLOBAL 规则列表:  [rule1, rule2, ...]
    DEVICE 索引:     { "sensor-001": [rule3, rule5] }
    GROUP 索引:      { "group-42": [rule4, rule7] }
  },
  tenant_2: { ... }
}

MQTT 消息到达（携带 tenant_id）:
  tenantRules = tenantIndex[tenant_id]
  candidateRules = tenantRules.GLOBAL + tenantRules.DEVICE[device_id] + tenantRules.GROUP[device.group_id]
```

**MQTT 消息租户和设备解析**：

MQTT Topic 规范：`vionix/{tenantId}/{deviceId}/telemetry`

Spring Boot MQTT Consumer 从 topic 路径解析 tenantId 和 deviceId：
1. 解析 tenantId → 定位租户规则分区
2. 解析 deviceId → 查询设备所属分组

**设备分组内存映射**：

启动时从 `device_group_member` 表加载 `deviceId → Set<groupId>` 映射，缓存在 `ConcurrentHashMap<String, Set<Long>>` 中。设备绑定/解绑分组时通过 ApplicationEvent 刷新映射。避免每次 MQTT 消息查数据库。

```
// 伪代码
Map<String, Set<Long>> deviceGroupIndex;  // deviceId → groupIds

// MQTT 消息到达
String deviceId = extractDeviceId(topic);
Set<Long> groupIds = deviceGroupIndex.getOrDefault(deviceId, emptySet());
candidateRules = globalRules
    + deviceRules.get(deviceId)
    + groupIds.flatMap(gid -> groupRules.get(gid));
```

### 4.2 StateTracker（状态跟踪器）

- 每个设备每个指标维护一个滑动窗口（环形缓冲区，默认保留最近 600 秒数据点）
- `duration_seconds > 0`：检查窗口内是否持续满足阈值
- `condition_type = RATE`：用窗口内时间范围的首尾差值计算变化率
- 定期清理长时间无数据的设备窗口，防止内存泄漏
- **时间异常保护**：消息时间戳与系统时间偏差超过 ±5 分钟时，丢弃该数据点并记录警告日志。环形缓冲区按**每秒一个槽位**组织（取该秒内最新值），共 600 个槽位。同一秒内多条消息仅保留最后一条，避免高频设备占用过多内存。
- **容量控制**：默认最大跟踪 10,000 个设备，超出后采用 LRU 淘汰最久未活跃的设备。单设备窗口内存约 48KB（600 秒 × 5 指标 × 16 字节），10,000 设备约 480MB

### 4.3 AlertManager（告警管理器）

- 维护 `Map<ruleId + deviceId, AlertState>` 告警状态映射
- **频率抑制**：从上一次告警创建（FIRING 或 ESCALATED）的时间开始计算 suppress_minutes 窗口，窗口内不重复触发。告警 RESOLVED 后条件再次满足视为新告警，不受 suppress_minutes 约束。
- **恢复检测**：当所有触发条件（AND/OR 分组）均不满足时，立即标记 RESOLVED 并发送恢复通知。对于持续时间条件（duration_seconds > 0），只要当前时刻条件不满足即视为恢复，不要求持续不满足
- **恢复确认窗口**：为避免告警抖动（flapping），恢复判定增加确认机制 —— 条件持续不满足 `resolve_confirm_seconds`（默认 60 秒）后标记 RESOLVED。可通过规则配置覆盖默认值，`resolve_confirm_seconds = 0` 表示立即恢复。恢复通知在确认后才发出。
- **告警升级**：FIRING 状态持续超过 escalation_minutes，标记 ESCALATED 并触发升级动作（如通知更高级别 IM 群）

**规则删除级联行为**：删除规则时，RuleEngine 先将该规则关联的所有 FIRING 状态告警标记为 RESOLVED（trigger_message 记录 "规则已删除"），再级联删除 rule_condition、rule_action 记录，最后从内存缓存中移除规则。alert 和 alert_action_log 保留不删除（用于历史审计）。

### 4.4 ActionDispatcher（动作分发器）

- 根据规则关联的 rule_action 列表，异步并行执行所有动作
- 每个动作类型有独立的 Executor 实现
- 失败重试 1 次，记录到 alert_action_log

**执行配置**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 线程池核心线程数 | 4 | ActionExecutor 线程池 |
| 线程池最大线程数 | 8 | 高峰期扩容上限 |
| 队列容量 | 100 | 待执行任务队列 |
| HTTP 连接超时 | 5 秒 | HTTP 回调连接超时 |
| HTTP 读取超时 | 10 秒 | HTTP 回调读取超时 |
| IM 推送超时 | 5 秒 | Webhook 推送超时 |
| 重试间隔 | 3 秒 | 失败后延迟 3 秒重试 1 次 |

超时和重试参数可在 `rule_action.config` 中通过 `timeout` 字段覆盖。

### 4.5 单实例部署约束

当前设计基于**单实例部署**，RuleEngine、StateTracker、AlertManager 均为 JVM 内存状态。若需多实例部署：

| 组件 | 单实例 | 多实例方案 |
|------|--------|-----------|
| RuleEngine | 本地 ConcurrentHashMap | 每实例独立缓存，规则变更时通过 Redis Pub/Sub 通知所有实例刷新 |
| StateTracker | 本地环形缓冲区 | MQTT 消息按 device_id 一致性哈希路由到固定实例，或迁移至 Redis Stream |
| AlertManager | 本地 HashMap | 迁移至 Redis HashMap，以 `alert:state:{ruleId}:{deviceId}` 为 Key |
| 定时巡检 | 本地 TaskScheduler | Redis 分布式锁保证单实例执行 |

多实例部署的完整方案需根据实际数据量和性能需求另行设计。

### 4.5.1 重启恢复策略

应用重启时 JVM 内存状态丢失，需以下恢复机制：

**AlertManager 恢复**：启动时从数据库加载所有 `status = 'FIRING'` 的 alert 记录，重建内存状态映射 `Map<ruleId + deviceId, AlertState>`。恢复后告警抑制（suppress_minutes）和升级（escalation_minutes）计时从 alert 原始 `trigger_time` 开始计算，不因重启而重置。

**StateTracker 恢复**（可选）：启动时从 InfluxDB `device_raw` bucket 查询每个设备最近 10 分钟的历史数据，预热滑动窗口。若跳过此步骤，StateTracker 需重新积累数据，`duration_seconds > 0` 的条件在积累期间存在检测盲区（接受短暂的检测空窗）。

**定时巡检任务恢复**：启动时重新加载所有 `schedule_cron IS NOT NULL` 的规则，通过 TaskScheduler 重新注册 ScheduledFuture，与正常启动流程一致。

### 4.6 双模式执行

**实时模式**：MQTT Consumer 收到消息 → RuleEngine 即时评估 → AlertManager → ActionDispatcher

**定时巡检模式**：按 `schedule_cron` 查询 InfluxDB 做规则评估，适用于：
- 设备离线检测（某设备 N 分钟无数据上报）
- 聚合指标规则（基于分钟/小时级聚合值）

**动态任务调度**：

定时巡检不使用 Spring `@Scheduled` 注解（cron 表达式编译时固定），改用 `TaskScheduler` 动态注册：

- 启动时加载所有 `schedule_cron IS NOT NULL` 的规则，为每条规则注册 `ScheduledFuture<?>`
- 每个规则有独立的 cron 表达式，到时执行 InfluxDB 查询 → RuleEngine 评估
- 规则增删改/启用禁用时，取消旧 `ScheduledFuture` 并重新注册
- 使用 `ConcurrentHashMap<Long, ScheduledFuture<?>>` 管理任务映射

单实例部署时任务正常执行；多实例部署需通过分布式锁（Redis `SETNX`）保证同一时刻只有一个实例执行巡检，避免重复告警。

---

## 5. API 设计

### 5.1 规则管理

```
POST   /api/rules              创建规则（含 conditions + actions）
PUT    /api/rules/{id}          更新规则
DELETE /api/rules/{id}          删除规则（级联删除 rule_condition、rule_action；关联的 FIRING 告警自动标记 RESOLVED 并记录 trigger_message='规则已删除'）
PUT    /api/rules/{id}/toggle   启用/禁用规则
GET    /api/rules               分页查询规则列表
GET    /api/rules/{id}          规则详情
```

创建/更新请求体：

```json
{
  "name": "高温告警",
  "description": "窑炉温度超过阈值",
  "scope": "GROUP",
  "groupId": 42,
  "severity": "CRITICAL",
  "suppressMinutes": 5,
  "escalationMinutes": 30,
  "cooldownSeconds": 10,
  "conditions": [
    {
      "conditionType": "THRESHOLD",
      "groupIndex": 0,
      "logicOp": "AND",
      "metric": "temperature",
      "operator": "GT",
      "threshold": 80.0,
      "durationSeconds": 300
    },
    {
      "conditionType": "RATE",
      "groupIndex": 1,
      "logicOp": "OR",
      "metric": "temperature",
      "rateWindowSeconds": 60,
      "rateDirection": "UP",
      "threshold": 10.0
    }
  ],
  "actions": [
    {
      "actionType": "ALERT",
      "config": { "titleTemplate": "设备 {device_id} 温度过高" }
    },
    {
      "actionType": "IM",
      "config": { "type": "DINGTALK", "webhookUrl": "https://..." }
    }
  ]
}
```

### 5.2 告警查询

```
GET /api/alerts            分页查询告警记录
GET /api/alerts/firing     当前正在触发的告警
GET /api/alerts/stats      告警统计（按级别/设备/时间段聚合）
PUT  /api/alerts/{id}/ack  确认告警（标记为已处理）
```

**告警确认（ACK）**：`PUT /api/alerts/{id}/ack` 将 FIRING 或 ESCALATED 状态的告警标记为 ACKNOWLEDGED，记录确认人和确认时间。ACK 不影响告警的自动恢复检测——条件不满足时仍会自动转为 RESOLVED。仅同租户用户可操作。

查询参数：`severity`、`status`、`deviceId`、`startTime`、`endTime`、`pageNum`（默认1）、`pageSize`

### 5.3 设备分组管理

```
POST   /api/device-groups              创建分组
PUT    /api/device-groups/{id}         更新分组
DELETE /api/device-groups/{id}         删除分组
GET    /api/device-groups              分组列表
POST   /api/device-groups/{id}/devices 绑定设备到分组
DELETE /api/device-groups/{id}/devices 从分组移除设备
```

### 5.4 告警实时推送

复用现有 WebSocket 通道（`/ws`），新增告警消息类型：

```json
// 告警触发
{ "type": "ALERT_FIRING", "data": { "alertId": 123, "ruleName": "高温告警", "severity": "CRITICAL", "deviceId": "sensor-001", "triggerValue": 92.5 }}

// 告警恢复
{ "type": "ALERT_RESOLVED", "data": { "alertId": 123, "ruleName": "高温告警", "resolveTime": "..." }}

// 告警升级
{ "type": "ALERT_ESCALATED", "data": { "alertId": 123, "escalateTime": "..." }}
```

---

## 6. DDL

```sql
CREATE TABLE device_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant (tenant_id)
);

CREATE TABLE device_group_member (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id    BIGINT NOT NULL,
    device_id   VARCHAR(64) NOT NULL,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_group_device (group_id, device_id),
    INDEX idx_member_device (device_id)
);

CREATE TABLE rule (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    scope               ENUM('DEVICE','GROUP','GLOBAL') NOT NULL,
    device_id           VARCHAR(64)          NULL,
    group_id            BIGINT               NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    severity            ENUM('INFO','WARNING','CRITICAL') NOT NULL DEFAULT 'WARNING',
    suppress_minutes    INT NOT NULL DEFAULT 5,
    escalation_minutes  INT NOT NULL DEFAULT 30,
    cooldown_seconds    INT NOT NULL DEFAULT 10,
    resolve_confirm_seconds INT NOT NULL DEFAULT 60,   -- 恢复确认窗口（秒），0 表示立即恢复
    schedule_cron       VARCHAR(50)          NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rule_tenant (tenant_id)
);

CREATE TABLE rule_condition (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id             BIGINT NOT NULL,
    condition_type      ENUM('THRESHOLD','RATE') NOT NULL DEFAULT 'THRESHOLD',
    group_index         INT NOT NULL DEFAULT 0,
    logic_op            ENUM('AND','OR') NOT NULL DEFAULT 'AND',
    metric              VARCHAR(64) NOT NULL,
    operator            ENUM('GT','GTE','LT','LTE','EQ','NEQ') NOT NULL,
    threshold           DOUBLE NOT NULL,
    duration_seconds    INT NOT NULL DEFAULT 0,
    rate_window_seconds INT NOT NULL DEFAULT 0,
    rate_direction      ENUM('UP','DOWN','ANY') NOT NULL DEFAULT 'ANY',
    INDEX idx_condition_rule (rule_id)
);

CREATE TABLE rule_action (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id     BIGINT NOT NULL,
    action_type ENUM('ALERT','MQTT','HTTP','IM') NOT NULL,
    config      JSON NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_action_rule (rule_id)
);

CREATE TABLE alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    rule_id         BIGINT NOT NULL,
    rule_name       VARCHAR(100) NOT NULL,
    device_id       VARCHAR(64) NOT NULL,
    severity        ENUM('INFO','WARNING','CRITICAL') NOT NULL,
    trigger_value   DOUBLE NOT NULL,
    trigger_message VARCHAR(500),
    status          ENUM('FIRING','RESOLVED','ESCALATED','ACKNOWLEDGED') NOT NULL DEFAULT 'FIRING',
    trigger_time    DATETIME NOT NULL,
    resolve_time    DATETIME          NULL,
    escalate_time   DATETIME          NULL,
    acknowledged_by BIGINT            NULL,
    acknowledged_at DATETIME          NULL,
    INDEX idx_alert_tenant (tenant_id),
    INDEX idx_alert_status (status),
    INDEX idx_alert_rule_device (rule_id, device_id),
    INDEX idx_alert_device_time (device_id, trigger_time),
    INDEX idx_alert_tenant_status_time (tenant_id, status, trigger_time),
    INDEX idx_alert_tenant_severity_time (tenant_id, severity, trigger_time)
);

CREATE TABLE alert_action_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id    BIGINT NOT NULL,
    action_type ENUM('ALERT','MQTT','HTTP','IM') NOT NULL,
    request     TEXT,
    response    TEXT,
    success     BOOLEAN NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_log_alert (alert_id)
);
```

---

## 7. 项目结构

### 7.1 后端包结构

```
com.vionix.backend/
├── rule/
│   ├── controller/
│   │   ├── RuleController.java
│   │   ├── AlertController.java
│   │   └── DeviceGroupController.java
│   ├── model/
│   │   ├── entity/
│   │   │   ├── Rule.java
│   │   │   ├── RuleCondition.java
│   │   │   ├── RuleAction.java
│   │   │   ├── Alert.java
│   │   │   ├── AlertActionLog.java
│   │   │   ├── DeviceGroup.java
│   │   │   └── DeviceGroupMember.java
│   │   ├── dto/
│   │   │   ├── RuleCreateRequest.java
│   │   │   ├── RuleUpdateRequest.java
│   │   │   ├── RuleDetailResponse.java
│   │   │   ├── AlertQueryRequest.java
│   │   │   └── AlertResponse.java
│   │   └── enums/
│   │       ├── RuleScope.java
│   │       ├── Severity.java
│   │       ├── ConditionType.java
│   │       ├── Operator.java
│   │       ├── ActionType.java
│   │       └── AlertStatus.java
│   ├── mapper/
│   │   ├── RuleMapper.java
│   │   ├── RuleConditionMapper.java
│   │   ├── RuleActionMapper.java
│   │   ├── AlertMapper.java
│   │   ├── AlertActionLogMapper.java
│   │   ├── DeviceGroupMapper.java
│   │   └── DeviceGroupMemberMapper.java
│   ├── service/
│   │   ├── RuleService.java
│   │   ├── AlertService.java
│   │   └── DeviceGroupService.java
│   └── engine/
│       ├── RuleEngine.java
│       ├── RuleMatcher.java
│       ├── CompiledRule.java
│       ├── StateTracker.java
│       ├── AlertManager.java
│       ├── ActionDispatcher.java
│       └── action/
│           ├── ActionExecutor.java
│           ├── AlertActionExecutor.java
│           ├── MqttActionExecutor.java
│           ├── HttpActionExecutor.java
│           └── ImActionExecutor.java
```

### 7.2 前端页面结构

```
views/rule/
├── RuleList.vue            # 规则列表页
├── RuleForm.vue            # 规则编辑页
├── AlertList.vue           # 告警记录列表
└── DeviceGroup.vue         # 设备分组管理

components/rule/
├── ConditionEditor.vue     # 条件编辑器
├── ActionEditor.vue        # 动作编辑器
└── AlertBadge.vue          # 实时告警角标
```
