# psy-agent · 心理服务智能体中台

> 面向校园 / 企业的心理支持智能体：从「分诊」到「干预」再到「危机处置」的完整闭环。
> 当前进度：**阶段 1 已完成**（骨架可跑 + 单模型对话闭环 + 无 Key 降级）。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 17 | 不用 21/25：Spring Boot 3.5 官方支持到 24，17 是最稳的 LTS |
| 框架 | Spring Boot 3.5.16 | 父 POM 统一管理版本 |
| AI | Spring AI 1.1.8（OpenAI 兼容） | DeepSeek / 通义 / 智谱 / vLLM 均可，换 base-url 即可 |
| Agent 编排 | Spring AI Alibaba 1.1.2.3（阶段 6） | Supervisor / Sequential / Routing / Loop |
| 数据 | MySQL 8 + Redis（阶段 2） | 业务数据 + 会话记忆 |
| 向量库 | Milvus（阶段 5） | 知识库检索 |
| 前端 | Vue3 + Vite（阶段 3 起） | 与后端解耦 |

## 模块结构

```
psy-agent/
├── backend/                    后端（Maven 多模块）
│   ├── pom.xml                 父 POM：版本仲裁、模块聚合
│   ├── psy-common/             通用契约：R 统一响应、ResultCode、BizException
│   ├── psy-infra-ai/           模型接入：AiChatFacade 门面、ChatClient 装配、Mock 降级、探活
│   ├── psy-infra-store/        存储：实体、Mapper、Flyway 迁移、Redis 限流
│   ├── psy-bootstrap/          主服务：启动类、Controller、Service、全局异常、配置
│   ├── docs/                   项目文档（接口、协作规范）
│   └── scripts/                后端启动脚本
└── frontend/                   前端（Vue3 + Vite）
    ├── src/                    源码
    ├── 启动前端.cmd             双击启动（自动装依赖 + 探测后端端口 + 开浏览器）
    └── 关闭前端.cmd             双击关闭（释放 5173 端口）
```

**为什么拆模块**：编译期强制隔离。`psy-common` 不允许依赖 Spring Web，
`psy-infra-ai` 不允许依赖 Controller——一旦反向依赖，编译就红。
后面 `mcp-server`（独立进程）也能直接复用 `psy-common`，不用拷代码。

## 快速启动

### 后端

```bash
cd backend

# 1. 配置（可选，不配也能起来，走 Mock）
cd psy-bootstrap/src/main/resources
copy application-local.yml.example application-local.yml
# 编辑 application-local.yml，填入 DEEPSEEK_API_KEY 与数据库密码

# 2. 构建（Windows 下确保用 JDK 17）
mvn -DskipTests package

# 3. 运行
java -jar psy-bootstrap/target/psy-bootstrap-1.0.0-SNAPSHOT.jar

# 端口被占用时
java -jar psy-bootstrap/target/psy-bootstrap-1.0.0-SNAPSHOT.jar --server.port=18087
```

也可以用 IDEA 直接运行 `PsyAgentApplication`（Project SDK 选 17）。

### 前端

双击 `frontend/启动前端.cmd`，会依次完成：安装依赖（国内镜像）→ 启动 Vite → 打开浏览器。
脚本会自动探测后端端口（8080 / 18080 / 18087…），并校验响应确实是 psy-agent，
所以后端换端口不用改配置；确实需要指定时设 `VITE_API_TARGET` 即可。

停止：双击 `frontend/关闭前端.cmd`，或直接关掉那个命令行窗口。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/system/health` | 健康检查，含 `llmAvailable` / `model` |
| POST | `/api/chat` | 单轮对话 `{userId, message}` |
| GET | `/actuator/health` | Spring Boot 原生健康端点 |

```bash
curl http://localhost:8080/api/system/health
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","message":"最近总是失眠，压力很大"}'
```

## 版本矩阵（踩过的坑）

| 组件 | 用的版本 | 坑 |
|---|---|---|
| Spring Boot | 3.5.16 | 3.5.x 支持 Java 17–24，**别用 JDK 25 跑**（CGLIB/字节码会炸） |
| Spring AI | 1.1.8 | 1.0 起 starter 改名为 `spring-ai-starter-model-openai`，老名字已废弃 |
| Spring AI Alibaba | 1.1.2.3 | 元数据里的 `1.1.2.4` 实际发布名是 `1.1.2.4-security-fix`，照抄会 404 |
| MyBatis-Plus | 3.5.18（阶段 2） | 必须用 `mybatis-plus-spring-boot3-starter`，用错 starter 不生效 |

## 许可证

MIT。心理健康类应用请在 README 与产品页明确「不替代专业诊断」。
