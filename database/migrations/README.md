# Database Migrations

本目录预留给 MySQL 初始化和迁移脚本。

建议命名：

```text
V20260527_001__init_schema.sql
V20260527_002__seed_permissions.sql
```

脚本变更必须同步更新数据设计文档和测试用例。

## 当前迁移

| 脚本 | 内容 |
|------|------|
| `V20260527_001__init_schema.sql` | 初始化租户、用户、角色权限、token、审计、设备目录、规则告警和仪表盘元数据表。 |
