package com.signalscope.app.util

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP (Time-based One-Time Password) generator.
 * Pure Kotlin implementation compatible with pyotp / Google Authenticator.
 * RFC 6238 compliant.
 */
object TOTP {

    private const val DIGITS = 6
    private const val PERIOD = 30L // seconds

    fun generateTOTP(secret: String): String {
        val key = base32Decode(secret.uppercase().replace(" ", "").replace("-", ""))
        val time = System.currentTimeMillis() / 1000L / PERIOD
        val timeBytes = ByteArray(8)
        var t = time
        for (i in 7 downTo 0) {
            timeBytes[i] = (t and 0xFF).toByte()
            t = t shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(DIGITS.toDouble()).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (c in input) {
            if (c == '=') break
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add((buffer shr bitsLeft).toByte())
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        return output.toByteArray()
    }
}
