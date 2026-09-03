/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.spotifyprovider.xposed.api.NoFoundLyricException
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import kotlin.concurrent.thread
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object Spotify : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "SpotifyProvider"
    private var lyriconProvider: LyriconProvider? = null
    private var trackId: String? = null
    private var lastSong: Song? = null

    /** 请求头捕获失效提示（每进程只提示一次，避免刷屏） */
    private val headerMissingWarned = AtomicBoolean(false)

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "正在注入进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
                startHeaderCapture()
            }
        }
        hookMediaSession()
    }

    /**
     * 启动宿主 okhttp 请求头捕获。
     *
     * 需要在 Application 创建后执行：DexKit 路径的结果缓存要写宿主私有目录，
     * 依赖宿主 Context；R8 混淆版本的首次 DexKit 扫描耗时较长，统一放到后台线程，
     * 即使初始化失败也不影响 MediaSession 等其它 Hook，歌词请求只会退化为缺少登录头。
     */
    private fun startHeaderCapture() {
        val context = appContext ?: return
        val classLoader = appClassLoader ?: return
        thread(start = true, isDaemon = true, name = "SpotifyProvider-HeaderCapture") {
            try {
                HeaderCapture.install(context, classLoader, context.applicationInfo.sourceDir)
            } catch (e: Throwable) {
                YLog.error(
                    tag = TAG,
                    msg = "请求头捕获初始化失败，歌词接口将无法携带宿主登录态",
                    e = e
                )
            }
        }
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = (args[0] as? PlaybackState) ?: return@after
                    lyriconProvider?.player?.setPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after

                    val data = MetadataCache.save(metadata)

                    if (data == null) {

                        //处理其它通知（比如广告）
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        if (title.orEmpty().isNotBlank() || artist.orEmpty().isNotBlank()) {
                            YLog.info(tag = TAG, msg = "Invalid track info, using title and artist")
                            setPlaceholder(title, artist)
                        }

                        return@after
                    }

                    val id = data.id
                    if (id == this@Spotify.trackId) return@after
                    this@Spotify.trackId = id

                    onTrackIdChanged(data)
                }
            }
        }
    }

    private fun setPlaceholder(title: String?, artist: String?) {
        setSong(Song(name = title, artist = artist))
    }

    private fun onTrackIdChanged(data: Metadata) {
        val (id, title, artist) = data
        val cache = appContext?.let { DiskCache.get(it, id) }
        if (cache != null) {
            applyResponse(id, cache)
            return
        }

        setPlaceholder(title, artist)
        Downloader.download(id, this)
    }

    override fun onDownloadFinished(id: String, response: ByteArray) {
        applyResponse(id, response)
        appContext?.let { DiskCache.put(it, id, response) }
    }

    private fun applyResponse(id: String, response: ByteArray) {
        val song = response.toSongOrNull(id) ?: return
        if (song != lastSong) setSong(song)
    }

    override fun onDownloadFailed(id: String, e: Exception) {
        if (e is NoFoundLyricException) {
            YLog.debug(tag = TAG, msg = e.message)
            return
        }

        // 网络失败且关键请求头一个都没捕获到：多半是宿主结构再次变动导致
        // HeaderCapture 失配（如 okhttp 被 Cronet 替换），给出显式提示便于跟进
        val hint = if (e is IOException && SpotifyApi.snapshotHeaders().isEmpty()
            && headerMissingWarned.compareAndSet(false, true)
        ) {
            "；可能宿主已变更网络栈/结构导致请求头捕获失配，请查看 HeaderCapture 相关日志并更新模块"
        } else {
            ""
        }
        YLog.error(tag = TAG, msg = "Failed to fetch lyric for $id$hint", e = e)
    }

    private fun setSong(song: Song) {
        lastSong = song
        lyriconProvider?.player?.setSong(song)
    }
}
