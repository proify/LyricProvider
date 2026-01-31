/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.decrypt

import java.util.zip.InflaterInputStream

object QrcDecrypter {
    private val QQ_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val decryptSchedule = Array(3) { Array(16) { ByteArray(6) } }.also {
        DESHelper.tripleDESKeySetup(QQ_KEY, it, DESHelper.DECRYPT)
    }

    fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null

        return runCatching {
            val encryptedBytes = hexStringToByteArray(encrypted)
            val decryptedData = ByteArray(encryptedBytes.size)

            val temp = ByteArray(8)
            val inputBlock = ByteArray(8)

            for (i in encryptedBytes.indices step 8) {
                val blockSize = if (i + 8 <= encryptedBytes.size) 8 else encryptedBytes.size - i
                System.arraycopy(encryptedBytes, i, inputBlock, 0, blockSize)
                DESHelper.tripleDESCrypt(inputBlock, temp, decryptSchedule)
                System.arraycopy(temp, 0, decryptedData, i, blockSize)
            }

            decompress(decryptedData).toString(Charsets.UTF_8)
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    private fun decompress(data: ByteArray): ByteArray =
        InflaterInputStream(data.inputStream()).use { it.readBytes() }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string length must be even" }

        return ByteArray(len / 2).apply {
            for (i in indices) {
                this[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}