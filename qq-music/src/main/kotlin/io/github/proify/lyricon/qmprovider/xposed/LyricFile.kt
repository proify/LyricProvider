/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import java.io.File

data class LyricFile(
    val id: Long,
    val code: String,
    val directory: File
) {

    constructor(id: Long, source: File) : this(
        id = id,
        code = source.name.let { fileName ->
            if ("." in fileName) {
                fileName.substring(0, fileName.lastIndexOf("."))
            } else {
                fileName
            }
        },
        directory = source.parentFile
    )

    fun getLrcFile(): File = File(directory, "$code.lrc")

    fun isLrcFileExist(): Boolean = getLrcFile().exists()

    fun getQrcFile(): File = File(directory, "$code.qrc")

    fun isQrcFileExist(): Boolean = getQrcFile().exists()

    fun getProducerFile(): File = File(directory, "$code.producer")

    fun isProducerFileExist(): Boolean = getProducerFile().exists()
}