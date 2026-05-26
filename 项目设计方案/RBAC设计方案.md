# Vionix 多租户 RBAC 设计方案

## 1. 概述

Vionix 引入多租户隔离与 RBAC 权限控制，覆盖认证（JWT 双 Token + SHA-256）、授权（角色权限矩阵）、数据安全（RSA 加解密 + 盐值）三个维度。

### 安全架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                       Vue 前端                              │
│  ┌──────────┐  ┌──────────────────────────────────┐        │
│  │ RSA 公钥  │  │  AES 会话密钥 (RSA 加密传输)      │        │
│  │ 加密密码   │  │  敏感字段加密                      │        │
│  └──────────┘  └──────────────────────────────────┘        │
└────────────┬──────────────────────────┬────────────────────┘
             │ HTTPS                    │ WS (Token 鉴权)
┌────────────┴──────────────────────────┴────────────────────┐
│                    Spring Boot 后端                         │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────────────┐│
│  │ RSA 私钥    │  │ JWT 双Token  │  │ RBAC 鉴权拦截器       ││
│  │ 解密敏感数据 │  │ 签发/刷新     │  │ 注解 + AOP           ││
│  └────────────┘  └──────┬──────┘  └──────────────────────┘│
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────────┐│
│  │                    MySQL (多租户)                        ││
│  │  tenant → user → user_role ← role ← role_permission    ││
│  │                        ↓                                ││
│  │              permission (菜单/API/数据)                  ││
│  └─────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────┘
```

---

## 2. 多租户模型

### 2.1 隔离策略：共享数据库 + tenant_id 行级隔离

| 策略 | 选择 | 原因 |
|------|------|------|
| 数据库 | 共享 MySQL，行级隔离 | 运维成本低，适合 IoT SaaS 中小规模场景 |
| 隔离字段 | 所有业务表携带 `tenant_id` | 查询自动拼接租户条件，ORM 层透明过滤 |
| 超级管理员 | 不属于任何租户，`tenant_id = 0`（平台级特殊值，拦截器中显式跳过） | 管理所有租户，可跨租户操作 |

### 2.2 租户生命周期

```
创建租户 → 初始化管理员账号 → 分配默认角色 → 业务运行 → 停用/启用
```

### 2.3 租户配置

每个租户可独立配置：规则数量上限、设备数量上限、告警通道（IM 类型）、功能模块开关等。

---

## 3. RBAC 权限模型

### 3.1 模型层级

```
用户(User) ──M:N──→ 角色(Role) ──M:N──→ 权限(Permission)
    │                    │
    │                    └── 租户隔离（角色属于租户）
    │
    └── 必须属于某个租户（超级管理员除外）
```

### 3.2 权限类型

| 类型 | 标识格式 | 示例 | 说明 |
|------|---------|------|------|
| 菜单权限 | `menu:<module>` | `menu:monitor`, `menu:rule`, `menu:dashboard` | 控制前端菜单/页面可见性 |
| API 权限 | `api:<module>:<action>` | `api:rule:create`, `api:alert:view`, `api:dashboard:create` | 控制后端接口访问 |
| 数据权限 | `data:<scope>` | `data:self`, `data:group`, `data:tenant` | 控制数据可见范围 |

### 3.3 数据权限范围

| 范围 | 说明 |
|------|------|
| `data:self` | 仅自己的数据 |
| `data:group` | 所属分组的数据 |
| `data:tenant` | 整个租户的数据 |
| `data:all` | 全部数据（仅超级管理员） |

### 3.4 预置角色

| 角色标识 | 角色名称 | 说明 |
|---------|---------|------|
| `SUPER_ADMIN` | 超级管理员 | 平台运维，管理所有租户，不受租户限制 |
| `TENANT_ADMIN` | 租户管理员 | 租户内最高权限，管理本租户用户和配置 |
| `TENANT_OPERATOR` | 租户运维员 | 管理规则、告警、设备分组 |
| `TENANT_VIEWER` | 租户观察者 | 只读查看监控数据、告警记录 |

租户管理员可在租户内自定义角色并分配权限。

---

## 4. 认证方案

### 4.1 JWT 双 Token 机制

| Token | 有效期 | 存储位置 | 用途 |
|-------|--------|---------|------|
| accessToken | 30 分钟 | 前端内存（不存 localStorage） | 接口鉴权，放 Authorization Header |
| refreshToken | 7 天 | httpOnly + Secure Cookie | 仅用于刷新 accessToken |

**签名算法**：推荐使用 RS256（RSA-SHA256）非对称签名。私钥仅后端持有用于签发，公钥用于验证，避免 HS256 对称密钥泄露后可伪造 Token 的风险。JwtTokenProvider 应显式锁定算法，禁止 `alg: none` 攻击。

**Token 载荷：**

```json
// accessToken
{
  "sub": "userId",
  "tid": "tenantId",
  "roles": ["TENANT_ADMIN"],
  "jti": "tokenUniqueId",
  "iat": 1740000000,
  "exp": 1740001800
}

