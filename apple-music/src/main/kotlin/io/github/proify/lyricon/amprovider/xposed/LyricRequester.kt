/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    // Apple Music 自己创建的真实 PlayerLyricsViewModel 实例池。
    // 手动 new 的 ViewModel 依赖可能不完整，无法真正触发歌词下载，
    // 因此优先复用真实实例（由 Apple.kt hook 构造函数收集）。
    private val realViewModels = HashSet<Any>()
    private var fallbackViewModel: Any? = null

    /**
     * 由 Apple.kt 的构造函数 hook 调用，收集真实 ViewModel 实例
     */
    fun registerViewModel(instance: Any) {
        if (realViewModels.add(instance)) {
            YLog.debug("LyricRequester: Registered real ViewModel (total=${realViewModels.size})")
        }
    }

    /**
     * 欺骗 Apple Music 触发歌词下载
     *
     * @see Apple.hookLyricBuildMethod
     */
    fun requestDownload(mediaId: String) {
        if (mediaId.isBlank()) {
            YLog.debug("LyricRequester: mediaId is null or blank")
            return
        }
        try {
            val song =
                XposedHelpers.newInstance(classLoader.loadClass("com.apple.android.music.model.Song"))
            XposedHelpers.callMethod(song, "setId", mediaId)
            // 同时设置 adamId，确保 Apple 内部 getAdamId() 能正确返回歌曲 ID，
            // 否则 saveSong() 读取的 adamId 可能为 null，导致歌词缓存失败
            runCatching { XposedHelpers.callMethod(song, "setAdamId", mediaId) }
            XposedHelpers.callMethod(song, "setHasLyrics", true)

            // 优先使用 Apple Music 真实创建的 ViewModel（下载才有效）
            val realViewModel = realViewModels.firstOrNull()
            if (realViewModel != null) {
                YLog.debug("LyricRequester: Using real ViewModel to loadLyrics for $mediaId")
                XposedHelpers.callMethod(realViewModel, "loadLyrics", song)
                YLog.debug("LyricRequester: Triggered download for $mediaId (real VM)")
                return
            }

            // 无真实实例时，使用手动创建的兜底实例
            YLog.debug("LyricRequester: No real ViewModel yet, using fallback for $mediaId")
            if (fallbackViewModel == null) {
                fallbackViewModel = classLoader
                    .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }
            XposedHelpers.callMethod(fallbackViewModel, "loadLyrics", song)
            YLog.debug("LyricRequester: Triggered download for $mediaId (fallback VM)")

        } catch (e: Exception) {
            YLog.error("LyricRequester: Failed to trigger download", e)
        }
    }
}