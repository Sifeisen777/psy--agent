# Git 工作流：每阶段怎么提交

> 目的：让 `git log` 本身成为简历的一部分。面试官会翻你的提交历史——
> 一次 5 万行的 `initial commit` 等于告诉他"这是抄的"；
> 而十几个语义清晰、能看出演进过程的提交，等于展示你的工程习惯。

---

## 一、已经建好的仓库状态

```bash
cd psy-agent
git log --oneline
# 1483244 feat(chat): 打通单模型对话闭环，支持无 Key 降级
# a2e2fb3 chore(project): 初始化 psy-agent 多模块工程与版本矩阵

git tag -n
# stage-1-done    阶段 1 完成：多模块骨架 + 单模型对话闭环 + Mock 降级
```

- 分支：`main`
- 提交粒度：**按逻辑单元拆**，不是按文件拆。骨架一个提交、业务一个提交。
- 里程碑：每阶段结束打一个 annotated tag，简历里可以写"按 8 个里程碑迭代交付"。

---

## 二、Commit Message 规范

采用 Conventional Commits，**标题英文前缀 + 中文描述**（国内团队最常见的写法）：

```
<type>(<scope>): <一句话说清做了什么>

<为什么这么做 / 关键取舍，一行一条>
```

| type | 用在哪 |
|---|---|
| `feat` | 新功能（新增接口、新增 Agent） |
| `fix` | 修 bug（注意：要写清"修了什么现象"，不是"改了点东西"） |
| `refactor` | 不改行为的重构（换实现、抽接口） |
| `perf` | 性能优化（带数字，如"首 token 延迟 1.2s → 380ms"） |
| `test` | 只加/改测试 |
| `docs` | 只改文档 |
| `chore` | 构建、依赖、配置、脚手架 |
| `build` / `ci` | 打包脚本、流水线 |

**scope 用模块名**：`chat` / `db` / `agent` / `mcp` / `rag` / `obs`。

### 好提交 vs 坏提交

```bash
# ❌ 无法复盘
git commit -m "update"
git commit -m "修改代码"
git commit -m "阶段2完成"

# ✅ 能看出决策
git commit -m "feat(db): 新增会话与消息表，按用户维度建联合索引" \
           -m "- t_message 加 (conversation_id, created_at) 联合索引，覆盖历史分页查询
- 软删除字段 deleted 默认 0，避免物理删除导致审计断链"
```

判断标准：**三个月后你只看 commit message，能不能想起当时为什么这么改。**

---

## 三、每阶段的提交清单

| 阶段 | 建议分支（可选） | 提交数 | 提交内容 | tag |
|---|---|---|---|---|
| 1 骨架 | — | 2 ✅ | 工程初始化 / 对话闭环 | `stage-1-done` ✅ |
| 2 数据层 | `feat/stage-2-persistence` | 3–4 | ①建表+Flyway ②MyBatis-Plus 接入 ③Redis 限流 | `stage-2-done` |
| 3 SSE + 前端 | `feat/stage-3-stream` | 3 | ①SSE 端点 ②会话管理 ③Vue 对话页 | `stage-3-done` |
| 4 AI 工程化 | `feat/stage-4-ai` | 3 | ①结构化输出 ②Advisor ③对话记忆 | `stage-4-done` |
| 5 RAG | `feat/stage-5-rag` | 3 | ①文档入库 ②向量检索 ③引用溯源 | `stage-5-done` |
| 6 Agent 编排 | `feat/stage-6-agent` | 4–5 | ①引入 Agent Framework ②分诊 Agent ③情绪评估 Agent ④危机 Agent+HITL | `stage-6-done` |
| 7 MCP 工具 | `feat/stage-7-mcp` | 3 | ①MCP Server 骨架 ②量表工具 ③告警邮件工具 | `stage-7-done` |
| 8 可观测评测 | `feat/stage-8-obs` | 3–4 | ①OTel 埋点 ②评测集 ③压测脚本 ④README 架构图 | `stage-8-done` |

**一个阶段内部，按"能独立编译通过"的最小单元提交。** 比如阶段 6：
先提交"引入框架 + 空编排能跑通"，再提交"分诊 Agent"，再提交"情绪 Agent"。
每一步 `mvn package` 都是绿的，出问题能精准回滚。

