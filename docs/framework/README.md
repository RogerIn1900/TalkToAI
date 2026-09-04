# 框架文档索引

> 本目录收纳 TalkToAI 项目的 UI / 架构蓝图，所有章节都从「参考外部项目」映射到「Kuikly DSL 实现」。
> 阅读顺序：先看 `librechat-kuikly-mapping.md`，后续补充的文档以同一套约定编写。

## 文档列表

| 文档 | 内容 | 状态 |
|---|---|---|
| [`librechat-kuikly-mapping.md`](./librechat-kuikly-mapping.md) | 将 [garfiec/Librechat-Mobile](https://github.com/garfiec/Librechat-Mobile) 的 Compose UI 架构逐项翻译到 Kuikly：模块结构、Compose → Kuikly 翻译表、分屏蓝图、主题、性能规则、实现路线图 | ✅ v0.1 |
| [`dsh-architecture.md`](./dsh-architecture.md) | 参考 [AlephAITech/WorkBuddyGuide](https://github.com/AlephAITech/WorkBuddyGuide) Multi-Agent 系统设计，建立 TalkToAI feature-owned 模块架构：6 个 feature 角色契约 + 目标目录结构 + 迁移计划 + 冲突预判 | ✅ v1.0 |
| [`ui-architecture.md`](./ui-architecture.md) | 参考豆包 4-Tab + `PageList` 模式，设计 TalkToAI 顶层 UI 架构：AppShell 根容器 + BottomTabBar + Drawer 二级导航；含完整 DSL 实现、6 阶段迁移计划、风险矩阵 | 🆕 v1.0 设计稿 |

## 命名约定

- **前缀**：`./<主题>-kuikly-mapping.md`，例如 `markdown-renderer-kuikly-mapping.md`、`theme-system-kuikly-mapping.md`
- **新增时**同时更新本索引的「文档列表」
- **版本号**：v0.x 初稿，v1.x 稳定稿，每次 PR 末尾追加 row 到文档末尾「版本记录」表

## 推荐写作结构

每份映射文档都按以下骨架展开（与 `librechat-kuikly-mapping.md` 保持一致）：

1. **§0 阅读顺序建议** —— 一句话点出重点章节
2. **§1 整体架构对照** —— 表格对比参考项目 vs 当前项目
3. **§2 通用翻译表** —— 概念 → Kuikly DSL 等价
4. **§3 分屏蓝图 / 分类详述** —— 视觉骨架 + 数据模型 + 关键交互 + 实现优先级
5. **§4 该主题特有的子系统**（如主题、状态、错误处理）
6. **§5 性能 / 稳定性 / 边界规则**
7. **§6 / §7 测试 / 风险 / 路线**
8. **§8 版本记录**

## 关联文件

- 项目上层说明：`/Users/jerry/JustDoIt/OHHHHH/MEMORY.md`
- 子任务追踪：`/Users/jerry/JustDoIt/OHHHHH/TalkToAI/docs/` 下按 Subagent 拆分的小文档（建议路径）
- 代码实现位置：`TalkToAI/shared/src/commonMain/kotlin/com/example/talktoai/base/`
