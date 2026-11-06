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

## 固定签名已经启用（固定首发证据）

2026-11-07 重新核对 GitHub：首个固定签名版本 **v154 已正式作为独立 Trace prerelease 发布成功**，不可修改或覆盖：
- [Release exp-link-plus-trace-v154-7d78b96265cd](https://github.com/chengshl/link_plus/releases/tag/exp-link-plus-trace-v154-7d78b96265cd)
- [成功的 GitHub Actions #42257660981](https://github.com/chengshl/link_plus/actions/runs/42257660981)
- APK：`LinkPlus-Trace.apk`，`versionCode=154`，`versionName=0.0.9-trace-r3`
- Release SHA256：`50941dedd8fda0bd95f80ecc1e722e047e8f3b1866a3522caeccd109c540ef06`
- 固定证书 SHA256：`3fc11b60da53fd634d668bde7f7e9e19f6ed042d59502de2797b949e53e482e3`
- 别名：`linkplustracefixed`，RSA-4096，有效期至 2054-03-24。

所有后续版本必须用**同一密钥、同一 applicationId、严格递增的 versionCode**，才能覆盖升级、保留 Trace 配置。

## 三项 GitHub Secrets（与 CarDeck 一致的模式）

仓库 `chengshl/link_plus` → Settings → Secrets and variables → Actions → Repository secrets 仅需要：
1. `TRACE_SIGNING_KEYSTORE_B64`：现有固定签名 .p12 的单行 Base64。
2. `TRACE_SIGNING_STORE_PASSWORD`：Keystore 密码，同时用于 private key。
3. `TRACE_SIGNING_KEY_ALIAS`：`linkplustracefixed`。

Gradle 现在使用 `keyPassword = traceStorePassword`。因此不再读取旧的 `TRACE_SIGNING_KEY_PASSWORD` 与 `TRACE_SIGNING_PUBLISH_APPROVED` 两项 Secret；如果仓库仍保留它们，不影响当前工作流，但请先核对新的三项密钥和 v154 证书一致，再由管理员择机清理闲置 Secret。**不能公开任何 .p12、密码或 Base64，不能把 CarDeck 的密钥直接换成 Trace 密钥。**

## 后续发布：手动推送一次受控请求 Tag

本仓库 `main` 保持上游同步，不往 `main` 写发布工作流。GitHub 的 `workflow_dispatch` 依赖 workflow 在默认分支，所以本实验专用发布改为 **手动创建+推送请求 Tag**；正常源代码 `push` 只触发 smoke CI，**绝不自动签名发布**。

仅当新代码经过独立 CI 审查、确有高于已发布 154 的 `versionCode` 后，在已登录的本地 Git 环境执行：

```bash
set -Eeuo pipefail
git fetch origin experiment/link-plus-trace-fixed-update-20261107
git switch experiment/link-plus-trace-fixed-update-20261107
git pull --ff-only origin experiment/link-plus-trace-fixed-update-20261107
CODE="$(sed -nE 's/^val appVersionCode = ([0-9]+)$/\1/p' app/build.gradle.kts)"
HEAD="$(git rev-parse HEAD)"
test "$CODE" -gt 154
REQUEST_TAG="trace-publish-request-v${CODE}-${HEAD:0:12}"
echo "申请发布：$REQUEST_TAG"
git tag "$REQUEST_TAG" "$HEAD"
git push origin "refs/tags/$REQUEST_TAG"
```

这个精确 Tag 推送**就是单次发布确认**。工作流必须核对请求 Tag 版本码和源码 SHA、对应实验分支最新 HEAD、GitHub 已有版本最大值、固定签名 Secret/指纹，才执行：
`testTraceUnitTest → assembleTrace → apksigner + package/version → SHA256 → immutable schema-2 prerelease`。
请求内容不符合条件则 STOP；旧 v154、其他实验、正式 CarDeck 和 Fork `main` 不被触碰。**本轮仅修改发布门禁和 Secret 数量，不创建新的请求 Tag，也不重新发布 v154。**

## 首次迁移与回退
- 目前安装的 `.buick.trace` 是 GitHub Runner 临时 debug 签名，不能覆盖升级到本固定签名。**只有第一次**需先导出日志、记录 Gateway WSS 地址与令牌，再卸载旧 Trace 并从 GitHub 首次固定签名 Release 安装；不卸载、不中断已验证正常的 `.buick` 应用。不同签名不能继承旧 Trace 私有 Android Keystore 配置。
- 首个固定签名版安装后，未来的固定签名版用同包名与更高 versionCode 可覆盖升级并保留 Gateway 配置；如果失去密钥，不能继续无损升级。
- 初次签名 Release 的 CI 成功不代表手机自动更新与车机回归成功；先在停车条件下验证手动检查、无候选、断网、错 SHA/包名/签名、覆盖升级与音频/触控保持原样。
- 固定签名发行操作需要管理员手动创建请求 Tag，保持仓库 main 不受影响；不自动修改或清理服务器、已上传 Gateway 日志与既有 Release。
