/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method

object Apple : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null
    private lateinit var lyricRequester: LyricRequester

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        PreferencesMonitor.initialize(application)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()

        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        lyricRequester = LyricRequester(classLoader, application)
        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = lyricRequester
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()
        hookLyricViewModelConstructor()
    }

    // --- Hook 0: 收集 Apple Music 真实 PlayerLyricsViewModel 实例 ---
    // 手动 new 的 ViewModel 依赖不完整，无法真正触发歌词下载。
    // 收集真实实例后，LyricRequester 可复用其 loadLyrics 触发下载。
    private fun hookLyricViewModelConstructor() {
        try {
            val vmClass =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            vmClass.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after {
                        instanceOrNull?.let { lyricRequester.registerViewModel(it) }
                    }
                }
            }
            YLog.debug("hookLyricViewModelConstructor Hooked")
        } catch (e: Exception) {
            YLog.debug("Skip: PlayerLyricsViewModel constructor - ${e.message}")
        }
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        // 方案A（最可靠）：hook MediaSession.setMetadata(MediaMetadata)
        // Apple Music 无论使用系统 MediaSession 还是 MediaSessionCompat（androidx/support 兼容库），
        // 最终发布/更新 MediaMetadata 时都会调用系统 MediaSession.setMetadata()。
        // 自动切歌时此方法必然被调用——它是检测"切歌 + 获取歌名/歌手"最可靠的信号。
        try {
            val resolver =
                classLoader.loadClass("android.media.session.MediaSession")
                    .resolve()
                    .optional()
                    .firstMethodOrNull {
                        name = "setMetadata"
                        parameters(MediaMetadata::class)
                    }
            if (resolver != null) {
                resolver.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after
                        YLog.debug(
                            "MediaSession.setMetadata: id=${metadata.id}, " +
                                "title=${metadata.title}, artist=${metadata.artist}"
                        )
                        // 委托给 Manager 处理
                        PlaybackManager.onSongChanged(metadata.id)
                    }
                }
                YLog.debug("Hook OK: MediaSession.setMetadata")
            } else {
                YLog.debug("Skip: MediaSession.setMetadata not found")
            }
        } catch (e: Exception) {
            YLog.debug("Skip: MediaSession.setMetadata - ${e.message}")
        }

        // 方案B：hook MediaSession.Callback（Android 13+ 的 onMediaMetadataChanged / 旧版 onMetadataChanged）
        // 某些系统版本/场景下 App 会通过 SessionCallback 传递 metadata 而非直接调 setMetadata。
        try {
            val callbackClass =
                classLoader.loadClass("android.media.session.MediaSession\$Callback")
            hookMetadataCallback(callbackClass, "onMediaMetadataChanged")
            hookMetadataCallback(callbackClass, "onMetadataChanged")
        } catch (e: Exception) {
            YLog.debug("Skip: MediaSession.Callback - ${e.message}")
        }

        // 方案C：兼容库 MediaSessionCompat 的 setMetadata（若 App 绕过系统 MediaSession 直接调用兼容库）
        hookCompatMetadataChange("androidx.media.session.MediaSessionCompat")
        hookCompatMetadataChange("android.support.v4.media.session.MediaSessionCompat")

        YLog.debug("hookMediaMetadataChange Setup Complete")
    }

    private fun hookCompatMetadataChange(className: String) {
        try {
            val resolver =
                classLoader.loadClass(className)
                    .resolve()
                    .optional()
                    .firstMethodOrNull {
                        name = "setMetadata"
                        // 兼容库参数类型是各自包下的 MediaMetadataCompat，用模糊匹配
                        parameters(VagueType)
                    }
            if (resolver == null) {
                YLog.debug("Skip: $className.setMetadata not found")
                return
            }
            resolver.hook {
                after {
                    val compatMetadata = args[0] ?: return@after
                    // 兼容库 MediaMetadataCompat.getMediaMetadata() 转系统 MediaMetadata
                    val mediaMetadata = runCatching {
                        XposedHelpers.callMethod(compatMetadata, "getMediaMetadata") as? MediaMetadata
                    }.getOrNull() ?: return@after
                    val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after
                    YLog.debug("$className.setMetadata: id=${metadata.id}, title=${metadata.title}")
                    PlaybackManager.onSongChanged(metadata.id)
                }
            }
            YLog.debug("Hook OK: $className.setMetadata")
        } catch (e: Exception) {
            YLog.debug("Skip: $className.setMetadata - ${e.message}")
        }
    }

    private fun hookMetadataCallback(callbackClass: Class<*>, methodName: String) {
        try {
            // KavaRef 1.0.2: optional() 是 MemberScope 的方法（resolve() 之后调用），
            // 找不到成员时不抛异常；firstMethodOrNull 在无匹配时返回 null 而非抛异常。
            val resolver = callbackClass.resolve()
                .optional()
                .firstMethodOrNull {
                    name = methodName
                }
            if (resolver != null) {
                resolver.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after

                        // 委托给 Manager 处理
                        PlaybackManager.onSongChanged(metadata.id)
                    }
                }
                YLog.debug("Hook OK: MediaSession.Callback.$methodName")
            } else {
                YLog.debug("Skip: MediaSession.Callback.$methodName not found (API level)")
            }
        } catch (e: Exception) {
            YLog.debug("Skip: MediaSession.Callback.$methodName - ${e.message}")
        }
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        try {
            val resolver =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .resolve()
                    .optional()
                    .firstMethodOrNull { name = "buildTimeRangeToLyricsMap" }
                    ?: run {
                        YLog.debug("Skip: PlayerLyricsViewModel.buildTimeRangeToLyricsMap not found")
                        return
                    }
            resolver.hook {
                after {
                    YLog.debug("buildTimeRangeToLyricsMap:$args")
                    val arg: Any? = args[0]
                    if (arg == null) {
                        YLog.debug("args0 null")
                        return@after
                    }
                    val songNative = XposedHelpers.callMethod(arg, "get")
                    YLog.debug("songNative: $songNative")

                    // 从原生 Song 对象中提取 adamId（当前播放歌曲的权威 ID）
                    // 注意：buildTimeRangeToLyricsMap 是 Apple Music 内部为
                    // 当前播放歌曲构建歌词映射时调用的，因此这里的 adamId
                    // 一定是"当前正在播放的歌曲"，而不是旧歌。
                    val adamId = runCatching {
                        XposedHelpers.callMethod(songNative, "getAdamId")?.toString()
                    }.getOrNull()
                    YLog.debug("buildTimeRangeToLyricsMap adamId: $adamId")

                    // 委托给 Manager 处理（传入 adamId 用于切歌检测）
                    PlaybackManager.onLyricsBuilt(songNative, adamId)
                }
            }
            YLog.debug("hookLyricBuildMethod Hooked")
        } catch (e: Exception) {
            YLog.error("hookLyricBuildMethod failed", e)
        }
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
        try {
            val exoPlayerClass =
                classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

            exoPlayerClass.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after {
                        exoMediaPlayerInstance = instanceOrNull
                        getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                    }
                }
            }

            val seekResolver = exoPlayerClass.resolve().optional().firstMethodOrNull {
                name = "seekToPosition"
                parameters(Long::class)
            }
            if (seekResolver != null) {
                seekResolver.hook {
                    after {
                        val position = args(0).cast<Long>() ?: 0L
                        if (isPlaying) provider?.player?.seekTo(position)
                    }
                }
                YLog.debug("Hook OK: ExoMediaPlayer.seekToPosition")
            } else {
                YLog.debug("Skip: ExoMediaPlayer.seekToPosition not found")
            }

            val stateResolver =
                classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
                    .resolve()
                    .optional()
                    .firstMethodOrNull {
                        name = "onPlaybackStateChanged"
                        parameters(VagueType, Int::class, Int::class)
                    }
            if (stateResolver != null) {
                stateResolver.hook {
                    after {
                        when (PlaybackState.of(args[2] as Int)) {
                            PlaybackState.PLAYING -> startSyncAction()
                            else -> stopSyncAction()
                        }
                    }
                }
                YLog.debug("Hook OK: LocalMediaPlayerController.onPlaybackStateChanged")
            } else {
                YLog.debug("Skip: LocalMediaPlayerController.onPlaybackStateChanged not found")
            }
        } catch (e: Exception) {
            YLog.error("hookExoMediaPlayer failed", e)
        }
    }

    // --- 进度同步逻辑 ---

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

}
