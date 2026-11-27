# psy-agent

心理服务智能体平台。面向校园与企业场景，提供情绪识别、分诊与干预建议的对话能力，
支持流式输出、会话持久化与风险分级。

> **免责声明**：本项目为工程实践与技术演示，不构成医疗诊断或专业心理咨询的替代方案。
> 若出现自伤、自杀等高风险表述，请立即联系当地心理援助热线或紧急服务。

## 功能

| 能力 | 说明 |
|---|---|
| 流式对话 | SSE 逐字返回，首字延迟约 1 秒 |
| 会话管理 | 多会话隔离、历史消息持久化、标题自动生成 |
| 模型接入 | OpenAI 兼容协议，可对接 DeepSeek、通义、智谱或本地 vLLM |
| 无密钥降级 | 未配置模型密钥时以 Mock 模式运行，便于本地开发与 CI |
| 限流 | 基于 Redis 的会话级限流，拒绝请求不消耗模型调用 |
| 身份认证 | JWT 无状态鉴权，接口身份取自令牌而非请求参数 |
| 健康检查 | 存活探针、模型可用性探测（含缓存）、存储自检 |

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 | Java 17 |
| 应用框架 | Spring Boot 3.5.16 |
| 模型接入 | Spring AI 1.1.8（OpenAI 兼容） |
| 持久化 | MySQL 8.0 + MyBatis-Plus 3.5.18 + Flyway |
| 缓存 | Redis 8 |
| 前端 | Vue 3 + Vite 6 |
| 构建 | Maven 多模块 / npm |

## 架构

### 模块划分

| 模块 | 职责 |
|---|---|
| `psy-common` | 统一响应体、错误码、业务异常 |
| `psy-infra-ai` | 模型接入、调用门面、可用性探测、降级实现 |
| `psy-infra-store` | 实体与 Mapper、Flyway 迁移、Redis 限流 |
| `psy-bootstrap` | HTTP 入口、业务编排、全局异常、配置 |

依赖方向单向收敛：

```
psy-bootstrap → psy-infra-ai  → psy-common
             → psy-infra-store → psy-common
```

`psy-common` 不引入 Web 依赖，`psy-infra-*` 不引入 Controller，
通过编译期约束避免层级倒挂。

### 关键设计

- **模型访问收敛在门面之后**：上层依赖 `AiChatFacade` 而非 Spring AI 的 `ChatClient`，
  后续替换为多智能体编排时，业务侧无需改动。
- **缺少外部依赖仍可启动**：未配置模型密钥时装配 Mock 实现，避免新环境首次启动即失败。
- **主键精度**：采用雪花 ID，接口层统一序列化为字符串，规避前端 `Number` 精度丢失。
- **消息只追加**：`t_message` 不做逻辑删除，保留完整审计链路；用户与会话采用逻辑删除。
- **限流前置**：限流判断先于模型调用，超额请求直接返回，不产生费用与延迟。

## 目录结构

```
psy-agent/
├── backend/
│   ├── pom.xml                 父 POM：版本管理、模块聚合
│   ├── psy-common/             通用契约
│   ├── psy-infra-ai/           模型接入
│   ├── psy-infra-store/        持久化与缓存
│   ├── psy-bootstrap/          应用主模块
│   └── scripts/                启动脚本
└── frontend/
    ├── src/                    源码
    ├── 启动前端.cmd             Windows 启动脚本
    └── 关闭前端.cmd             Windows 停止脚本
```

## 快速开始

### 环境要求

- JDK 17
- Maven 3.9+
- MySQL 8.0
- Redis 6+
- Node.js 18+

### 后端

```bash
cd backend

# 生成本地配置（该文件不入版本库）
cp psy-bootstrap/src/main/resources/application-local.yml.example \
   psy-bootstrap/src/main/resources/application-local.yml
# 填写模型 API Key、数据库与 Redis 连接信息

# 构建
mvn -DskipTests package

# 启动
java -jar psy-bootstrap/target/psy-bootstrap-1.0.0-SNAPSHOT.jar
```

数据库表结构由 Flyway 在启动时自动迁移，无需手工执行 SQL。
默认端口 8080，可通过 `--server.port=xxxx` 覆盖。

