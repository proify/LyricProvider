/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null

    // 当前 MediaSession 的 mediaId（来自 METADATA_KEY_MEDIA_ID）
    private var currentMediaId: String? = null

    // 当前已确认的歌曲 adamId（来自 buildTimeRangeToLyricsMap 回调或下载下载）
    private var currentAdamId: String? = null

    // 最近一次请求下载歌词的 mediaId
    private var lastRequestedMediaId: String? = null

    // 当前歌曲是否已收到歌词
    private var hasLyricsForCurrentSong = false

    // 歌词缓存（key = song.adamId，兼容 mediaId）
    private val songCache = HashMap<String, Song>()

    // 轮询协程：后台自动切歌时 buildTimeRangeToLyricsMap 不会触发（仅歌词界面打开时触发），
    // 因此切歌后发起下载，并轮询磁盘缓存确认歌词已落盘后更新。
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollJob: Job? = null

    fun init(remotePlayer: RemotePlayer, requester: LyricRequester) {
        this.player = remotePlayer
        this.lyricRequester = requester
    }

    /**
     * 当系统切歌或 Metadata 变化时调用
     *
     * 自动切歌时 MediaSession.setMetadata()（系统层）必然被调用，
     * 这是最可靠的切歌信号。这里负责：
     * 1. 更新当前歌曲ID与歌名/歌手
     * 2. 立即展示缓存或占位歌词
     * 3. 发起歌词下载
     * 4. 启动磁盘轮询兜底（因 buildTimeRangeToLyricsMap 仅歌词 UI 打开时触发）
     */
    fun onSongChanged(newMediaId: String?) {
        if (newMediaId.isNullOrBlank()) {
            currentMediaId = null
            currentAdamId = null
            lastRequestedMediaId = null
            hasLyricsForCurrentSong = false
            stopPolling()
            setSong(null)
            YLog.debug("PlaybackManager: Song changed to null")
            return
        }

        // 同一 mediaId 重复 setMetadata（进度刷新等）：不重复处理
        if (newMediaId == currentMediaId) {
            return
        }

        YLog.debug(
            "PlaybackManager: onSongChanged mediaId=$newMediaId " +
                "(was $currentMediaId, currentAdamId=$currentAdamId)"
        )
        currentMediaId = newMediaId
        currentAdamId = null
        hasLyricsForCurrentSong = false
        lastRequestedMediaId = null
        stopPolling()

        val metadata = MediaMetadataCache.getMetadataById(newMediaId)
        YLog.debug(
            "PlaybackManager: onSongChanged mediaId=$newMediaId " +
                "(title=${metadata?.title}, artist=${metadata?.artist})"
        )

        // 1. 尝试从内存缓存恢复
        val cachedSong = songCache[newMediaId]
        if (cachedSong != null && !cachedSong.lyrics.isNullOrEmpty()) {
            YLog.debug("PlaybackManager: Using cached lyrics for $newMediaId")
            hasLyricsForCurrentSong = true
            setSong(cachedSong)
            return
        }

        // 2. 尝试从磁盘缓存读取
        val diskSong = SongRepository.getSong(newMediaId)
        if (diskSong != null && !diskSong.lyrics.isNullOrEmpty()) {
            YLog.debug("PlaybackManager: Loaded lyrics from disk for $newMediaId")
            hasLyricsForCurrentSong = true
            setSong(diskSong)
            return
        }

        // 3. 无歌词：显示占位符，发起下载，并启动磁盘轮询兜底
        setSong(diskSong ?: Song(newMediaId, metadata?.title, metadata?.artist))
        lastRequestedMediaId = newMediaId
        YLog.debug("PlaybackManager: Requesting lyrics download for $newMediaId")
        lyricRequester?.requestDownload(newMediaId)
        startPolling(newMediaId)
    }

    /**
     * 当 Hook 捕获到歌词构建完成时调用（仅歌词界面打开时触发）
     *
     * 这是直接拿到【完整歌词 + 原生歌名/歌手】的最佳途径，
     * 可立即更新，无需等待轮询。
     */
    fun onLyricsBuilt(nativeSongObj: Any, adamId: String?) {
        // 提前建立 adamId ↔ mediaId 映射（AppleSongParser 回填歌名用）
        if (adamId != null && currentMediaId != null && adamId != currentMediaId) {
            MediaMetadataCache.putAdamIdMapping(adamId, currentMediaId!!)
            YLog.debug(
                "PlaybackManager: Pre-mapped adamId=$adamId -> mediaId=$currentMediaId"
            )
        }

        val song = SongRepository.saveSong(nativeSongObj)
        if (song == null) {
            YLog.debug("PlaybackManager: Failed to save song.")
            return
        }
        val id = song.id // 即 adamId

        YLog.debug(
            "PlaybackManager: Lyrics built, id=$id, adamId=$adamId, " +
                "currentAdamId=$currentAdamId, currentMediaId=$currentMediaId, " +
                "hasLyricsForCurrentSong=$hasLyricsForCurrentSong, " +
                "lyricLines=${song.lyrics?.size}"
        )

        if (song.lyrics.isNullOrEmpty()) {
            YLog.debug("PlaybackManager: Lyrics ready but empty, skipping update.")
            return
        }

        // buildTimeRangeToLyricsMap 由 Apple Music 内部为当前播放歌曲构建，
        // 直接接受并更新。
        currentAdamId = id
        hasLyricsForCurrentSong = true
        lastRequestedMediaId = null
        stopPolling()

        // 建立 adamId ↔ mediaId 映射
        if (id != null && currentMediaId != null && id != currentMediaId) {
            MediaMetadataCache.putAdamIdMapping(id, currentMediaId!!)
            YLog.debug("PlaybackManager: Mapped adamId=$id -> mediaId=$currentMediaId")
        }

        // 缓存歌词（adamId 和 mediaId 两个 key）
        if (id != null) {
            songCache[id] = song
            YLog.debug("PlaybackManager: Cached lyrics under adamId=$id")
        }
        currentMediaId?.let { songCache[it] = song }

        setSong(song)
    }

    /**
     * 磁盘轮询兜底：检查歌词是否已下载保存到磁盘。
     * 用于后台自动切歌时 buildTimeRangeToLyricsMap 不触发的场景。
     */
    private fun startPolling(mediaId: String) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // 最多轮询 15 秒，每 500ms 检查一次
            repeat(30) {
                if (!isActive) return@launch
                delay(500)

                // 检查当前歌曲是否仍是当初发起下载的那首（防止切歌后旧轮询误更新）
                if (currentMediaId != mediaId || hasLyricsForCurrentSong) return@launch

                val diskSong = runCatching { SongRepository.getSong(mediaId) }.getOrNull()
                val hasLyrics = diskSong != null && !diskSong.lyrics.isNullOrEmpty()
                YLog.debug("PlaybackManager: Polling disk for $mediaId, hasLyrics=$hasLyrics")
                if (hasLyrics && diskSong != null) {
                    hasLyricsForCurrentSong = true
                    songCache[mediaId] = diskSong
                    setSong(diskSong)
                    YLog.debug("PlaybackManager: Polling found lyrics for $mediaId")
                    return@launch
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun setSong(song: Song?) {
        player?.setSong(song)
    }
}