// refreshToken
{
  "sub": "userId",
  "tid": "tenantId",
  "jti": "refreshTokenId",
  "type": "refresh",
  "iat": 1740000000,
  "exp": 1740604800
}
```

> accessToken 仅携带身份标识（userId、tenantId、roles），权限列表通过 Redis 缓存实时查询（Key: `auth:perms:{userId}`），避免 Token 体积过大且保证权限变更即时生效。

### 4.2 SHA-256 二次加密 Token

refreshToken 存入数据库前，经过 SHA-256 + 应用固定盐值二次加密，确保数据库泄露也无法还原可用 Token。

```
流程：
1. 签发 refreshToken (JWT)
2. 存储: SHA-256(refreshToken + ${auth.token-hash-salt}) → hashedToken
3. DB 存储: { id, userId, hashedToken, expiresAt }
4. 验证: 客户端提交 refreshToken → SHA-256(token + 固定salt) == stored hashedToken
```

**数据库中不存储 refreshToken 明文**，只存哈希值。盐值配置在 `application.yml` 中，所有 Token 共用。

### 4.3 盐值机制

| 场景 | 盐值来源 | 哈希算法 | 说明 |
|------|---------|---------|------|
| 用户密码 | BCrypt 内置随机 salt | `BCrypt(password)` | BCrypt 自适应哈希（cost=10），内置随机 salt |
| refreshToken 存储 | 应用固定盐值，配置在 `auth.token-hash-salt` | `SHA-256(token + salt)` | Token 明文不落库，所有 Token 共用同一盐值 |

### 4.4 登录流程

```
┌──────────┐                                    ┌──────────┐
│  前端     │                                    │  后端     │
└────┬─────┘                                    └────┬─────┘
     │  1. GET /api/auth/public-key                  │
     │ ──────────────────────────────────────────→   │
     │  2. 返回 RSA 公钥                              │
     │ ←──────────────────────────────────────────   │
     │                                               │
     │  3. RSA加密(password) + RSA加密(nonce) + RSA加密(timestamp)  │
     │     POST /api/auth/login                       │
     │     { username, encPassword, encNonce, encTimestamp }    │
     │ ──────────────────────────────────────────→   │
     │                                               │
     │                    4. RSA私钥解密 password + nonce + timestamp
     │                    5. 校验 nonce 防重放（Redis SET NX，TTL 5min）+ timestamp 偏差（±5min）
     │                    5.5 校验租户状态（tenant.status），停用租户拒绝登录
     │                    6. BCrypt.checkpw(inputPassword, user.password)
     │                    7. 签发 accessToken + refreshToken
     │                    8. SHA-256(refreshToken + 固定盐值) 存库
     │                                               │
     │  9. 返回 accessToken（Body）                    │
     │     + refreshToken（Set-Cookie httpOnly）       │
     │ ←──────────────────────────────────────────   │
     │                                               │
     │  10. 后续请求 Header: Authorization: Bearer {accessToken}
     │ ──────────────────────────────────────────→   │
```

### 4.5 Token 刷新流程

```
前端                                    后端
 │  accessToken 过期 (401)                │
 │  → 自动 POST /api/auth/refresh        │
 │    (refreshToken 由 Cookie 自动携带)    │
 │ ────────────────────────────────────→  │
 │                                       │  SHA-256(cookie中的token + 固定盐值)
 │                                       │  与 DB 哈希比对
 │                                       │  校验有效期
 │                                       │  签发新 accessToken + refreshToken
 │                                       │  旧 refreshToken 失效（删除/标记）
 │                                       │  新 refreshToken SHA-256 存库
 │  ← 返回新 accessToken                  │
 │    + Set-Cookie 新 refreshToken        │