也可在 IDE 中直接运行 `PsyAgentApplication`。

### 前端

```bash
cd frontend
npm install
npm run dev
```

Windows 环境下可直接运行 `启动前端.cmd` / `关闭前端.cmd`。
开发服务器会自动探测后端端口，也可通过环境变量 `VITE_API_TARGET` 显式指定。

## 接口

### 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 同步对话，单次返回 |
| POST | `/api/chat/stream` | 流式对话，SSE 输出并持久化 |

流式接口返回的事件序列：

| 事件 | 载荷 | 说明 |
|---|---|---|
| `meta` | `{conversationId, title}` | 首帧，返回会话标识 |
| `delta` | 文本片段 | 增量内容，可多次 |
| `done` | `{costMs, length}` | 生成结束 |
| `error` | 错误提示 | 生成失败 |

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/conversation/list` | 当前用户的会话列表 |
| GET | `/api/conversation/{id}/messages` | 指定会话的消息记录 |

### 账号

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册并返回令牌 |
| POST | `/api/auth/login` | 登录并返回令牌 |
| POST | `/api/auth/unregister` | 注销当前账号（逻辑删除并释放用户名） |

### 系统

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/system/ping` | 存活探针，无 IO |
| GET | `/api/system/health` | 健康检查，含模型可用性 |
| GET | `/api/system/db-check` | 存储连通性自检 |
| GET | `/actuator/health` | Spring Boot 原生端点 |

响应统一为 `{ code, message, data, ts }`，`code = 0` 表示成功。

### 认证

除探活、系统自检与登录注册接口外，其余接口需携带：

```
Authorization: Bearer <token>
```

令牌由 `/api/auth/login` 或 `/api/auth/register` 签发，默认有效期 2 小时，载荷包含用户标识与用户名。
未携带或令牌失效返回 HTTP 401（`code = 40100`），权限不足返回 HTTP 403（`code = 40300`）。

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456"}' | jq -r .data.token)

curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"最近压力比较大"}'
```

## 数据模型

| 表 | 说明 |
|---|---|
| `t_user` | 账号，密码以 BCrypt 存储；注销时逻辑删除并将用户名改写为 `name#id`，释放唯一键 |
| `t_conversation` | 会话，冗余维护消息条数与最后活跃时间，避免列表页聚合查询 |
| `t_message` | 消息，只追加；预留 `risk_level` 字段用于风险分级 |
| `flyway_schema_history` | 迁移记录，由 Flyway 维护 |

## 配置项

| 配置 | 说明 | 默认值 |
|---|---|---|
| `psy.ai.api-key` | 模型密钥，为空时启用 Mock | 空 |
| `psy.ai.base-url` | OpenAI 兼容端点 | `https://api.deepseek.com` |
| `psy.ai.model` | 模型名称 | `deepseek-chat` |
| `psy.rate-limit.chat-per-minute` | 单用户每分钟对话次数上限 | `10` |
| `psy.jwt.secret` | 令牌签名密钥（HS256，至少 32 字节），生产环境通过 `PSY_JWT_SECRET` 注入 | 开发默认值 |
| `psy.jwt.expiration` | 令牌有效期 | `2h` |
| `spring.datasource.*` | 数据库连接 | — |
| `spring.data.redis.*` | Redis 连接 | — |

密钥与连接信息仅写入 `application-local.yml`，该文件已在 `.gitignore` 中排除。

## 开发约定

- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)
- `main` 分支保持可构建、可启动
- 新增配置项需同步更新 `application-local.yml.example` 与本文件

## Roadmap

- [x] 多模块工程结构与统一响应契约
- [x] 模型接入层、无密钥降级、可用性探测
- [x] 数据持久化、版本化迁移、会话级限流
- [x] 流式对话、会话管理、Web 客户端
- [x] 身份认证与访问控制（JWT）
- [ ] 对话记忆与结构化情绪评估
- [ ] 知识库检索与引用溯源
- [ ] 多智能体编排与工具调用
- [ ] 可观测性与效果评测

## License

[MIT](LICENSE)
