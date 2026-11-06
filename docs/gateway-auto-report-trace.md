# Link Plus Trace → Once Gateway 自动上报（2026-11-06）

实验分支：`experiment/link-plus-protocol-trace-20261106`，只在 Fork 内推进。
上游已有的 `.buick` 正常连接/锁屏/音频包不变，新测试 APK 使用 `.buick.trace` 包名。

## 本次功能
- 把 CarDeck / Buick-E5 现有的 Gateway WebSocket 协议移植进 Link Plus Trace。
- App 首次需要在手机“关于 → 日志 → Once 日志自动上报设置”填写完整的 `wss://` 地址和独立 Gateway token，点击“保存并开启自动上报”；以后的每次应用进程启动自动恢复。
- 同时上报 `APP` 应用日志和 `TRACE` 结构化协议事件：蓝牙 iAP2、MFi、AirPlay RTSP、事件 ACK、虚拟 iAP2。原有 Trace 继续在本地保留四个 512KiB 循环文件。
- Gateway 使用独立的已验证互联网连接（优先 VPN/移动网络），不会重新绑定或接管 CarPlay Wi-Fi；无外网时有界内存积压及指数重连。
- 与既有 Gateway 的 `hello → register → heartbeat/status → logs → logs_ack` 协议一致；每批最多 8 行、必须 ACK 才计数，丢包按限额计数。
- 不开启系统日志抓取、不会上传原始 iAP2 字节流、证书、热点密码、令牌或密钥；上报前再次使用原 CarDeck Gateway 脱敏过滤。
- 仅配置 Gateway URL、token 和本机生成的独立 UUID，不读取原 CarDeck 应用私有存储、签名或凭据。

## 2026-11-06 第二轮专项取证修正
- 给每条接收的事件请求分配本地单调关联号 `local`，随后在同一次事件响应 200 的日志中复用；如果车机没有提供 `CSeq`，明确标记 `remoteCseq=absent`，绝不伪造远端序列号，也不修改在线 RTSP 报文或响应规则。
- Gateway 自动上报前另加一层隐私过滤：歌名/歌手和歌词不出现在自动上报里，协议报文的 `head/hex/raw/payload/pub/全字段` 预览移除，但手机本地既有诊断保留；增加合成脱敏回归测试。
- 音频 UDP 发送失败与同步包停止时增加 `running/stopped/socketClosed/syncOwned/sentMedia/sentSync` 等状态信息，用于识别用户断开、交接及真正发送异常，仅留证不改媒体发送行为。
- 修正 Gateway 手机端丢弃计数重复累加的显示；计数并不等于服务器持久化保证。
- 这些变更不会追溯清除之前已自动上报、位于现有七天保留期内的历史记录；服务端存量清理必须独立审计授权。

## 限制
- 只要 Trace App 进程存在（通常由 CarPlay 前台服务保持），上报会持续运行；强制停止、系统杀进程或手机关机后不会独立后台唤醒。
- 网络中断仅缓存内存队列；不会把未发的私有日志自动持久化并补传历史。
- 网关服务端需要启用 `device_status_v1` 与 `logs_v1`；若鉴权失败会暂停，须修正令牌重新保存。
- 自动上报在一次用户主动配置后生效；关闭按钮删除加密配置及停止后续发送。
- 单次上传的应用日志被限制为 256 字符，队列容量 2048 行；上游事件过多时会丢旧行，状态显示丢弃计数。
- 车机/手机真机回归未完成，自动上报不会证明 AirPlay 握手已全部成功。

## 验收
1. 在手机中安装 Trace 自动上报 APK，已验证的 `.buick` 旧版保留。
2. 打开“关于 → 日志 → Once 日志自动上报设置”，填写已存在的 Gateway `wss://` 地址和访问令牌；保存并启用。
3. 检查状态从“连接 Gateway 中”转为“正常上报中”；ACK 确认行数应不断增加，服务端设备列表出现新设备。
4. 保持手机有独立的移动网络或 VPN，停车连接 E5，分别测试锁屏触控、切歌、音频 ≥3 分钟以及正常断开。
5. 服务端确认收到 `IAP2-LINK`、`IAP2-CSM`、`AIRPLAY-RTSP`、`EVENT-RX-ACK` 等不同类型的日志，并查看批次确认数。
6. 暂时断开移动网络再恢复，观察状态进入重连，ACK 恢复；在回归过程中，不应影响车机热点和主推流。
7. 检查服务端不包含密码、密钥、MAC/IP、SSID 或私有 URL。必要时在 Trace 本地“导出日志”留存证据。
