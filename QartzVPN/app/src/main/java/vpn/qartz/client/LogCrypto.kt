package vpn.qartz.client

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрует лог клиента НАШИМ публичным ключом, чтобы его можно было безопасно отправить в поддержку.
 * На устройстве наружу уходит только шифртекст — расшифровать может лишь держатель приватного ключа
 * (мы, оффлайн). Формат байт-в-байт совпадает с инструментом `qartzlog`:
 *
 *   "QLOG1\n" | u16be(wkLen) | RSA-OAEP(SHA-256)(aesKey32) | nonce12 | AES-256-GCM(gzip(plaintext))
 */
object LogCrypto {
    private const val MAGIC = "QLOG1\n"

    private fun publicKey(ctx: Context): PublicKey {
        val pem = ctx.assets.open("qartz-log-public.pem").bufferedReader().use { it.readText() }
        val b64 = pem.lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** Шифрует [plain] в контейнер QLOG1. */
    fun seal(ctx: Context, plain: ByteArray): ByteArray {
        val rnd = SecureRandom()
        val aesKey = ByteArray(32).also { rnd.nextBytes(it) }

        val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey(ctx), oaep)
        val wk = rsa.doFinal(aesKey)

        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = gcm.doFinal(gzip(plain))

        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write((wk.size ushr 8) and 0xFF)
        out.write(wk.size and 0xFF)
        out.write(wk)
        out.write(nonce)
        out.write(ct)
        return out.toByteArray()
    }

    /** Шифрует текущий box.log в cacheDir/qartz-log.enc и возвращает файл (null, если лога нет). */
    fun exportEncrypted(ctx: Context): File? {
        val log = QartzVpnService.logFile(ctx)
        val bytes = runCatching { if (log.exists()) log.readBytes() else null }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val outFile = File(ctx.cacheDir, "qartz-log.enc")
        outFile.writeBytes(seal(ctx, bytes))
        return outFile
    }
}
