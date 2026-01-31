/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.os.FileObserver
import java.io.File

class LyricFileObserver(context: Context, private val callback: FileObserverCallback) {

    // 定义监控目录列表，自动过滤空路径并确保存储目录存在
    private val watchDirs = listOfNotNull(
        File(context.externalCacheDir, "Cache/Lyric"),
        context.getExternalFilesDir("LrcDownload")
    ).onEach { if (!it.exists()) it.mkdirs() }

    @Suppress("DEPRECATION")
    private val observers = watchDirs.map { dir ->
        object : FileObserver(dir.absolutePath, CREATE or DELETE or MODIFY) {
            override fun onEvent(event: Int, path: String?) {

                //YLog.debug("LyricFileObserver: $event $path")
                if (path.isNullOrEmpty()) return

                val file = File(cacheDir, path)
                if (!file.exists() || !file.isFile) return

                callback.onFileChanged(event, file)
            }
        }
    }

    fun start() = observers.forEach { it.startWatching() }

    fun stop() = observers.forEach { it.stopWatching() }

    fun getFile(id: String): File {
        // 优先返回已存在的文件，否则默认指向第一个目录(Cache)
        return watchDirs.map { File(it, id) }.firstOrNull { it.exists() }
            ?: File(watchDirs.first(), id)
    }

    interface FileObserverCallback {
        fun onFileChanged(event: Int, file: File)
    }
}
