# Frontend

前端应用基于 `docs/development/06-前端开发说明书.md` 实现，提供登录、实时监控、设备目录、告警、规则、RBAC、租户和低代码仪表盘页面。

目标技术栈：

- Vue
- Vite
- TypeScript
- Pinia
- ECharts

入口结构：

```text
frontend/
├── package.json
├── Dockerfile
├── nginx.conf
├── public/
└── src/
```

本地开发：

```powershell
npm install
npm run dev
npm run build
```