---

## 四、在 IDEA 里怎么操作

### 1. 先做三个必改设置（不改必踩坑）

| 设置 | 位置 | 改成 |
|---|---|---|
| Project SDK | `File → Project Structure → Project` | **17**（你系统 JAVA_HOME 是 25，IDEA 默认会选 25） |
| Maven Runner JRE | `Settings → Build Tools → Maven → Runner → JRE` | **17** |
| 文件编码 | `Settings → Editor → File Encodings` | 全部 **UTF-8** |

改完 `Build → Rebuild Project`，确认无报错。

### 2. 第一次打开会弹两个窗

- **Trust Project** → 选 *Trust Project*（否则 Maven 不自动导入）。
- 右下角提示 *Maven projects need to be imported* → 点 *Import*（或右上角 M 图标刷新）。

### 3. 日常提交流程（快捷键）

| 操作 | 快捷键 | 说明 |
|---|---|---|
| 打开提交面板 | `Ctrl + K` | 左侧列出所有改动文件 |
| 查看单文件 diff | 在提交面板点文件 | **提交前必看 diff**，防止误提交密钥、target、调试代码 |
| 取消勾选 | 文件前复选框 | 不想提交的文件（如临时调试类）取消勾选 |
| 写提交信息 | 上方输入框 | 标题一行，空一行写 body |
| 提交 | `Commit` 按钮 | 只提交不推送 |
| 提交并推送 | `Commit and Push` | 远程仓库建好后用这个 |
| 提交历史 | `Alt + 9` 打开 Git 工具窗 → Log | 图形化看分支、tag、每次改动 |
| 看某行谁改的 | 编辑器左侧槽右键 → *Annotate with Git Blame* | 面试演示时很好用 |

**习惯**：每完成一个可编译的小功能就 `Ctrl+K` 提交一次，不要攒一天。
攒着提交的后果是——改崩了想回退，发现只能退回一天前。

### 4. 打 tag（阶段结束）

IDEA 里：`Git 工具窗 → Log → 右键目标提交 → New Tag`，填 `stage-2-done`。
命令行等价：

```bash
git tag -a stage-2-done -m "阶段 2 完成：MySQL + MyBatis-Plus + Flyway + Redis 限流"
git push origin stage-2-done     # 有远程仓库时
```

### 5. 分支（可选，想模拟团队协作时用）

```bash
git switch -c feat/stage-2-persistence
# ... 开发、提交 ...
git switch main
git merge --no-ff feat/stage-2-persistence -m "merge: 阶段 2 数据层"
git tag -a stage-2-done -m "..."
```

`--no-ff` 会保留一条合并记录，Log 图能看出"阶段"这个层级。
**个人项目其实 main 直推 + tag 就够了**，不用为了形式造 PR——面试官一问"你们 code review 怎么做的"就露馅。

---

## 五、四个必须避开的坑

| 坑 | 现象 | 防法 |
|---|---|---|
| **误提交密钥** | `application-local.yml` 里是真 Key | 已被 `.gitignore` 忽略；提交前看 diff。万一提交了：立刻去平台吊销 Key，再 `git filter-repo` 清历史——**改历史不等于密钥没泄露** |
| **CRLF 污染** | 全文件显示改动，实际只改了空格 | 已设 `core.autocrlf=false`；团队协作时加 `.gitattributes` 统一 `* text=auto eol=lf` |
| **提交 target/** | 仓库迅速膨胀到几百 MB | 已被 `.gitignore` 忽略；`.gitignore` 要在**第一次提交前**就位 |
| **中文乱码** | `git status` 显示 `\344\275\240` | 已设 `core.quotepath=false` |

---

## 六、每次提交前的 30 秒自检

```
□ 能编译通过吗？            mvn -q -DskipTests package
□ 有没有调试残留？          System.out、TODO、写死的测试 Key
□ diff 里有没有本地配置？    application-local.yml、.env、日志
□ 标题能让人看懂做了什么？   不是 "update" / "fix bug"
□ body 写了"为什么"？       不是重复标题
```

这五条养成习惯，你的 `git log` 放到简历附件里都是加分项。
