package com.zapp.net.termux

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The lane scripts, shipped encrypted.
 *
 * `assets/serve.sh` and `assets/zapp_proxy.py` used to sit in the APK as plain text. An APK is
 * a zip: unzip it and the whole launcher and the whole egress proxy were readable, copyable
 * and rebrandable in under a minute. the owner, 22 Nov: "i dont want them to copy my script make
 * it unreadable".
 *
 * They are now AES-256-GCM blobs under `assets/lane/`, produced by `make-vault.js`. The key is
 * SHA-256(pepper | package | version), and the pepper is **not** stored as a string - it lives
 * here as XOR'd bytes, so `strings` on the dex finds nothing and a plain grep for the key
 * finds nothing either.
 *
 * The sealed assets keep the scripts out of reach of anyone who simply unzips the APK and
 * reads the files as plain text.
 *
 * Layout of each blob: iv(12) | tag(16) | ciphertext.
 */
object Vault {

    private const val TAG = "ZAPP"

    /** The pepper, XOR'd with [X]. Reassembled at runtime; never a literal in the dex. */
    private val P = intArrayOf(
        0x38, 0x39, 0x6c, 0x6d, 0x3f, 0x3f, 0x69, 0x6c, 0x69, 0x6c, 0x6e, 0x38,
        0x6b, 0x6f, 0x69, 0x68, 0x69, 0x3e, 0x6a, 0x6d, 0x69, 0x68, 0x38, 0x38,
        0x3b, 0x63, 0x6b, 0x3f, 0x6b, 0x6d, 0x63, 0x6e, 0x6f, 0x3e, 0x68, 0x63,
        0x6b, 0x3e, 0x6d, 0x63, 0x3f, 0x3f, 0x6e, 0x6f, 0x6d, 0x3f, 0x6e, 0x6c,
        0x6d, 0x6b, 0x39, 0x6e, 0x3f, 0x6d, 0x3e, 0x39, 0x6d, 0x68, 0x6c, 0x3b,
        0x6d, 0x6e, 0x69, 0x62,
    )
    private const val X = 0x5a

    private const val SALT = "|com.zapp.net|lane-v1"
    private const val IV_LEN = 12
    private const val TAG_LEN = 16

    /** `serve.sh`, decrypted. Null means the asset is missing or the blob was tampered with. */
    fun serve(ctx: Context): String? = read(ctx, "lane/serve.bin", "serve.sh")

    /** `zapp_proxy.py`, decrypted. */
    fun proxy(ctx: Context): String? = read(ctx, "lane/proxy.bin", "zapp_proxy.py")

    private fun read(ctx: Context, asset: String, label: String): String? = runCatching {
        val blob = ctx.assets.open(asset).use { it.readBytes() }
        if (blob.size <= IV_LEN + TAG_LEN) {
            Log.e(TAG, "vault: $label blob is ${blob.size} B - too short")
            return null
        }
        val iv = blob.copyOfRange(0, IV_LEN)
        val tag = blob.copyOfRange(IV_LEN, IV_LEN + TAG_LEN)
        val body = blob.copyOfRange(IV_LEN + TAG_LEN, blob.size)
        // GCM wants tag and ciphertext as one buffer.
        val sealed = body + tag
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
        String(c.doFinal(sealed), Charsets.UTF_8)
    }.onFailure {
        Log.e(TAG, "vault: $label failed to open: ${it.message}")
    }.getOrNull()

    private fun key(): ByteArray {
        val pepper = ByteArray(P.size) { (P[it] xor X).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pepper)
        md.update(SALT.toByteArray(Charsets.UTF_8))
        return md.digest()
    }
}
