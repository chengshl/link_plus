package io.github.shaonialife.buicklinkplus.gateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 私有配置文件与业务代码分离；凭证经 Android Keystore 加密且不进入备份。后台线程调用。 */
object EntryConfigStore {
    private const val KEY_ALIAS = "buicklinkplus.trace.gateway.entry"

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "link-plus-trace-gateway.json"))

    @Synchronized
    fun load(context: Context): EntryConfig? {
        val data = try {
            file(context).openRead().bufferedReader().use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        val json = JSONObject(data)
        require(json.getInt("version") == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false),
            GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)),
        )
        val token = cipher.doFinal(Base64.decode(json.getString("token"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return EntryConfig(EntryAddress.normalize(json.getString("gatewayUrl")), token)
    }

    @Synchronized
    fun save(context: Context, gatewayUrl: String, token: String) {
        val address = EntryAddress.normalize(gatewayUrl)
        require(token.isNotBlank() && token.length <= 4096 && token.none { it <= ' ' || it > '~' }) {
            "请输入有效的入口凭证。"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val json = JSONObject()
            .put("version", 1)
            .put("gatewayUrl", address)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val target = file(context)
        val output = target.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear(context: Context) {
        val target = file(context)
        target.delete()
        check(!target.baseFile.exists())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "入口凭证密钥不可用" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
