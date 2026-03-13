@file:Suppress("unused")

package com.lagradost.cloudstream3.extractors.helper

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesHelper {
    fun cryptoAESHandler(
        data: String,
        key: ByteArray,
        iv: ByteArray,
        encrypt: Boolean = true
    ): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            if (encrypt) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                android.util.Base64.encodeToString(cipher.doFinal(data.toByteArray()), android.util.Base64.NO_WRAP)
            } else {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                String(cipher.doFinal(android.util.Base64.decode(data, android.util.Base64.DEFAULT)))
            }
        } catch (e: Exception) {
            null
        }
    }
}