```

**安全措施：**
- 每次刷新后旧 refreshToken 立即失效（一次性使用），防止 Token 重放
- 并发刷新保护：使用数据库乐观锁（`DELETE FROM token_auth WHERE token_hash = ? AND user_id = ?`）保证原子性。多标签页并发刷新时，实施"宽容窗口"机制 —— 刷新成功后，将旧 token_hash → 新 Token 信息存入 Redis（Key: `auth:grace:{old_token_hash}`，Value: 新 accessToken + 新 refreshToken_hash，TTL 30 秒）。后续 30 秒内相同旧 token 的刷新请求先查 Redis grace key，命中则直接返回缓存的新 Token，避免用户被强制登出
- 同一用户同时有效 refreshToken 数量上限（默认 5），超出时按创建时间排序删除最早的 token_auth 记录，确保不超过上限。应用层在每次签发新 refreshToken 时执行检查和清理
- accessToken 存前端内存（不持久化），用户刷新页面后 accessToken 丢失，前端自动通过 Cookie 中的 refreshToken 走刷新流程恢复会话

### 4.6 登出流程

```
前端 → DELETE /api/auth/logout
  1. 删除 DB 中该用户的 refreshToken 记录
  2. accessToken 加入 Redis 黑名单（剩余有效期）
  3. 前端清除内存中 accessToken，清除 Cookie
```

### 4.7 WebSocket 认证

SockJS/STOMP 连接建立时需要认证，防止未授权访问：

**连接认证**：

```
前端：STOMP CONNECT 时在 headers 中携带 accessToken
  stompClient.connect({ Authorization: 'Bearer ' + accessToken }, onConnected, onError)

后端：Spring WebSocket ChannelInterceptor 校验 Token
  → 有效：放行连接，提取 userId/tenantId 存入 WebSocketSession
  → 过期：返回 ERROR 帧，前端触发 refresh 后重连
  → 无效：拒绝连接
```

**多租户隔离**：

STOMP destination 按 tenantId 隔离：

```
/topic/tenant/{tenantId}/device/{deviceId}/metrics    — 秒级实时数据
/topic/tenant/{tenantId}/alerts                        — 告警推送
```

后端广播消息时，从 MQTT topic 解析 tenantId，只推送给订阅了对应租户 topic 的客户端。前端只能订阅自己所属租户的 topic，尝试订阅其他租户 topic 会被 ChannelInterceptor 拒绝。

**Token 过期处理**：
- accessToken 过期时 WS 连接不断开，前端静默 refresh 后更新本地 Token
- refresh 失败时关闭 WS 连接，跳转登录页

---

## 5. RSA 加解密方案

### 5.1 密钥管理

RSA 密钥对部署时生成，写入 `application.yml`。

| 项目 | 配置 |
|------|------|
| 密钥算法 | RSA 2048-bit |
| 公钥分发 | `GET /api/auth/public-key` 返回 Base64 编码公钥 |
| 私钥存储 | `application.yml` 中 `auth.rsa.private-key` 配置项 |
| 公钥存储 | `application.yml` 中 `auth.rsa.public-key` 配置项 |

**密钥轮换（支持零停机）：**

建议每 90 天轮换一次密钥对。配置中保留新旧两套密钥，用 `kid`（Key ID）标识：

```
1. 生成新密钥对，配置新 kid（如 `kid: "2026-q2"`）
2. 更新 application.yml，保留旧密钥用于验证旧 Token
3. 新签发的 JWT Header 中携带新 `kid`，验证时根据 `kid` 选择对应密钥
4. 旧 Token 自然过期后（30 分钟），移除旧密钥配置
```

无需重启服务，通过 Spring Cloud Config 或 Nacos 配置热更新即可。

**密钥生成：**

```bash
# 生成私钥
openssl genrsa -out rsa_private.pem 2048

# 从私钥导出公钥
openssl rsa -in rsa_private.pem -pubout -out rsa_public.pem
```

将生成的 PEM 内容配置到 `application.yml`：

```yaml
auth:
  rsa:
    public-key: "MIIBIjANBgkq..."
    private-key: "MIIEvQIBADANBgkq..."
  token-hash-salt: "vionix-fixed-sha256-salt-32bytes"
```

### 5.2 敏感数据传输流程

**前端加密：**

**防重放机制**：使用 nonce（一次性随机数）替代时间戳。每次登录请求生成 32 字节随机 nonce，后端将已使用 nonce 存入 Redis（TTL = 5 分钟），重复 nonce 直接拒绝。时间戳仅用于辅助校验时钟偏差（±5 分钟）。

```javascript
// 登录时加密密码
const publicKey = await fetchPublicKey()  // 获取 RSA 公钥
const encryptedPassword = RSAEncrypt(password, publicKey)
const nonce = crypto.randomUUID()
const encryptedNonce = RSAEncrypt(nonce, publicKey)
const encryptedTimestamp = RSAEncrypt(String(Date.now()), publicKey)

