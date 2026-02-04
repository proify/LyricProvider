/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("SpellCheckingInspection")

package io.github.proify.qrckit

import io.github.proify.qrckit.decrypt.QrcDecrypter
import io.github.proify.qrckit.model.ParsedLyric
import java.util.regex.Pattern

data class LyricResponse(
    val id: String,
    val raw: String
) {

    val parsedLyric: ParsedLyric by lazy {
        val lyrics = regexGetCData(raw, "content")
        val trans = regexGetCData(raw, "contentts")
        val roma = regexGetCData(raw, "contentroma")

        val decryptedLyrics = QrcDecrypter.decrypt(lyrics)
        val decryptedTrans = QrcDecrypter.decrypt(trans)
        val decryptedRoma = QrcDecrypter.decrypt(roma)

        ParsedLyric(
            decryptedLyrics,
            decryptedTrans,
            decryptedRoma
        )
    }

    /**
     * 使用正则直接匹配 CDATA 内容
     * 匹配逻辑：找到 <标签名 ...> 之后最近的 <![CDATA[ 和 ]]> 之间的内容
     */
    private fun regexGetCData(xml: String, tagName: String): String? {
        try {
            val pattern = "<$tagName[^>]*>.*?<!\\[CDATA\\[(.*?)]]>"
            val regex = Pattern.compile(pattern, Pattern.DOTALL)
            val matcher = regex.matcher(xml)

            return if (matcher.find()) matcher.group(1).trim() else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}