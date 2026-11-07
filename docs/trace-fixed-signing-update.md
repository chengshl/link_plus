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

## 后续自动发布：提交代码 → CI → 固定签名 → Release

2026-11-07 用户明确要求恢复之前不需要人工推送 Tag 的自动发布方式，并继续只使用三项固定签名 Secrets。

`experiment/link-plus-trace-fixed-update-20261107` 现在通过 `push` 触发本分支专用签名工作流，触发路径仅为：
- `app/src/main/**`：Android 应用运行代码、资源或配置有变更；
- `app/build.gradle.kts`：应用构建配置（包括 versionCode）有变更；
- `.github/workflows/trace-fixed-release.yml`：修复该工作流本身，允许重新核对未发的版本。

`docs/**`、纯单元测试、Fork 的 `main` 和其他实验分支**不触发固定签名发布**。即使触发，版本不高于已发布版本，也会只记录 `publish=false` 并跳过编译签名和发布，不制造重复/降级 APK。

只有预检满足以下条件，工作流才自动继续：
1. `github.ref` 精确为 `refs/heads/experiment/link-plus-trace-fixed-update-20261107`，提交 SHA 与该分支当前 HEAD 完全相同（避免较早的 CI 竞态发布）。
2. `appVersionCode` 是有效整数，且**严格高于**当前仓库已发布 Trace 独立 Release 的最大版本码；v154 已发布，首次新版本应是 v155。
3. 固定签名的三项 GitHub Secrets 均存在，证书 SHA256 精确匹配 `3fc11b60da53fd634d668bde7f7e9e19f6ed042d59502de2797b949e53e482e3`，不允许 debug 签名作为回退。
4. `testTraceUnitTest`、`assembleTrace`、APK 签名校验、applicationId、versionCode、SHA256 全部通过，发布前再次检查当前分支 HEAD，且同 tag 尚不存在。

通过后自动发布**全新的** `exp-link-plus-trace-v<versionCode>-<commit12>` 独立 prerelease，包含 `LinkPlus-Trace.apk`、对应 `.sha256` 与 schema-2 源码/证书元数据；不可覆盖旧 v154 Release。不会自行执行 Android 手机端安装，手机端仍是自动检查可选、提示后用户确认下载和系统安装器。

这里的“自动发布”是 GitHub Actions 无需用户人工 Tag 的仓库级流程；**不等于绕过手机系统安装确认**。如果新提交没有递增 versionCode，将跳过固定签名发行（可运行 smoke CI），不能靠反复提交相同版本制造新包。

## 本次 v155 验收与回退

- v155 代码 `87625a291b3e2e4b0e61c55834f182b078f33093` 已通过独立 debug smoke CI [#42289536473](https://github.com/chengshl/link_plus/actions/runs/42289536473)；在本轮自动发布工作流提交前，固定签名 v155 尚未发布。
- 自动签名 CI 和新 Release 真正出现后，从**现有固定签名 v154** 的软件更新页检查并安装 v155，不卸载，不清除数据。确认版本和原 Gateway 地址/令牌保留。
- 后续查验缺网失败后的重试（至少 10 分钟且下一次应用进入前台）、缓存 APK 授权中断后再次校验安装、停车状态锁屏触控及音频实车回归。
- 若 CI 失败，保持 v154 可下载及现有安装不变；Android 同包高版本不能无损降级旧包，不通过卸载抹除配置来冒充无损回滚。

## 首次迁移与回退
- 目前安装的 `.buick.trace` 是 GitHub Runner 临时 debug 签名，不能覆盖升级到本固定签名。**只有第一次**需先导出日志、记录 Gateway WSS 地址与令牌，再卸载旧 Trace 并从 GitHub 首次固定签名 Release 安装；不卸载、不中断已验证正常的 `.buick` 应用。不同签名不能继承旧 Trace 私有 Android Keystore 配置。
- 首个固定签名版安装后，未来的固定签名版用同包名与更高 versionCode 可覆盖升级并保留 Gateway 配置；如果失去密钥，不能继续无损升级。
- 初次签名 Release 的 CI 成功不代表手机自动更新与车机回归成功；先在停车条件下验证手动检查、无候选、断网、错 SHA/包名/签名、覆盖升级与音频/触控保持原样。
- 固定签名发布由受控分支运行代码或构建配置更新自动触发，只有版本递增且全部门禁通过时发布；Fork main、既有 Release、已上传 Gateway 日志不变。
