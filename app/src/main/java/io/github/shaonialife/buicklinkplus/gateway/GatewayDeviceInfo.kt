package io.github.shaonialife.buicklinkplus.gateway

import org.json.JSONObject
import java.util.UUID

/** Random installation identity; never a hardware identifier or credential. */
data class GatewayDeviceInfo(val deviceId: String, val appVersion: String, val sdk: Int) {
    init {
        require(UUID.fromString(deviceId).toString() == deviceId && UUID.fromString(deviceId).version() == 4)
        require(appVersion.length in 1..64 && appVersion.all { it.code in 32..126 })
        require(sdk in 0..1000)
    }

    fun registration(): JSONObject = JSONObject().put("type", "register")
        .put("device_id", deviceId).put("app_version", appVersion).put("sdk", sdk)
}