request.post('/api/auth/login', {
  username,
  encPassword: encryptedPassword,
  encNonce: encryptedNonce,
  encTimestamp: encryptedTimestamp
})
```

**后端解密：**

```java
// 解密密码
String password = RSADecrypt(encPassword, privateKey)
String nonce = RSADecrypt(encNonce, privateKey)
String timestamp = RSADecrypt(encTimestamp, privateKey)

// 防重放：校验 nonce 未使用过（Redis SET NX，TTL 5 分钟）
validateNonce(nonce)
// 辅助校验时间戳偏差（±5 分钟）
validateTimestamp(timestamp)
```

### 5.3 加密范围

| 数据 | 传输加密 | 说明 |
|------|---------|------|
| 用户密码 | RSA 加密 | 登录、注册、修改密码 |
| 手机号 / 邮箱 | 传输 RSA 加密，存储 AES-256-GCM 对称加密 + SHA-256 哈希索引 | 注册、个人信息修改 |
| IM Webhook URL | AES-256-GCM 加密存储 | 规则引擎 IM 动作配置（含鉴权 Token） |
| HTTP 回调密钥 | AES-256-GCM 加密存储 | 规则引擎 HTTP 动作配置 |

**存储加密策略**：手机号/邮箱/密钥等高频使用数据使用 AES-256-GCM 对称加密存储（性能优于 RSA），密钥由应用配置管理。同时存储 SHA-256 哈希值用于等值查询和唯一性校验。RSA 仅用于传输阶段的敏感字段加密。

---

## 6. 数据模型

### 6.1 租户表 `tenant`

```sql
CREATE TABLE tenant (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    code            VARCHAR(50)  NOT NULL UNIQUE,     -- 租户编码，用于 subdomain / header 路由
    status          TINYINT NOT NULL DEFAULT 1,       -- 1=启用 0=停用
    config          JSON,                             -- 租户配置（限制项、功能开关等）
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 6.2 用户表 `sys_user`

```sql
CREATE TABLE sys_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL DEFAULT 0,        -- 0=超级管理员
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(255) NOT NULL,             -- BCrypt(rawPassword)
    nickname        VARCHAR(64),
    phone_enc       VARCHAR(512),                      -- AES-256-GCM 加密存储
    phone_hash      VARCHAR(64),                        -- SHA-256(phone + pepper)，用于唯一性校验和检索
    email_enc       VARCHAR(512),                      -- AES-256-GCM 加密存储
    email_hash      VARCHAR(64),                        -- SHA-256(email + pepper)，用于唯一性校验和检索
    avatar          VARCHAR(255),
    status          TINYINT NOT NULL DEFAULT 1,        -- 1=启用 0=禁用
    last_login_at   DATETIME,
    last_login_ip   VARCHAR(45),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_username (tenant_id, username)
);
```

### 6.3 角色表 `role`

```sql
CREATE TABLE role (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    role_code       VARCHAR(50)  NOT NULL,             -- SUPER_ADMIN / TENANT_ADMIN / 自定义
    role_name       VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,    -- 系统内置角色不可删除
    data_scope      ENUM('SELF','GROUP','TENANT','ALL') NOT NULL DEFAULT 'SELF',
    status          TINYINT NOT NULL DEFAULT 1,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_role_code (tenant_id, role_code)
);
```

### 6.4 权限表 `permission`

```sql
CREATE TABLE permission (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    perm_code       VARCHAR(100) NOT NULL UNIQUE,      -- api:rule:create
    perm_name       VARCHAR(100) NOT NULL,
    perm_type       ENUM('MENU','API','DATA') NOT NULL,
    parent_id       BIGINT DEFAULT NULL,               -- 树形结构
    sort_order      INT NOT NULL DEFAULT 0,
    path            VARCHAR(255),                      -- 前端路由 (MENU) / API 路径 (API)
    icon            VARCHAR(64),                       -- 菜单图标
    status          TINYINT NOT NULL DEFAULT 1,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_perm_type (perm_type),
    INDEX idx_parent (parent_id)
);
```

### 6.5 用户角色关联 `user_role`

```sql
CREATE TABLE user_role (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    role_id         BIGINT NOT NULL,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_role (user_id, role_id)
);
```

### 6.6 角色权限关联 `role_permission`

```sql
CREATE TABLE role_permission (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id         BIGINT NOT NULL,
    permission_id   BIGINT NOT NULL,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_perm (role_id, permission_id)
);
```

### 6.7 Token 认证记录 `token_auth`

```sql
CREATE TABLE token_auth (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,             -- SHA-256(refreshToken + 固定盐值)
    device_info     VARCHAR(255),                      -- 客户端标识（UA hash）
    ip_address      VARCHAR(45),
    expires_at      DATETIME NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_token_hash (token_hash),
    INDEX idx_token_user (user_id),
    INDEX idx_token_expires (expires_at)
);
```

### 6.8 ER 关系

```
tenant (1) ──→ (N) sys_user
sys_user (M) ←──→ (N) role (M) ←──→ (N) permission
role.tenant_id → tenant.id（租户隔离）
permission 全局共享（所有租户共用权限树）
```

---

## 7. API 设计

### 7.0 统一响应格式

所有 API 响应遵循统一格式：

```json
// 成功响应
{ "code": 200, "message": "success", "data": { ... } }

// 分页响应
{ "code": 200, "message": "success", "data": { "list": [...], "total": 100, "pageNum": 1, "pageSize": 20 } }

// 错误响应
{ "code": 401, "message": "Token 已过期", "data": null }

// 参数校验错误
{ "code": 400, "message": "参数校验失败", "data": { "field": "username", "message": "不能为空" } }
```

| HTTP 状态码 | code | 说明 |
|------------|------|------|
| 200 | 200 | 成功 |
| 400 | 400 | 参数校验错误 |
| 401 | 401 | 未认证 / Token 过期 |
| 403 | 403 | 无权限 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |

### 7.1 认证接口

```
GET    /api/auth/public-key         获取 RSA 公钥
POST   /api/auth/login              登录
POST   /api/auth/refresh            刷新 Token
DELETE /api/auth/logout              登出
GET    /api/auth/me               获取当前用户信息
```

**登录请求：**

```json
{
  "username": "admin",
  "encPassword": "RSA加密后的密码(Base64)",
  "encNonce": "RSA加密后的随机数(Base64)",
  "encTimestamp": "RSA加密后的时间戳(Base64)"
}
```

**登录响应：**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "管理员",
    "tenantId": 1,
    "roles": ["TENANT_ADMIN"],
    "permissions": ["api:rule:create", "api:alert:view", ...]
  }
}
```

**当前用户信息：**

```
GET /api/auth/me
```

用于页面刷新后通过 refreshToken 恢复会话时获取用户信息和权限列表。

响应格式与登录响应中 `user` 字段相同：
```json
{
  "id": 1,
  "username": "admin",
  "nickname": "管理员",
  "tenantId": 1,
  "roles": ["TENANT_ADMIN"],
  "permissions": ["api:rule:create", "api:alert:view", ...]
}
```

需要 accessToken 鉴权（Authorization Header）。

refreshToken 通过 `Set-Cookie: refreshToken=xxx; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=604800` 下发。

### 7.2 租户管理（超级管理员）

```
POST   /api/tenants              创建租户
PUT    /api/tenants/{id}         更新租户
DELETE /api/tenants/{id}         删除租户（级联处理见下方说明）
GET    /api/tenants              租户列表
GET    /api/tenants/{id}         租户详情
```

**租户删除级联行为**：

删除租户时需按以下顺序处理关联数据：

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 验证 | 确认租户下无活跃 FIRING 告警，或由操作员确认强制删除 |
| 2 | 禁用 | 设置 tenant.status = 0，阻断该租户所有用户登录 |
| 3 | 清理 Redis | 删除该租户所有用户的 `auth:perms:{userId}` 缓存 + Token 黑名单 |
| 4 | 删除 MySQL 数据 | 按 alert_action_log → alert → rule_action → rule_condition → rule → device_group_member → device_group → user_role → role_permission → role → sys_user → tenant 顺序级联删除 |
| 5 | 清理 InfluxDB | 通过 Flux `delete()` API 按 `tenant_id` tag 清理所有 bucket 中的数据 |

删除操作为硬删除（不可恢复），建议 UI 层增加二次确认。当前版本仅超级管理员可执行。

### 7.3 用户管理

```
POST   /api/users                创建用户
PUT    /api/users/{id}           更新用户
DELETE /api/users/{id}           删除用户
GET    /api/users                用户列表（自动过滤当前租户）
GET    /api/users/{id}           用户详情
PUT    /api/users/{id}/password  修改密码
PUT    /api/users/{id}/roles     分配角色
PUT    /api/users/{id}/status    启用/禁用用户
```

### 7.4 角色管理

```
POST   /api/roles                创建角色
PUT    /api/roles/{id}           更新角色
DELETE /api/roles/{id}           删除角色（系统角色不可删）
GET    /api/roles                角色列表
GET    /api/roles/{id}           角色详情（含权限列表）
PUT    /api/roles/{id}/permissions  分配权限
```

### 7.5 权限管理

```
GET    /api/permissions/tree     权限树（前端菜单渲染 + 角色分配用）
GET    /api/permissions          权限列表（支持 type 过滤）
```

### 7.6 鉴权方式

后端通过 **注解 + AOP 拦截器** 实现：

```java
@RequiresPermission("api:rule:create")
@PostMapping("/rules")
public Result createRule(@RequestBody RuleCreateRequest request) { ... }
```

```java
@RequiresRole("TENANT_ADMIN")
@RequiresDataScope("TENANT")
@GetMapping("/users")
public Result listUsers() { ... }
```

**拦截链路：**

```
HTTP Request
  → Filter: JWT 解析 → 构建 SecurityContext（userId, tenantId, roles, perms）
  → AOP: @RequiresPermission 校验 → 通过 / 403
  → AOP: @RequiresDataScope 注入数据范围 → MyBatis 拦截器自动拼接 tenant_id 条件
  → Controller
