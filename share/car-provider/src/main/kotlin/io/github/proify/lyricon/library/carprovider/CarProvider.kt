/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.library.carprovider

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.android.AndroidUtils
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class CarProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : YukiBaseHooker() {

    private val tag = "CarProvider"
    private var provider: LyriconProvider? = null

    override fun onHook() {
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)
        YLog.debug(tag = tag, msg = "进程: $processName")

        onAppLifecycle {
            onCreate { initProvider() }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = providerPackageName,
            playerPackageName = context.packageName,
            logo = logo
        ).apply { register() }
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
                        val state = (args[0] as? PlaybackState)
                        provider?.player?.setPlaybackState(state)
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
//                        metadata.keySet().forEach { key ->
//                            val value = metadata.getString(key)
//                            YLog.debug(tag = tag, msg = "Metadata: $key=$value")
//                        }
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        if (!title.isNullOrBlank()) {
                            provider?.player?.sendText(title)
                        } else {
                            provider?.player?.sendText(null)
                        }
                    }
                }
            }
    }
}