/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.os.FileObserver
import java.io.File

@Suppress("DEPRECATION")
class LyricFileObserver(context: Context, callback: FileObserverCallback) {

    private val watchDirs by lazy {
        listOfNotNull(
            context.externalCacheDir?.let { File(it, "Cache/Lyric") },
            context.getExternalFilesDir("LrcDownload")
        )
    }

    private val observers: List<FileObserver> by lazy {
        watchDirs.map { dir ->
            if (!dir.exists()) dir.mkdirs()

            object : FileObserver(dir.absolutePath, CREATE or DELETE or MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return

                    val file = File(dir, path)
                    if (file.isFile) callback.onFileChanged(event, file)
                }
            }
        }
    }

    fun start() {
        observers.forEach { it.startWatching() }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
    }

    fun getFile(id: String): File? {
        return watchDirs.map { File(it, id) }
            .firstOrNull { it.exists() && it.isFile }
    }

    interface FileObserverCallback {
        fun onFileChanged(event: Int, file: File)
    }
}