```

**数据范围传递机制**：

AOP 拦截器通过 ThreadLocal（SecurityContext）将数据范围传递给 MyBatis 拦截器：
1. `@RequiresDataScope("TENANT")` 注解标注在 Controller 方法上
2. PermissionAspect 解析注解，将数据范围写入 SecurityContext（ThreadLocal）
3. TenantInterceptor 从 SecurityContext 读取数据范围，动态拼接 SQL 条件

**多角色数据范围合并**：用户有多角色时取最宽范围：`ALL > TENANT > GROUP > SELF`

**`data:group` 范围说明**：需要业务表中存在 `group_id` 字段（如设备表按分组归属）。用户的分组关联通过 `user_group` 表管理。当前版本暂不实现 `data:group`，默认使用 `data:tenant`，后续按需扩展。

---

## 8. 租户上下文传递

### 8.1 租户识别

```
方式一（推荐）：JWT Token 中携带 tenantId
  登录后 Token 载荷包含 tid 字段，后端从 Token 解析

方式二：请求 Header X-Tenant-Id
  适用于跨租户管理场景（超级管理员）
```

### 8.2 数据隔离实现

MyBatis-Plus 多租户插件，自动在 SQL 中拼接 `tenant_id` 条件：

```java
@Component
public class TenantInterceptor implements InnerInterceptor {
    @Override
    public void beforeQuery(Executor executor, ...) {
        // SELECT ... FROM rule WHERE tenant_id = ?
        // 自动追加 WHERE 条件
    }
}
```

**忽略租户过滤的场景：**
- 超级管理员操作（`tenant_id = 0`，平台级特殊值，拦截器中显式跳过租户条件拼接）
- 登录认证（尚未确定租户）
- 权限表查询（`permission` 全局共享）

### 8.3 InfluxDB 租户标识传递

数据写入链路 `设备 → Mosquitto → Telegraf → InfluxDB` 中，tenant_id 的传递方式：

```
MQTT Topic 规范：vionix/{tenantId}/{deviceId}/telemetry
                              ↓
