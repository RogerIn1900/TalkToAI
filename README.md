# TalkToAI Android V1

TalkToAI 是一个仅提供 A 股信息参考的 Android 测试版应用。客户端使用 Kotlin、Kuikly 和 MVVM；后端使用 CloudBase HTTP 云函数与 TypeScript。应用不提供登录、下单、支付或收益承诺。

## 快速验证

```bash
cd backend/functions/talktoai-api
npm test
npm run build

cd ../../..
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --no-daemon
adb -s emulator-5554 install --no-streaming -r -t androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

测试环境接口：`https://stockai-test-d6gd0ho1z97f0bbde.service.tcloudbase.com/talktoai`。当前公开测试环境故意不包含任何密钥；未配置凭证时 AI 和附件上传会返回明确的 503，而不会回退成伪成功。

详细资料：

- [需求基线](docs/v1/requirements.md)
- [架构与安全边界](docs/v1/architecture.md)
- [HTTP 接口](docs/v1/api.md)
- [测试环境部署](docs/v1/deployment.md)
- [验证报告与剩余风险](docs/v1/test-report.md)
