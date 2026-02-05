/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.paprovider.bridge.BridgeConstants
import io.github.proify.lyricon.paprovider.bridge.Configs
import io.github.proify.lyricon.paprovider.xposed.PowerAmp.lastPlaybackState
import io.github.proify.lyricon.paprovider.xposed.PowerAmp.onDownloadFinished
import io.github.proify.lyricon.paprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PowerAmp : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "PowerAmpProvider"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
    private const val ACTION_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED"

    private var provider: LyriconProvider? = null

    private var lastPlaybackState: PlaybackState? = null
    private var curMetadata: TrackMetadata? = null

    // 用于处理粘性广播的暂存 Intent
    @Volatile
    private var pendingTrackIntent: Intent? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    /** 用于匹配音频标签中歌词字段的正则 */
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                initDataChannel()
                setupLyriconProvider(this)
                setupBroadcastReceiver(this)
            }
            onTerminate { release() }
        }
        hookMediaSession()
    }

    private fun initDataChannel() {
        val channel = dataChannel
        channel.wait(key = BridgeConstants.ACTION_SETTING_CHANGED) {
            YLog.info(tag = TAG, msg = "Received setting change")
            updateSettings()
        }
    }

    private fun updateSettings() {
        val isTranslationEnabled = prefs.get(Configs.ENABLE_TRANSLATION)
        provider?.player?.setDisplayTranslation(isTranslationEnabled)
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState ?: return@after
                        lastPlaybackState = state

                        // 状态激活检查：补发之前被拦截的粘性广播
                        if (isPlaybackActive(state)) {
                            val pending = pendingTrackIntent
                            if (pending != null) {
                                YLog.debug(tag = TAG, msg = "Playback active, processing pending sticky track intent")
                                handleTrackChange(pending)
                                pendingTrackIntent = null
                            }
                        }

                        if (state.state == PlaybackState.STATE_PLAYING) {
                            startSyncPositionTask()
                        } else if (state.state == PlaybackState.STATE_PAUSED
                            || state.state == PlaybackState.STATE_STOPPED
                        ) {
                            stopSyncPositionTask()
                        }
                    }
                }
            }
    }

    /**
     * 判断当前播放状态是否属于“活跃”状态 (播放或暂停，而非停止/错误)
     */
    private fun isPlaybackActive(state: PlaybackState?): Boolean {
        if (state == null) return false
        return when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
            else -> false // STATE_STOPPED, STATE_NONE, STATE_ERROR, STATE_CONNECTING
        }
    }

    /**
     * 释放资源，取消所有正在运行的任务。
     */
    private fun release() {
        stopSyncPositionTask()
        coroutineScope.cancel()
        receiver?.let { appContext?.unregisterReceiver(it) }
        receiver = null
    }

    /**
     * 配置并注册 [LyriconProvider]。
     *
     * @param context 上下文
     */
    private fun setupLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        )
        updateSettings()
        provider?.register()
    }

    /**
     * 注册 PowerAmp 自定义广播接收器。
     *
     * @param context 上下文
     */
    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_TRACK_CHANGED)
            addAction(ACTION_STATUS_CHANGED)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 处理粘性广播逻辑：如果应用刚启动收到历史广播且当前未播放，则拦截
                if (isInitialStickyBroadcast && !isPlaybackActive(lastPlaybackState)) {
                    YLog.debug(tag = TAG, msg = "Sticky broadcast detected while inactive, pending track change")
                    pendingTrackIntent = intent
                    return
                }
                // 如果不是 Sticky 或已激活，清除挂起的 Intent（新的覆盖旧的）
                pendingTrackIntent = null

                when (intent.action) {
                    ACTION_TRACK_CHANGED -> handleTrackChange(intent)
                    ACTION_STATUS_CHANGED -> handleStatusChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /**
     * 开启进度同步任务。
     */
    private fun startSyncPositionTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive) {
                val position = calculateCurrentPosition()
                provider?.player?.setPosition(position)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    /**
     * 停止进度同步任务。
     */
    private fun stopSyncPositionTask() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * 根据 [lastPlaybackState] 快照推算当前实时位置。
     *
     * 计算逻辑：实时位置 = 快照位置 + (当前时间 - 快照产生时间) * 播放倍率
     *
     * @return 推算出的当前播放位置（毫秒）
     */
    private fun calculateCurrentPosition(): Long {
        val state = lastPlaybackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position

        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }

    /**
     * 处理切歌广播。
     *
     * @param intent 包含轨道信息的 Intent
     */
    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return
        val metadata = TrackMetadataCache.save(bundle) ?: return
        if (curMetadata == metadata) return
        curMetadata = metadata

        val path = metadata.path ?: return
        val resolvePath = resolvePowerampPath(path) ?: return

        val uri = SafUriResolver.resolveToUri(appContext!!, resolvePath) ?: return

        provider?.player?.setSong(Song(name = metadata.title, artist = metadata.artist))

        YLog.debug(tag = TAG, msg = "Trying to set song from $path")
        val success = setSongFromUri(metadata, uri)
        if (!success) {
            val isEnableNetSearch = prefs.get(Configs.ENABLE_NET_SEARCH)
            if (isEnableNetSearch) {
                YLog.debug(tag = TAG, msg = "Trying to search lyric from net")
                setSongFromNet(metadata)
            } else {
                YLog.debug(tag = TAG, msg = "No lyric found in $path")
            }
        }
    }

    /**
     * 从网络搜索歌词并设置给提供者。
     *
     * @param metadata 轨道元数据
     *
     * @see [onDownloadFinished]
     */
    private fun setSongFromNet(metadata: TrackMetadata) {

        Downloader.search(metadata, this) {
            trackName = metadata.title
            artistName = metadata.artist
            albumName = metadata.album
        }
    }

    /**
     * 从指定的 URI 读取并解析歌词，随后更新提供者。
     *
     * @param data 轨道元数据
     * @param uri 文件的 SAF URI
     */
    private fun setSongFromUri(data: TrackMetadata, uri: Uri): Boolean {
        val startTime = System.currentTimeMillis()
        val lyric = matchLyric(uri) ?: run {
            YLog.debug(tag = TAG, msg = "No lyric found in $uri")
            return false
        }

        val lines = EnhanceLrcParser.parse(lyric, data.duration).lines.filter {
            !it.text.isNullOrBlank()
        }

        val song = Song(
            id = data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = lines
        )

        provider?.player?.setSong(song)

        YLog.debug(
            tag = TAG,
            msg = "Song updated. Match/Parse cost: ${System.currentTimeMillis() - startTime}ms"
        )
        return true
    }

    /**
     * 通过 TagLib 从文件元数据中匹配歌词。
     *
     * @param uri 文件 URI
     * @return 匹配到的歌词字符串，未找到则返回 null
     */
    private fun matchLyric(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        YLog.error(tag = TAG, msg = "Match lyric failed: $uri", e = e)
        null
    }

    /**
     * 解析 Poweramp 相对路径。
     * 例如: `primary/Music/Jay.flac` -> `primary:Music/Jay.flac`
     *
     * @param path 原始路径字符串
     * @return 解析后的 SAF 兼容路径格式
     */
    private fun resolvePowerampPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null

        val firstSlash = trimmed.indexOf('/')
        if (firstSlash == -1) return null

        val volumeId = trimmed.substring(0, firstSlash)
        val relativePath = trimmed.substring(firstSlash + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    /**
     * 处理 PowerAmp 状态变化广播（备用状态同步）。
     *
     * @param intent 包含状态信息的 Intent
     */
    private fun handleStatusChange(intent: Intent) {
        val isPlaying = !intent.getBooleanExtra("paused", true)
        provider?.player?.setPlaybackState(isPlaying)

        // 确保在手动暂停/播放时，如果 MediaSession Hook 未及时触发，仍能管理 Job
        if (isPlaying) startSyncPositionTask() else stopSyncPositionTask()
    }

    override fun onDownloadFinished(metadata: TrackMetadata, response: List<ProviderLyrics>) {
        if (metadata == curMetadata) {
            val lines = response.firstOrNull()?.lyrics?.rich
            val song = Song(
                id = metadata.id,
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration,
                lyrics = lines
            )
            provider?.player?.setSong(song)
        }
    }

    override fun onDownloadFailed(metadata: TrackMetadata, e: Exception) {
        YLog.error(tag = TAG, msg = "Download failed $e", e = e)
    }
}