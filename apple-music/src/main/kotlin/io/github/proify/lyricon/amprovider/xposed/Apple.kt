/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object Apple : YukiBaseHooker() {

    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader
    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate { setupModule() }
        }
    }

    private fun setupModule() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return

        DiskSongManager.initialize(application)
        initPreferences()
        setupLyriconProvider()
        registerMediaHooks()
        registerLyricHooks()
    }

    private fun initPreferences() {
        PreferencesMonitor.apply {
            initialize(application)
            listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    provider?.player?.setDisplayTranslation(selected)
                }
            }
        }
    }

    private fun setupLyriconProvider() {
        provider = LyriconFactory.createProvider(
            context = application,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = application.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
            register()
        }

        PlaybackManager.init(
            remotePlayer = provider!!.player,
            requester = LyricRequester(classLoader, application)
        )
    }

    private fun registerMediaHooks() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            // 监听播放状态
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    provider?.player?.setPlaybackState(state)
                }
            }

            // 监听切歌元数据
            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    val cached = MediaMetadataCache.putAndGet(metadata) ?: return@after
                    PlaybackManager.onSongChanged(cached.id)
                }
            }
        }
    }

    private fun registerLyricHooks() {
        classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            .resolve()
            .firstMethod { name = "buildTimeRangeToLyricsMap" }
            .hook {
                after {
                    val songNative = XposedHelpers.callMethod(args[0] ?: return@after, "get")
                    PlaybackManager.onLyricsBuilt(songNative)
                }
            }
    }
}