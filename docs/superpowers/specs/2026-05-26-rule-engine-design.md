# Voinix 规则引擎设计文档

## 1. 概述

Voinix 物联网监控平台自建轻量规则引擎，支持设备数据的实时条件检测、定时巡检、告警管理与动作分发。

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

唯一约束：`(group_id, device_id)`

### 3.3 规则主表 `rule`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
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

示例：`(温度>80 持续5分钟) OR (温度1分钟内上升>10度)` → 两个条件分别在 group_index 0 和 1。

`condition_type = THRESHOLD` 时：比较 metric 当前值与 threshold。
`condition_type = RATE` 时：在 rate_window_seconds 窗口内计算 metric 变化量，与 threshold 比较。

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
{ "topic": "vionix/device/{device_id}/command", "payload": {"action": "shutdown"} }

// HTTP - 回调
{ "url": "https://api.example.com/alert", "method": "POST", "headers": {}, "bodyTemplate": "{...}" }

// IM - 推送
{ "type": "DINGTALK|WECHAT|FEISHU", "webhookUrl": "https://...", "contentTemplate": "..." }
```

模板变量：`{device_id}`、`{metric}`、`{value}`、`{threshold}`、`{trigger_time}`

### 3.6 告警记录 `alert`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| rule_id | BIGINT NOT NULL | 关联规则 |
| rule_name | VARCHAR(100) NOT NULL | 规则名称快照 |
| device_id | VARCHAR(64) NOT NULL | 触发设备 |
| severity | ENUM('INFO','WARNING','CRITICAL') | 告警级别 |
| trigger_value | DOUBLE NOT NULL | 触发时的值 |
| trigger_message | VARCHAR(500) | 触发描述信息 |
| status | ENUM('FIRING','RESOLVED','ESCALATED') | 告警状态 |
| trigger_time | DATETIME NOT NULL | 触发时间 |
| resolve_time | DATETIME NULL | 恢复时间 |
| escalate_time | DATETIME NULL | 升级时间 |

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
- 规则增删改/启用禁用时通过事件刷新缓存

匹配索引结构：

```
GLOBAL 规则列表:  [rule1, rule2, ...]
DEVICE 索引:     { "sensor-001": [rule3, rule5] }
GROUP 索引:      { "group-42": [rule4, rule7] }

MQTT 消息到达:
  candidateRules = GLOBAL规则 + DEVICE索引[device_id] + GROUP索引[device.group_id]
```

### 4.2 StateTracker（状态跟踪器）

- 每个设备每个指标维护一个滑动窗口（环形缓冲区，默认保留最近 600 秒数据点）
- `duration_seconds > 0`：检查窗口内是否持续满足阈值
- `condition_type = RATE`：用窗口内时间范围的首尾差值计算变化率
- 定期清理长时间无数据的设备窗口，防止内存泄漏

### 4.3 AlertManager（告警管理器）

- 维护 `Map<ruleId + deviceId, AlertState>` 告警状态映射
- **频率抑制**：上次触发时间 + suppress_minutes 内不再触发
- **恢复检测**：条件不满足时标记 RESOLVED，发送恢复通知
- **告警升级**：FIRING 状态持续超过 escalation_minutes，标记 ESCALATED 并触发升级动作（如通知更高级别 IM 群）

### 4.4 ActionDispatcher（动作分发器）

- 根据规则关联的 rule_action 列表，异步并行执行所有动作
- 每个动作类型有独立的 Executor 实现
- 失败重试 1 次，记录到 alert_action_log

### 4.5 双模式执行

**实时模式**：MQTT Consumer 收到消息 → RuleEngine 即时评估 → AlertManager → ActionDispatcher

**定时巡检模式**：`@Scheduled` 定时任务，按 `schedule_cron` 查询 InfluxDB 做规则评估，适用于：
- 设备离线检测（某设备 N 分钟无数据上报）
- 聚合指标规则（基于分钟/小时级聚合值）

---

## 5. API 设计

### 5.1 规则管理

```
POST   /api/rules              创建规则（含 conditions + actions）
PUT    /api/rules/{id}          更新规则
DELETE /api/rules/{id}          删除规则
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
```

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
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE device_group_member (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id    BIGINT NOT NULL,
    device_id   VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_group_device (group_id, device_id)
);

CREATE TABLE rule (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    schedule_cron       VARCHAR(50)          NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
    rate_direction      ENUM('UP','DOWN','ANY') NOT NULL DEFAULT 'ANY'
);

CREATE TABLE rule_action (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id     BIGINT NOT NULL,
    action_type ENUM('ALERT','MQTT','HTTP','IM') NOT NULL,
    config      JSON NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id         BIGINT NOT NULL,
    rule_name       VARCHAR(100) NOT NULL,
    device_id       VARCHAR(64) NOT NULL,
    severity        ENUM('INFO','WARNING','CRITICAL') NOT NULL,
    trigger_value   DOUBLE NOT NULL,
    trigger_message VARCHAR(500),
    status          ENUM('FIRING','RESOLVED','ESCALATED') NOT NULL DEFAULT 'FIRING',
    trigger_time    DATETIME NOT NULL,
    resolve_time    DATETIME          NULL,
    escalate_time   DATETIME          NULL,
    INDEX idx_alert_status (status),
    INDEX idx_alert_device_time (device_id, trigger_time)
);

CREATE TABLE alert_action_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id    BIGINT NOT NULL,
    action_type ENUM('ALERT','MQTT','HTTP','IM') NOT NULL,
    request     TEXT,
    response    TEXT,
    success     BOOLEAN NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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
