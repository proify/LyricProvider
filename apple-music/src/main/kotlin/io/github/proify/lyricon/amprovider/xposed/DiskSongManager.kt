/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.provider.common.extensions.deflate
import io.github.proify.lyricon.provider.common.extensions.inflate
import io.github.proify.lyricon.provider.common.extensions.json
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.util.Locale

object DiskSongManager {
    private var baseDir: File? = null

    fun initialize(context: Context) {
        val lyriconDir = File(context.filesDir, "lyricon")

        val locale = Locale.getDefault()
        baseDir = File(File(lyriconDir, "songs"), locale.toLanguageTag())
        baseDir?.mkdirs()
    }

    fun save(appleSong: AppleSong): Boolean {
        val id = appleSong.adamId
        if (id.isNullOrBlank()) return false
        val string = json.encodeToString(appleSong)
        return runCatching {
            val file = getFile(id)

            file.also { it.parentFile?.mkdirs() }
                .writeBytes(
                    string
                        .toByteArray(Charsets.UTF_8)
                        .deflate()
                )
        }.isSuccess
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun load(id: String): AppleSong? {
        return runCatching {
            getFile(id)
                .takeIf { it.exists() }
                ?.readBytes()
                ?.inflate()
                ?.let {
                    json.decodeFromString<AppleSong>(
                        it.toString(Charsets.UTF_8)
                    )
                }
        }.getOrNull()
    }

    fun hasCache(id: String): Boolean = getFile(id).exists()
    private fun getFile(id: String): File = File(baseDir, "$id.json.gz")
}