Telegraf 从 topic 解析 tenantId → 写入 InfluxDB tag: tenant_id
                              ↓
Spring Boot MQTT Consumer 从 topic 解析 tenantId → 规则引擎匹配
```

- **Telegraf 配置**：使用 `topics_regex` 从 topic 中提取 tenantId 作为 tag
- **Spring Boot**：MQTT 消息处理时从 topic 解析 tenantId，不信任 payload 中的 tenant_id
- **安全校验**：查询时 tenant_id 来自已认证的 JWT Token（`tid` 字段），不使用客户端参数

---

## 9. 前端交互

### 9.1 路由守卫

```
用户访问页面
  → 检查内存中 accessToken
    → 无 → 检查 Cookie 中 refreshToken
      → 有 → 自动调用 /api/auth/refresh
        → 成功 → 存储 accessToken，放行
        → 失败 → 跳转登录页
      → 无 → 跳转登录页
    → 有 → 解析 Token 检查过期
      → 未过期 → 放行
      → 已过期 → 触发刷新流程
```

### 9.2 权限指令

```javascript
// 按钮级权限控制
<el-button v-permission="'api:rule:create'">创建规则</el-button>

// 菜单级权限控制
const routes = buildRoutes(userPermissions)  // 根据权限过滤路由表
```

### 9.3 页面结构

```
登录页
└── 布局
    ├── 顶部导航
    │   ├── 租户名称（多租户切换，超管可见）
    │   ├── 告警角标
    │   └── 用户头像 → 个人设置 / 修改密码 / 退出
    ├── 侧边菜单（根据权限动态生成）
    │   ├── 实时监控        menu:monitor
    │   ├── 规则管理        menu:rule
    │   ├── 告警中心        menu:alert
    │   ├── 设备分组        menu:group
    │   ├── 仪表盘        menu:dashboard
    │   ├── 用户管理        menu:user        (TENANT_ADMIN+)
    │   ├── 角色管理        menu:role        (TENANT_ADMIN+)
    │   └── 系统设置        menu:system      (SUPER_ADMIN)
    └── 主内容区
