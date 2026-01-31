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
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.common.util.ScreenStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Apple : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null

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
                providerPackageName = Constants.APP_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = LyricRequester(classLoader, application)
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()

//        XposedHelpers.findAndHookMethod(
//            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
//            classLoader,
//            "loadLyrics",
//            classLoader.loadClass("com.apple.android.music.model.PlaybackItem"),
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    val arg = param?.args?.get(0) ?: return
//                    ObjectUtils.print(arg)
//                }
//            })
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        val method = findMediaMetadataChangeMethod() ?: return

        method.hook {
            after {
                val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                val metadata = AppleMediaMetadata.putAndGet(mediaMetadata) ?: return@after

                // 委托给 Manager 处理
                PlaybackManager.onSongChanged(metadata.id)
            }
        }
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        val m =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .resolve()
                .firstMethod { name = "buildTimeRangeToLyricsMap" }
                .hook {
                    after {
                        YLog.debug("buildTimeRangeToLyricsMap:$args")
                        val arg: Any? = args[0]
                        if (arg == null) {
                            YLog.debug("args0 null")
                            return@after
                        }
                        val songNative = XposedHelpers.callMethod(arg, "get")
                        YLog.debug("songNative: $songNative")

                        // 委托给 Manager 处理
                        PlaybackManager.onLyricsBuilt(songNative)
                    }
                }
        YLog.debug("hookLyricBuildMethod Hooked: $m")
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
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

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
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
        progressJob = mainScope.launch {
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

    private fun findMediaMetadataChangeMethod() =
        "android.support.v4.media.MediaMetadataCompat".toClass()
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers)
                        && Modifier.isStatic(it.modifiers)
                        && it.parameterCount == 1
                        && it.returnType.simpleName.contains("MediaMetadata")
            }
}