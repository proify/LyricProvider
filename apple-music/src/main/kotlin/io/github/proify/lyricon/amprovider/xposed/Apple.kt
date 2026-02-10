package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object Apple : YukiBaseHooker() {
    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate { setupModule() }
        }
    }

    private fun setupModule() {
        DiskSongManager.initialize(appContext!!)
        initPreferences()
        setupLyriconProvider()
        registerMediaHooks()
        registerLyricHooks()
    }

    private fun initPreferences() {
        PreferencesMonitor.apply {
            initialize(appContext!!)
            listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    provider?.player?.setDisplayTranslation(selected)
                }
            }
        }
    }

    private fun setupLyriconProvider() {
        val application = appContext ?: return
        val classLoader = appClassLoader ?: return

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
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    provider?.player?.setPlaybackState(args[0] as? PlaybackState)
                }
            }

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
        appClassLoader!!.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
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