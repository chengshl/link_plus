# Link Plus Trace v155 · 在线升级可靠性专项

基线：`experiment/link-plus-trace-fixed-update-20261107@ecfa3e0b93d9ae24cf61baead2abfebaf889046e`

## 目标与变更

- v154 首份固定签名 Release 已核验真实存在：`exp-link-plus-trace-v154-7d78b96265cd`，SHA256 `50941dedd8fda0bd95f80ecc1e722e047e8f3b1866a3522caeccd109c540ef06`。
- v155 代码保持原 Android applicationId、固定证书指纹、Gateway 与 CarPlay 链路不变；版本码 154 → 155，名称 `0.0.9-trace-r3` → `0.0.9-trace-r4`。
- 自动检查：24 小时节流只基于上次**成功联网查询**；无网络等失败会在 10 分钟后自动重试，而不是静默等待 24 小时；支持状态时间显示。开启自动检查开关会尝试立即执行一次。
- 下载后若 Android 拒绝未知应用安装，用户授予安装权限后，可回到「软件更新」点击「安装已下载的更新包」；再次按实时 Release、缓存 SHA256、包名、版本码、固定签名校验，拒绝旧包或篡改缓存，不要求重复下载。
- 新增 `TraceUpdateCheckPolicyTest`（首次、成功后的 24 小时、失败后 10 分钟、时间回拨）及原有 `TraceReleasePolicyTest`；维持严格 `-Werror`。

## 发布门禁与实测划分

1. 本分支代码提交自动触发 smoke CI（`testTraceUnitTest` 和 `assembleTrace`），APK **仅为临时 debug 签名**，不得覆盖 v154 固定签名版。
2. 只有 smoke CI 成功、Review 确认、再次确认固定签名凭据齐全，才由管理员**显式推送精确单次 `trace-publish-request-v155-<HEAD12>` Tag**；代码 push 绝不自动发布。工作流应验证 HEAD/Tag/versionCode/签名指纹、SHA256 与 schema-2，再产生 v155 新 prerelease。
3. 未发版前 v154 在应用内检查更新应显示「已是最新」，不得显示 v155 smoke Artifact；已发版后 v154 应发现 v155，用户可下载→SHA256/证书确认→系统安装器覆盖，保留既有 Gateway 设置。
4. 手机实测（停车、断开 CarPlay 时安装）：固定签名覆盖不卸载、不清数据；同包同签名、versionCode=155；Gateway token 与自动上报开关保留；无网络首次检查失败，10 分钟后（新建议）重试或手动重试；安装授权中断后复用已验证缓存。
5. 实车回归（新建议）：连接 E5 后锁屏 3 分钟测试触控、音乐与音频，检查 iAP2/AirPlay 事件是否照常自动上报，正常断开。未经实车验收不声称连接链路已通过。
6. 回退：Android 禁止通过系统直接降级到 v154；可暂停使用 v155 并保留现场日志，修复后发行更高版本码。不得通过卸载并损失 Gateway 私有配置冒充“无损回退”。

本实验尚不承诺后台无人值守静默安装；**自动检查 + 人工确认下载/安装**是有意设计。
