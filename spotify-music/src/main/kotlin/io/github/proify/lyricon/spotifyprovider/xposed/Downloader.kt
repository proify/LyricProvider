/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    private val downloadingIds = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun download(
        id: String,
        downloadCallback: DownloadCallback
    ): Job? {
        if (!downloadingIds.add(id)) return null

        return scope.launch {
            try {
                val response = SpotifyApi.fetchLyricResponse(id)
                downloadCallback.onDownloadFinished(id, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(id, e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }
}