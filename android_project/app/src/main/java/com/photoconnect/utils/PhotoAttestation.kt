package com.photoconnect.utils

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed proof that the app verified camera-origin photos before upload.
 * The server validates the HMAC using the same bearer token.
 */
data class PhotoAttestationBundle(
    val payloadJson: String,
    val signatureHex: String,
)

object PhotoAttestation {
    private const val VERSION = 1
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    fun buildForUpload(
        context: Context,
        accessToken: String,
        takerId: Int,
        imageUris: List<Uri>,
    ): PhotoAttestationBundle? {
        if (accessToken.isBlank() || imageUris.isEmpty()) return null
        val images = imageUris.mapNotNull { uri ->
            val verified = ExifUtils.isOriginalCameraPhoto(context, uri)
            val sha256 = sha256OfUri(context, uri) ?: return@mapNotNull null
            JSONObject()
                .put("sha256", sha256)
                .put("client_verified", verified)
        }
        if (images.size != imageUris.size) return null
        return sign(accessToken, takerId, images)
    }

    fun buildForFiles(
        context: Context,
        accessToken: String,
        takerId: Int,
        files: List<File>,
    ): PhotoAttestationBundle? {
        if (accessToken.isBlank() || files.isEmpty()) return null
        val images = files.mapNotNull { file ->
            if (!file.exists() || file.length() <= 0L) return@mapNotNull null
            val uri = Uri.fromFile(file)
            val verified = ExifUtils.isOriginalCameraPhoto(context, uri)
            val sha256 = sha256OfFile(file) ?: return@mapNotNull null
            JSONObject()
                .put("sha256", sha256)
                .put("client_verified", verified)
        }
        if (images.size != files.size) return null
        return sign(accessToken, takerId, images)
    }

    private fun sign(
        accessToken: String,
        takerId: Int,
        images: List<JSONObject>,
    ): PhotoAttestationBundle {
        val payload = JSONObject()
            .put("v", VERSION)
            .put("taker_id", takerId)
            .put("issued_at", System.currentTimeMillis() / 1000L)
            .put("images", JSONArray(images))
        val payloadJson = payload.toString()
        val signatureHex = hmacSha256Hex(attestationKey(accessToken), payloadJson)
        return PhotoAttestationBundle(payloadJson, signatureHex)
    }

    private fun attestationKey(accessToken: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.UTF_8))

    private fun hmacSha256Hex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256OfUri(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                digestInputStream(input)
            }
        }.getOrNull()

    private fun sha256OfFile(file: File): String? =
        runCatching {
            file.inputStream().use { input -> digestInputStream(input) }
        }.getOrNull()

    private fun digestInputStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun isFresh(issuedAtEpochSeconds: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ageMs = nowMs - issuedAtEpochSeconds * 1000L
        return ageMs in 0..MAX_AGE_MS
    }
}
