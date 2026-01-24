/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.os.FileObserver
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File

class LyricFileObserver(context: Context, private val callback: FileObserverCallback) {

    private val cacheDir = File(context.externalCacheDir, "Cache/Lyric")

    @Suppress("DEPRECATION")
    private val fileObserver =
        object : FileObserver(cacheDir.absolutePath, CREATE or DELETE or MODIFY) {
            override fun onEvent(event: Int, path: String?) {

                YLog.debug("LyricFileObserver: $event $path")
                if (path.isNullOrEmpty()) return

                val file = File(cacheDir, path)
                if (!file.exists() || !file.isFile) return

                callback.onFileChanged(event, file)
            }
        }

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun start() {
        fileObserver.startWatching()
    }

    fun stop() {
        fileObserver.stopWatching()
    }

    fun getFile(id: String): File {
        return File(cacheDir, id)
    }

    interface FileObserverCallback {
        fun onFileChanged(event: Int, file: File)
    }
}