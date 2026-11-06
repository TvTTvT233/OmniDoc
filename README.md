# OmniDoc

OmniDoc 是一个面向团队的 AI 文档处理与协作平台。项目采用 Spring Cloud 微服务架构与 Vue 3 前端，覆盖文档管理、对象存储、智能分析、RAG 知识库、AI Agent、演示文稿生成和实时协作等场景。

## 当前可验收基线

当前集成候选包含文档—索引—引用一致性、Review M0 手工材料审查、统一任务运行协议，以及 Agent 持久化恢复与副作用控制。已推送至 `integration/core-baseline`，尚未合入 main。

| 能力 | 当前范围 |
| --- | --- |
| 文档与 RAG 一致性 | 修改后停用旧索引，历史引用绑定原版本并重新检查访问权限 |
| 材料审查 | 手工确认规则、齐全性/命名审查、证据报告、整改复审与下载；官方规则自动提取未接通 |
| Agent 执行 | JDBC 任务/操作账本、租约、检查点、重复请求合并、审批；写结果未知时停止自动重放 |
| 验证边界 | 本地无外部模型验收；不代表真实模型质量、完整奖学金 V1、跨服务 exactly-once 或生产容量已验证 |

首次体验请先读 [基线运行与迁移说明](docs/release/BASELINE-RUNBOOK.md)。最新证据索引见 [恢复控制验收报告](docs/task-runtime/RESULT-2026-11-02.md) 和 [项目进展](docs/PROGRESS.md)。报告引用的 `.local` 私有日志、截图和运行配置不随仓库发布。

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

### Windows 本地一键启动

以下是旧通用开发入口，不会自动复现最近的 Review/恢复控制验收配置；使用集成基线时优先参考上方运行说明。不要在已有验收服务占用端口时再运行另一套启动脚本。

需要先安装 JDK 17、Node.js LTS 和 Docker Desktop。Docker Desktop 使用 WSL2
后端，首次启用 WSL 或虚拟机平台后需要重启 Windows。

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

填写 `.env` 中的本地密码。外部模型是可选配置，按所选供应商的接口、模型权限和账户要求配置 `DASHSCOPE_API_KEY` 或 `OPENAI_API_KEY`；不要将真实值提交到 Git。无外部模型验收请使用运行说明中的禁用配置。

在项目根目录运行：

```powershell
.\scripts\start-local.cmd
```

该脚本会自动完成：

1. 启动 MySQL、Redis、RabbitMQ 和 MinIO；
2. 初始化本地数据库表；
3. 使用 JDK 17 构建并启动五个后端服务；
4. 安装前端依赖并启动 Vite。

查看状态或停止：

```powershell
.\scripts\status-local.cmd
.\scripts\stop-local.cmd
```

首次构建耗时较长。后续可跳过后端重新构建：

```powershell
.\scripts\start-local.cmd -SkipBuild
```

只运行前端：

```powershell
cd web
npm ci
npm run dev
```

前端默认通过 `VITE_API_TARGET=http://localhost:8080` 访问网关。

### 手动构建

Windows：

```powershell
.\mvnw.cmd -s .\.mvn\settings-local.xml -DskipTests package
```

Linux / macOS：

```bash
./mvnw -DskipTests package
```

生产或集群部署请参考 [DEPLOY.md](DEPLOY.md)。本地开发不需要 Nacos。

## 常用入口

| 服务 | 地址 |
| --- | --- |
| Web 前端 | `http://localhost:5173` |
| API 网关 | `http://localhost:8080` |
| RabbitMQ 管理台 | `http://localhost:15672` |
| MinIO 控制台 | `http://localhost:9001` |

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
