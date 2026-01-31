/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import io.github.proify.qrckit.LyricResponse
import io.github.proify.qrckit.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private val downloadingIds = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isDownloading(id: String): Boolean = downloadingIds.contains(id)

    fun download(id: String, downloadCallback: DownloadCallback) {
        if (isDownloading(id)) return

        scope.launch {
            try {
                val response = QrcDownloader.downloadLyrics(id)
                downloadCallback.onDownloadFinished(response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(id, e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }
}

interface DownloadCallback {
    fun onDownloadFinished(response: LyricResponse)
    fun onDownloadFailed(id: String, e: Exception)
}