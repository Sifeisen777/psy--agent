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
| 对话记忆 | 按会话注入最近 N 条历史，多轮上下文连续 |
| 情绪与风险评估 | 模型输出结构化结果（情绪 / 强度 / 风险等级），写入消息记录 |
| 知识库检索 | 心理科普内容切片入库，回答前检索并在回复中标注引用来源 |
| 对话编排 | 分诊后按「知识问答 / 情绪支持 / 危机干预 / 简短寒暄」切换策略，危机场景标记人工复核 |
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
  编排层决定策略、门面只负责传输，两者可以独立演进。编排未引入工作流引擎：
  固定业务流程下，它带来的抽象成本高于收益。
- **缺少外部依赖仍可启动**：未配置模型密钥时装配 Mock 实现，避免新环境首次启动即失败。
- **主键精度**：采用雪花 ID，接口层统一序列化为字符串，规避前端 `Number` 精度丢失。
- **模型输出按不可信内容处理**：Markdown 先渲染再经 DOMPurify 净化后才插入页面，
  禁止直接 `v-html`；用户输入始终按纯文本展示，不参与解析。
- **消息只追加**：`t_message` 不做逻辑删除，保留完整审计链路；用户与会话采用逻辑删除。
- **限流前置**：限流判断先于模型调用，超额请求直接返回，不产生费用与延迟。
- **记忆以数据库为准**：上下文按会话从消息表读取，而非进程内缓存，重启与多实例部署下上下文保持连续；
  窗口大小可配置，超出后仅保留最近若干条。

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
| `done` | `{costMs, length, emotion, citations, route, steps, hitl}` | 生成结束，附带编排轨迹 |
| `error` | 错误提示 | 生成失败 |

`emotion` / `citations` / `route` / `steps` 的含义见下文「对话编排」与「知识库与检索」。

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/conversation/list` | 当前用户的会话列表 |
| GET | `/api/conversation/{id}/messages` | 指定会话的消息记录 |
| DELETE | `/api/conversation/{id}` | 删除会话（逻辑删除，消息记录保留） |

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
| `t_message` | 消息，只追加；`risk_level` 记录该条用户消息的风险等级 |
| `t_knowledge_doc` | 知识库文档，保留原文用于溯源 |
| `t_knowledge_chunk` | 知识切片，`content` 建有 ngram 全文索引用于检索 |
| `flyway_schema_history` | 迁移记录，由 Flyway 维护 |

## 对话编排

每轮对话先分诊，再按路由执行不同节点，最后把各节点产出汇总成系统提示交给模型。

```
分诊 ──┬─→ 知识检索 ──→ 生成
       └─→ 危机干预 ──→ 生成
```

### 路由与策略

| 路由 | 判定依据 | 策略差异 |
|---|---|---|
| `CRISIS` | 命中自伤/自杀类表述 | 跳过检索；强制给出求助热线；标记人工复核 |
| `KNOWLEDGE` | 命中领域词且含疑问标记 | 优先引用资料，方法要具体到当天可执行 |
| `EMOTION` | 领域内但无提问意图 | 先共情再建议，最多两条，不追问细节 |
| `SMALLTALK` | 短句且无领域词 | 一两句回应，自然引导，跳过检索 |

模型是同一个，差异全部体现在提示词约束上。分诊用规则而非模型分类，原因有三：
分类若走模型要再加 300ms 且直接叠在首字延迟上；规则命中哪条可复盘；
风险判定不能依赖概率——规则命中即拦截，比模型"综合判断"更可靠。

### 节点与执行轨迹

节点实现 `AgentNode` 接口，自行声明本轮是否参与，顺序由 `@Order` 决定。
编排器不含业务规则：新增节点只需加一个 Bean。

`done` 帧的 `steps` 返回本轮实际执行轨迹：

```json
[{"node":"分诊","detail":"知识问答","costMs":0},
 {"node":"知识检索","detail":"3 条：入睡困难与睡眠维持","costMs":7}]
