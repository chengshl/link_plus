# Link Plus Trace · 固定签名与在线更新（2026-11-07）

基线：`experiment/link-plus-protocol-trace-20261106@3a0875c11e5fa2e7e425038ae15a3a17eec4ae7c`
实验：`experiment/link-plus-trace-fixed-update-20261107`（独立实验，不修改主线和已验证的 `.buick` 包）。

## 来源及功能
参考 CarDeck `Buick-E5` 最新软件更新模块与 [#17](https://github.com/chengshl/cardeck/issues/17)，保留方案 B 的 `updaterSchema=2` 安全规则，并适配只有一个自用 Trace 发布通道的场景：
- 「关于」增加软件更新入口，深色更新页面展示安装版本、当前证书是否固定、手动检查与“启动自动检查”开关；更新来源由内部固定，不允许误选 CarDeck E5/Test 主程序。
- 只读取公开 `chengshl/link_plus` Release 的本 Trace 分支签名预发布，检查 `artifactKind=experiment`、`updaterSchema=2`、固定 base/sourceBranch、applicationId、versionCode、完整 tag、APK+SHA256 路径及固定证书 fingerprint；只选更高版本。
- 检查下载走独立已验证的互联网 Network，不解绑 CarPlay 热点；自动检查默认关闭且只弹通知，不自动下载或安装。
- 手动下载先做 SHA256 与 APK 包名/版本/证书检查，Android 系统安装器再复核签名，同签名且更高版本才能覆盖升级。无外网、APK 格式错误、签名不匹配全部拒绝。
- 正式签名材料只在 GitHub Actions Secrets 或本机离线保管，不进入公开仓库。

## 一次性签名初始化（须由仓库管理员操作）

本任务建立了一套独立 PKCS12 固定签名证书，SHA256（冒号去除后）：
`3fc11b60da53fd634d668bde7f7e9e19f6ed042d59502de2797b949e53e482e3`
别名：`linkplustracefixed`，RSA-4096，签名有效期至 2054-03-24。

**不要上传 .p12、密码、base64 内容到 GitHub 源码、Issue、评论或公开 Artifact。**

GitHub 仓库 `chengshl/link_plus` → Settings → Secrets and variables → Actions → Repository secrets → New repository secret，创建：
1. `TRACE_SIGNING_KEYSTORE_B64`：私有 .p12 文件的单行 Base64
2. `TRACE_SIGNING_STORE_PASSWORD`：密钥库密码
3. `TRACE_SIGNING_KEY_ALIAS`：`linkplustracefixed`
4. `TRACE_SIGNING_KEY_PASSWORD`：密钥密码（PKCS12 与库密码相同）
5. `TRACE_SIGNING_PUBLISH_APPROVED`：管理员明确启用自动发布时设为 `YES`

前四项缺失或证书指纹不一致即 **STOP**，不允许回退为 debug 签名；第五项未设为 YES 时不会生成正式发布。凭据激活后，重跑实验分支签名工作流之前的失败 Run（或触发一次受审源码提交）才能生成第一个固定签名预发布；需要 GitHub Actions 的 `contents: write` 能力。

构建命令：`./gradlew -PtraceFixedSigning=true :app:testTraceUnitTest :app:assembleTrace`。Release 工作流将核对 `apksigner` 证书、packageName、versionCode、SHA256，生成不可变 tag `exp-link-plus-trace-v<versionCode>-<commit12>`，发布 `LinkPlus-Trace.apk`、`LinkPlus-Trace.apk.sha256` 和 schema-2 元数据。

## 首次迁移与回退
- 目前安装的 `.buick.trace` 是 GitHub Runner 临时 debug 签名，不能覆盖升级到本固定签名。**只有第一次**需先导出日志、记录 Gateway WSS 地址与令牌，再卸载旧 Trace 并从 GitHub 首次固定签名 Release 安装；不卸载、不中断已验证正常的 `.buick` 应用。不同签名不能继承旧 Trace 私有 Android Keystore 配置。
- 首个固定签名版安装后，未来的固定签名版用同包名与更高 versionCode 可覆盖升级并保留 Gateway 配置；如果失去密钥，不能继续无损升级。
- 初次签名 Release 的 CI 成功不代表手机自动更新与车机回归成功；先在停车条件下验证手动检查、无候选、断网、错 SHA/包名/签名、覆盖升级与音频/触控保持原样。
- 实验签名的发行操作需要管理员显式开启 Secrets，保持仓库 main 不受影响；不自动修改或清理服务器、已上传 Gateway 日志与既有 Release。
