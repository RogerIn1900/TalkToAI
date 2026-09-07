# TalkToAI 工程协作规范

## 产品边界

- 第一版仅支持 Android、A 股、匿名用户和只读咨询；不得加入登录注册、港美股、下单或交易能力。
- Android 使用 Kotlin、Kuikly 和 MVVM 单向数据流；CloudBase 测试后端可使用 TypeScript/Node.js。
- 所有远程数据必须走 HTTPS。AI 密钥、行情数据密钥和其他服务端凭证不得进入 APK、仓库、日志或反馈包。
- 仅允许部署到 CloudBase 测试环境 `stockai-test-d6gd0ho1z97f0bbde`；不得部署生产、发布商店或创建付费资源。

## 工程规则

- UI 只渲染 `UiState` 并发送 `UiAction`；ViewModel 负责状态转换，Repository 负责业务编排，Provider/Gateway 隔离外部服务。
- 行情统一通过 `MarketDataProvider`，每份响应携带数据源、市场时间、抓取时间和新鲜度状态。
- 生产代码变更必须同步补充测试。网络测试使用固定夹具，不依赖真实行情、真实模型或系统当前时间。
- 错误、超时、取消、重试、离线、持久化恢复和资源释放均须显式处理。
- 日志默认保留 10 天、每天最多 5000 条；禁止记录密钥、完整对话正文和附件正文。
- 每日 AI 请求上限为每匿名安装 500 次，按 `Asia/Shanghai` 自然日计算；服务端为权威计数。

## 交付验证

- 最低验证：领域单测、接口契约测试、Android 编译、debug APK、指定模拟器安装与关键链路截图/日志。
- 报告实际执行的命令、结果、未验证项和剩余风险；不得把 Mock 或 JVM 通过描述成线上链路已验证。
