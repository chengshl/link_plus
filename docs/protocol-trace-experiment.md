# Link Plus protocol trace experiment

Date: 2026-11-06
Base: `test/apk-smoke-20261106@7192f8e4dd16c790b817d3acab0e658e7ba2c5dd`
Experiment: `experiment/link-plus-protocol-trace-20261106`

## Coverage
- iAP2 Bluetooth link frames and network virtual iAP2 frames: every observed RX/TX control, sequence, ACK, session ID and payload byte length.
- iAP2 CSM: every parsed RX/TX message ID, name, length, and TLV parameter IDs/lengths (no values).
- AirPlay RTSP control channel: CSeq, method, safe path, message length, response status code, latency; unsolicited requests are recorded.
- AirPlay event channel: outbound command type, CSeq and length; inbound request and response CSeq/status.
- Existing local application log continues to cover Bluetooth connection events, MFi handshake outcome, Wi-Fi binding, mDNS, virtual display, audio and teardown.
- The existing Export Log control includes protocol-trace data, with current-process or all-sessions mode (long press).

## Privacy and resource limits
- No raw packets, TLV values, passwords, keys, certificate contents, pairing tokens, public IPs, SSIDs, or device identifiers are recorded by the new protocol trace.
- On-device only; there is no automatic Gateway upload.
- 4 rotating local files, 512KiB each; a single background writer, 1024-pending-event cap.
- A separate `.buick.trace` package uses a test signing key and can coexist with the known-good `.buick` APK without overwriting it.
- Android wireless-debugging pairing is specific to package identity; separate Trace activation may be needed.
- This is diagnostics only, not a claim of a complete raw HCI/PCAP capture.

## Acceptance
1. Build `testTraceUnitTest` and `assembleTrace`.
2. Install Trace alongside the original APK without uninstalling the original.
3. Activate the Trace app's own embedded service, connect while parked.
4. Verify iAP2 TX/RX, MFi, Wi-Fi and RTSP CSeq/status appear in exported log.
5. Lock phone, verify car touch and audio remain functional, and disconnect normally.
6. Export and inspect secrets, duration, ring-overflow; uninstall Trace only after exporting if preferred.
