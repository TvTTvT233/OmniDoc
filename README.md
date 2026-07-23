# OmniDoc

OmniDoc 是一个面向团队的 AI 文档处理与协作平台。项目采用 Spring Cloud 微服务架构与 Vue 3 前端，覆盖文档管理、对象存储、智能分析、RAG 知识库、AI Agent、演示文稿生成和实时协作等场景。

## 核心能力

- 文档创建、解析、搜索、版本管理与恢复
- 文件上传、下载、回收站和 MinIO 对象存储
- 文本摘要、纠错、关键词提取与多模型调用
- RAG 知识库、任务型 AI Agent 与执行审批
- HTML/PPT 生成及多套演示模板
- 用户注册登录、JWT 鉴权与网关统一路由
- WebSocket/STOMP 实时进度与协作
- Prometheus、Grafana 和 AIOps 监控能力

## 项目结构

| 目录 | 说明 | 默认端口 |
| --- | --- | --- |
| `gateway-service` | API 网关、鉴权、路由与限流 | 8080 |
| `user-service` | 用户、登录与权限 | 8081 |
| `file-service` | 文件与对象存储 | 8082 |
| `ai-service` | AI、RAG、Agent、异步任务与 PPT | 8083 |
| `document-service` | 文档、版本与协作 | 8084 |
| `common` | 公共模型、安全与基础组件 | — |
| `web` | Vue 3 前端 | 5173 |

基础设施包括 MySQL、Redis、RabbitMQ、MinIO、Nacos、Prometheus 与 Grafana。

## 技术栈

- Java 17、Spring Boot 3.2、Spring Cloud、Spring Cloud Alibaba
- Spring AI、MyBatis-Plus、JWT、OpenFeign
- MySQL、Redis、RabbitMQ、MinIO、Nacos
- Vue 3、Vite、Element Plus、Axios、STOMP
- Docker、Docker Compose / Swarm、Prometheus、Grafana

## 快速开始

### 1. 准备配置

复制环境变量模板，并按实际环境填写密码、节点地址和模型密钥：

```bash
cp .env.example .env
```

不要提交 `.env`。生产环境务必设置独立且足够长的 `OMNIDOC_JWT_SECRET`，并使用安全的密钥管理方案。

### 2. 构建后端

Windows：

```powershell
.\mvnw.cmd clean package
```

Linux / macOS：

```bash
./mvnw clean package
```

如只需快速验证编译，可追加 `-DskipTests`。

### 3. 启动前端

```bash
cd web
npm ci
npm run dev
```

前端默认通过 `VITE_API_TARGET=http://localhost:8080` 访问网关。

### 4. 启动基础设施

Compose 文件使用外部网络，首次运行前先创建：

```bash
docker network create omnidoc-network
docker compose --env-file .env -f docker-compose-infra.yml up -d
```

随后可通过 IDE 分别启动五个 Spring Boot 服务，或参考 [DEPLOY.md](DEPLOY.md) 使用 Docker Swarm 部署完整环境。

## 常用入口

| 服务 | 地址 |
| --- | --- |
| Web 前端 | `http://localhost:5173` |
| API 网关 | `http://localhost:8080` |
| Nacos | `http://localhost:8848/nacos` |
| RabbitMQ 管理台 | `http://localhost:15672` |
| MinIO 控制台 | `http://localhost:9001` |
| Grafana | `http://localhost:3000` |

## 配置与安全

- 所有密码、Token 和模型 API Key 均应通过环境变量提供。
- `.env`、构建产物、依赖目录和日志已加入 `.gitignore`。
- 上线前请启用 HTTPS、替换默认账号、限制管理端口暴露，并使用 Docker Secrets 或云端密钥服务。
- 本项目仍保留现有 Java 命名空间 `com.javaee`，本次重命名只调整品牌和发布包元数据，避免造成不必要的兼容性破坏。

## 更多文档

- [安装与使用](INSTALLATION_AND_USAGE.md)
- [部署指南](DEPLOY.md)
- [AI 服务说明](ai-service/README.md)
- [RabbitMQ 实现说明](RABBITMQ_IMPLEMENTATION.md)

