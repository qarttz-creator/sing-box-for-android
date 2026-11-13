package vpn.qartz.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Прозрачное шифрование значений на диске ключом из AndroidKeyStore (аппаратно-защищённый, не
 * извлекаемый с устройства). Защищает сохранённые профили/конфиг (ссылки шлюзов, креды) от простого
 * чтения дампа файлов/prefs. Приложение читает/пишет как обычно — на диске лежит шифртекст.
 *
 * Формат: "ar1:" + base64(nonce12 ‖ AES-256-GCM(plaintext)). Значения без префикса считаются
 * легаси-плейнтекстом (миграция: читаются как есть, при следующей записи шифруются).
 */
object AtRest {
    private const val ALIAS = "qartz_at_rest"
    private const val MARK = "ar1:"
    private const val KS = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    /** Шифрует значение. При сбое возвращает исходную строку (деградация, не краш). */
    fun enc(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv // GCM: 12 байт, генерирует keystore
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        MARK + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        plain
    }

    /** Расшифровывает; значение без префикса возвращает как есть (легаси). При сбое — пустая строка. */
    fun dec(stored: String): String {
        if (!stored.startsWith(MARK)) return stored
        return try {
            val raw = Base64.decode(stored.substring(MARK.length), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
