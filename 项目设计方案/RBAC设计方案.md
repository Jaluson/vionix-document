# Voinix 多租户 RBAC 设计方案

## 1. 概述

Voinix 引入多租户隔离与 RBAC 权限控制，覆盖认证（JWT 双 Token + SHA-256）、授权（角色权限矩阵）、数据安全（RSA 加解密 + 盐值）三个维度。

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
| 超级管理员 | 不属于任何租户，`tenant_id = 0` | 管理所有租户，可跨租户操作 |

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
| 菜单权限 | `menu:<module>` | `menu:monitor`, `menu:rule` | 控制前端菜单/页面可见性 |
| API 权限 | `api:<module>:<action>` | `api:rule:create`, `api:alert:view` | 控制后端接口访问 |
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

**Token 载荷：**

```json
// accessToken
{
  "sub": "userId",
  "tid": "tenantId",
  "roles": ["TENANT_ADMIN"],
  "perms": ["api:rule:create", "api:alert:view"],
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
| 用户密码 | 注册时随机生成，存入 `user.password_salt` | `SHA-256(password + salt)` → BCrypt | 双重哈希，BCrypt 自带 salt + 额外应用层 salt |
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
     │  3. RSA加密(password) + RSA加密(timestamp)     │
     │     POST /api/auth/login                       │
     │     { username, encPassword, encTimestamp }    │
     │ ──────────────────────────────────────────→   │
     │                                               │
     │                    4. RSA私钥解密 password + timestamp
     │                    5. 校验 timestamp 防重放（±5min）
     │                    6. SHA-256(inputPassword + user.salt) → hashed
     │                    7. BCrypt验证(hashed, user.password)
     │                    8. 签发 accessToken + refreshToken
     │                    9. SHA-256(refreshToken + 固定盐值) 存库
     │                                               │
     │  10. 返回 accessToken（Body）                   │
     │      + refreshToken（Set-Cookie httpOnly）      │
     │ ←──────────────────────────────────────────   │
     │                                               │
     │  11. 后续请求 Header: Authorization: Bearer {accessToken}
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
- 同一用户同时有效 refreshToken 数量上限（默认 5），超出最早失效

### 4.6 登出流程

```
前端 → DELETE /api/auth/logout
  1. 删除 DB 中该用户的 refreshToken 记录
  2. accessToken 加入 Redis 黑名单（剩余有效期）
  3. 前端清除内存中 accessToken，清除 Cookie
```

---

## 5. RSA 加解密方案

### 5.1 密钥管理

RSA 密钥对为固定配置，部署时生成，写入 `application.yml`，运行时不变。

| 项目 | 配置 |
|------|------|
| 密钥算法 | RSA 2048-bit |
| 公钥分发 | `GET /api/auth/public-key` 返回 Base64 编码公钥 |
| 私钥存储 | `application.yml` 中 `auth.rsa.private-key` 配置项 |
| 公钥存储 | `application.yml` 中 `auth.rsa.public-key` 配置项 |

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

```javascript
// 登录时加密密码
const publicKey = await fetchPublicKey()  // 获取 RSA 公钥
const encryptedPassword = RSAEncrypt(password, publicKey)
const encryptedTimestamp = RSAEncrypt(String(Date.now()), publicKey)

request.post('/api/auth/login', {
  username,
  encPassword: encryptedPassword,
  encTimestamp: encryptedTimestamp
})
```

**后端解密：**

```java
// 解密密码
String password = RSADecrypt(encPassword, privateKey)
String timestamp = RSADecrypt(encTimestamp, privateKey)

// 防重放：校验时间戳在 ±5 分钟内
validateTimestamp(timestamp)
```

### 5.3 加密范围

| 数据 | 传输加密 | 说明 |
|------|---------|------|
| 用户密码 | RSA 加密 | 登录、注册、修改密码 |
| 手机号 / 邮箱 | RSA 加密 | 注册、个人信息修改 |
| IM Webhook URL | RSA 加密 | 规则引擎 IM 动作配置（含鉴权 Token） |
| HTTP 回调密钥 | RSA 加密 | 规则引擎 HTTP 动作配置 |

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

### 6.2 用户表 `user`

```sql
CREATE TABLE user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL DEFAULT 0,        -- 0=超级管理员
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(255) NOT NULL,             -- BCrypt(SHA-256(rawPassword + salt))
    password_salt   VARCHAR(64)  NOT NULL,             -- 用户级盐值
    nickname        VARCHAR(64),
    phone_enc       VARCHAR(512),                      -- RSA 加密存储
    email_enc       VARCHAR(512),                      -- RSA 加密存储
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
    UNIQUE KEY uk_user_role (user_id, role_id)
);
```

### 6.6 角色权限关联 `role_permission`

```sql
CREATE TABLE role_permission (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id         BIGINT NOT NULL,
    permission_id   BIGINT NOT NULL,
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
    INDEX idx_token_user (user_id),
    INDEX idx_token_expires (expires_at)
);
```

### 6.8 ER 关系

```
tenant (1) ──→ (N) user
user (M) ←──→ (N) role (M) ←──→ (N) permission
role.tenant_id → tenant.id（租户隔离）
permission 全局共享（所有租户共用权限树）
```

---

## 7. API 设计

### 7.1 认证接口

```
GET    /api/auth/public-key         获取 RSA 公钥
POST   /api/auth/login              登录
POST   /api/auth/refresh            刷新 Token
DELETE /api/auth/logout              登出
```

**登录请求：**

```json
{
  "username": "admin",
  "encPassword": "RSA加密后的密码(Base64)",
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

refreshToken 通过 `Set-Cookie: refreshToken=xxx; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=604800` 下发。

### 7.2 租户管理（超级管理员）

```
POST   /api/tenants              创建租户
PUT    /api/tenants/{id}         更新租户
DELETE /api/tenants/{id}         删除租户
GET    /api/tenants              租户列表
GET    /api/tenants/{id}         租户详情
```

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
- 超级管理员操作（`tenant_id = 0`）
- 登录认证（尚未确定租户）
- 权限表查询（`permission` 全局共享）

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
  encTimestamp: encryptSensitive(String(Date.now()))
}
```

---

## 10. 安全措施总览

| 层面 | 措施 | 说明 |
|------|------|------|
| **密码存储** | SHA-256(password + salt) → BCrypt | 双重哈希，应用层 salt + BCrypt 内置 salt |
| **Token 存储** | SHA-256(refreshToken + 固定盐值) | refreshToken 明文不落库 |
| **敏感传输** | RSA 2048-bit 非对称加密 | 密码、手机号、邮箱、Webhook URL |
| **防重放** | RSA 加密时间戳，后端校验 ±5min | 防止截获请求重放 |
| **Token 刷新** | refreshToken 一次性使用 | 刷新后旧 Token 立即失效 |
| **Token 黑名单** | accessToken 加入 Redis 黑名单 | 登出后 Token 立即失效 |
| **登录保护** | 连续失败 5 次锁定 15 分钟 | 防暴力破解 |
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

Redis 为可选组件，小规模部署可用 Caffeine 本地缓存替代。