```

`hitl = true` 表示已标记人工复核，前端会同时展示危机干预提示。

## 知识库与检索

启动时扫描 `classpath:knowledge/*.md`，按标题判重后切片入库（幂等，重复启动不产生副本）。

### 切片策略

按**空行分段**，段落累计超过 320 字再切；超长段落先按句号拆句再聚合。
不按固定字数硬切的原因：一句完整论证被截断后，检索命中拼进提示词也是残缺语义。

### 检索方式

采用 MySQL ngram 全文索引（中文按 2 字切分）做稀疏检索，相关度由倒排表计算（类 TF-IDF）。
与向量检索同属 RAG 的检索层，差别在相似度度量：

| | 稀疏检索（当前） | 向量检索 |
|---|---|---|
| 依赖 | 字面重叠 | 语义距离 |
| 基础设施 | 无额外组件 | 需 embedding 服务 + 向量库 |
| 短板 | 同义表达匹配不上 | 召回更宽，但需调阈值与重排 |

补偿手段：查询扩展（口语映射到书面语），领域词门槛，双阈值过滤。

### 引用链路

1. 检索命中片段注入系统提示词，带编号 `[1][2]`，并要求模型标注引用
2. `done` 帧返回 `citations: [{index, title, docId}]`，前端按文档合并展示
3. 用户消息原样落库，检索内容不写入对话历史

### 已知局限

- **语义鸿沟**：`睡不着` 与 `失眠` 靠词表桥接，`我晚上脑子停不下来` 这类表达检索不到，只能依赖模型常识
- **虚词干扰**：中文无分词器，ngram 会让「今天天气怎么样」这类无关查询也拿到分数，目前靠领域词门槛拦截
- 根治需要 embedding 服务（DeepSeek 未提供 embeddings 接口），接入后可在 `KnowledgeRetriever` 后追加向量召回并做融合排序

## 情绪与风险评估

每轮对话结束后，对用户输入做一次独立的结构化评估，结果通过 `done` 帧返回并回填到消息记录。

```json
{
  "emotion": "焦虑",
  "intensity": 65,
  "riskLevel": "LOW",
  "reason": "备考压力大、失眠、注意力不集中，但无自伤表述",
  "suggestion": "睡前固定时间做10分钟深呼吸放松，白天用番茄钟分段学习。",
  "source": "MODEL"
}
```

| 字段 | 说明 |
|---|---|
| `emotion` | 主要情绪，两个字概括 |
| `intensity` | 强度 0–100 |
| `riskLevel` | `NONE` / `LOW` / `MEDIUM` / `HIGH` |
| `reason` | 判定依据，摘引用户原话 |
| `suggestion` | 一句可执行建议 |
| `source` | `MODEL` 模型判定 / `RULE` 规则兜底 / `MOCK` 降级模式 |

设计约束：

- **评估失败不影响对话**：模型调用异常时降级为关键词规则判定，返回 `source = RULE`，主回复不受影响。
- **风险等级写入用户消息**：`risk_level` 描述的是用户状态，干预检索按
  `role = 'user' AND risk_level IN ('MEDIUM','HIGH')` 定位。
- **提示词与对话人设隔离**：评估要求零温度、限定输出长度，不复用聊天提示词，避免影响回复语气。
- **跳过评估**：`psy.ai.emotion-enabled=false` 时不产生额外调用，`done` 帧不含 `emotion`。

## 配置项

| 配置 | 说明 | 默认值 |
|---|---|---|
| `psy.ai.api-key` | 模型密钥，为空时启用 Mock | 空 |
| `psy.ai.base-url` | OpenAI 兼容端点 | `https://api.deepseek.com` |
| `psy.ai.model` | 模型名称 | `deepseek-chat` |
| `psy.ai.memory-max-messages` | 注入上下文的历史消息上限 | `20` |
| `psy.ai.emotion-enabled` | 是否启用情绪与风险评估 | `true` |
| `psy.knowledge.enabled` | 是否启用知识库检索 | `true` |
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
- [x] 多轮对话记忆
- [x] 结构化情绪与风险输出
- [x] 消息渲染：Markdown 与富文本安全处理
- [x] 知识库检索与引用溯源
- [x] 多智能体编排（分诊 / 检索 / 危机干预）
- [ ] 工具调用与外部服务集成
- [ ] 可观测性与效果评测

## License

[MIT](LICENSE)
