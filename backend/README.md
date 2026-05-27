# Backend

后端服务预留目录。

目标技术栈：

- JDK 25
- Spring Boot 4
- MySQL
- Redis
- InfluxDB Client
- MQTT Client

建议入口结构：

```text
backend/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/vionix/backend/
    │   └── resources/
    └── test/java/com/vionix/backend/
```

实现前以 `docs/development/` 中的需求、接口、数据和测试文档为基线。