```

### 9.4 前端 RSA 工具流程

```javascript
// 应用启动时获取公钥
const { publicKey } = await api.get('/api/auth/public-key')

// 登录时加密
function encryptSensitive(data) {
  const encrypt = new JSEncrypt()
  encrypt.setPublicKey(publicKey)
  return encrypt.encrypt(data)
}

const loginForm = {
  username: username,
  encPassword: encryptSensitive(password),
  encNonce: encryptSensitive(crypto.randomUUID()),
  encTimestamp: encryptSensitive(String(Date.now()))
}
```

---

## 10. 安全措施总览

| 层面 | 措施 | 说明 |
|------|------|------|
| **密码存储** | BCrypt(password) | BCrypt 自适应哈希（cost=10），内置随机 salt |
| **Token 存储** | SHA-256(refreshToken + 固定盐值) | refreshToken 明文不落库 |
| **敏感传输** | RSA 2048-bit 非对称加密 | 密码、手机号、邮箱、Webhook URL |
| **防重放** | nonce 一次性随机数 + 时间戳辅助校验 | Redis 存储已用 nonce（TTL 5min），防止截获请求重放 |
| **Token 刷新** | refreshToken 一次性使用 | 刷新后旧 Token 立即失效 |
| **Token 黑名单** | accessToken 加入 Redis 黑名单 | 登出后 Token 立即失效 |
| **登录保护** | 基于 IP + 用户名双重限流：每 IP 每分钟最多 20 次登录尝试，每用户名连续失败 5 次锁定 15 分钟，防止暴力破解和恶意锁定 |
| **Cookie 安全** | httpOnly + Secure + SameSite=Strict | 防 XSS 窃取 Token |
| **SQL 注入** | MyBatis-Plus 参数化查询 | 框架层面防护 |
| **XSS 防护** | CSP Header + 输出转义 | 前端安全 |
| **CORS** | 白名单域名 | 仅允许前端域名跨域 |
| **数据隔离** | MyBatis 多租户拦截器 | 行级自动隔离 |

---

## 11. 后端包结构

```
com.vionix.backend/
├── auth/
│   ├── controller/
│   │   └── AuthController.java
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java
│   │   └── RefreshResponse.java
│   ├── jwt/
│   │   ├── JwtTokenProvider.java          # Token 签发/解析
│   │   ├── JwtAuthenticationFilter.java   # Filter 拦截
│   │   └── TokenHashService.java          # SHA-256 + Salt 哈希
│   ├── rsa/
│   │   └── RsaCryptoService.java          # 加解密（密钥从配置读取）
│   └── security/
│       ├── SecurityContext.java           # 用户上下文（ThreadLocal）
│       ├── RequiresPermission.java        # 权限注解
│       ├── RequiresRole.java              # 角色注解
│       ├── RequiresDataScope.java         # 数据范围注解
│       └── PermissionAspect.java          # AOP 鉴权拦截
├── tenant/
│   ├── controller/
│   │   └── TenantController.java
│   ├── model/
│   │   ├── entity/Tenant.java
│   │   └── dto/TenantCreateRequest.java
│   ├── mapper/TenantMapper.java
│   └── service/TenantService.java
├── user/
│   ├── controller/UserController.java
│   ├── model/
│   │   ├── entity/User.java
│   │   ├── entity/TokenAuth.java
│   │   └── dto/
│   ├── mapper/
│   └── service/UserService.java
├── role/
│   ├── controller/RoleController.java
│   ├── model/
│   │   ├── entity/Role.java
│   │   ├── entity/Permission.java
│   │   ├── entity/UserRole.java
│   │   ├── entity/RolePermission.java
│   │   └── dto/
│   ├── mapper/
│   └── service/
│       ├── RoleService.java
│       └── PermissionService.java
├── common/
│   ├── config/
│   │   ├── MybatisTenantConfig.java       # 多租户拦截器配置
│   │   ├── RsaConfig.java                 # RSA 密钥配置
│   │   └── SecurityConfig.java            # 安全配置
│   └── interceptor/
│       └── TenantInterceptor.java         # MyBatis 多租户拦截器
└── ...（rule / metrics 等现有模块）
```

---

## 12. Redis 在安全架构中的角色

| 用途 | Key 格式 | TTL | 说明 |
|------|---------|-----|------|
| Token 黑名单 | `auth:blacklist:{jti}` | accessToken 剩余有效期 | 登出后 accessToken 失效 |
| 登录失败计数 | `auth:fail:{username}` | 15 分钟 | 连续失败超限锁定 |
| 用户权限缓存 | `auth:perms:{userId}` | accessToken 有效期 | 避免每次查库 |
| nonce 防重放 | `auth:nonce:{nonce}` | 5 分钟 | 防止登录请求重放（Redis SET NX） |

**部署模式要求**：
- **生产环境（多实例）**：必须使用 Redis，Token 黑名单和权限缓存需跨实例共享
- **开发/演示环境（单实例）**：可使用 Caffeine 本地缓存替代
- 若不使用 Redis，Token 黑名单应改为数据库表实现（如 `token_blacklist`），不可依赖本地缓存

### 12.1 缓存失效策略

| 触发场景 | 失效操作 | 说明 |
|---------|---------|------|
| 管理员修改用户角色 | 删除 `auth:perms:{userId}` | 用户权限立即刷新 |
| 管理员修改角色权限 | 批量删除该角色关联的所有用户 `auth:perms:{userId}` | 角色变更影响所有关联用户 |
| 管理员禁用/启用用户 | 删除 `auth:perms:{userId}` | 状态变更时清除缓存 |
| 用户修改密码 | 删除 `auth:perms:{userId}` + Token 黑名单 | 强制重新登录 |

实现方式：RoleService / UserService 在事务提交后发布 Spring Event，缓存监听器清除对应 Key。

---

## 13. 审计日志

多租户 SaaS 平台需记录关键操作轨迹，用于安全审计和问题追溯。

### 13.1 审计日志表 `audit_log`

```sql
CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    user_id         BIGINT NOT NULL,
    username        VARCHAR(64) NOT NULL,
    action          VARCHAR(100) NOT NULL,        -- 操作标识，如 TENANT_CREATE、ROLE_UPDATE、RULE_DELETE
    resource_type   VARCHAR(50) NOT NULL,          -- 资源类型，如 tenant、role、rule、user
    resource_id     VARCHAR(64),                   -- 资源 ID
    detail          JSON,                          -- 变更详情（before/after 快照）
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(255),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_tenant_time (tenant_id, created_at),
    INDEX idx_audit_action (action)
);
```

### 13.2 审计范围

| 类别 | 审计动作 |
|------|---------|
| 租户管理 | 创建/更新/停用租户 |
| 用户管理 | 创建/删除用户、分配角色、启用/禁用 |
| 角色管理 | 创建/删除角色、分配权限 |
| 规则管理 | 创建/更新/删除规则、启用/禁用 |
| 设备分组 | 创建/删除分组、绑定/解绑设备 |
| 认证事件 | 登录成功、登录失败、登出 |

实现方式：通过 Spring AOP `@Auditable` 注解 + 切面，自动记录操作日志，业务代码无侵入。

### 13.2.1 敏感字段脱敏

审计日志 `detail` JSON 中记录变更快照时，以下字段必须脱敏处理：

| 字段 | 脱敏方式 | 示例 |
|------|---------|------|
| password / password_hash | 不记录 | `***` |
| phone_enc / email_enc | 不记录密文 | `***` |
| phone_hash / email_hash | 不记录哈希 | `***` |
| webhookUrl（含鉴权 Token） | 不记录 | `***` |
| 其他敏感配置密钥 | 不记录 | `***` |

实现方式：`@Auditable` 注解支持 `sensitiveFields` 参数指定需脱敏的字段列表，切面在记录 detail 时自动将敏感字段值替换为 `***`。

### 13.3 数据保留与清理

| 策略 | 说明 |
|------|------|
| 在线查询 | 保留 90 天，按 `created_at` 查询 |
| 冷存储归档 | 超过 90 天的记录迁移至归档表或对象存储 |
| 定时清理 | 每日凌晨执行，`DELETE FROM audit_log WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY)` |

权限数据随应用版本迭代，应通过 Flyway/Liquibase 数据库迁移脚本管理，确保权限定义与代码同步。
