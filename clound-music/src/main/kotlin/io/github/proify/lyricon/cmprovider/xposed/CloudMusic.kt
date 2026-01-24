/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("UnnecessaryVariable")

package io.github.proify.lyricon.cmprovider.xposed

import android.annotation.SuppressLint
import android.app.Application
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.cmprovider.xposed.Constants.APP_PACKAGE_NAME
import io.github.proify.lyricon.cmprovider.xposed.Constants.ICON
import io.github.proify.lyricon.cmprovider.xposed.PreferencesMonitor.PreferenceCallback
import io.github.proify.lyricon.cmprovider.xposed.parser.LyricParser
import io.github.proify.lyricon.lyric.model.Song
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
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method

/**
 * @author Tomakino
 * @since 2026/1/20
 */
@SuppressLint("StaticFieldLeak")
object CloudMusic : YukiBaseHooker(), LyricFileObserver.FileObserverCallback {
    private var provider: LyriconProvider? = null
    private var lastSong: Song? = null

    private const val POSITION_UPDATE_INTERVAL = ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL

    private val hotHooker = HotHooker()

    // 状态追踪
    @Volatile
    private var currentMusicId: String? = null
    private var lyricFileObserver: LyricFileObserver? = null

    // 协程作用域
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }

    // 任务句柄
    private var progressJob: Job? = null
    private var loadingJob: Job? = null

    private var isPlaying = false

    private var dexKitBridge: DexKitBridge? = null
    private var preferencesMonitor: PreferencesMonitor? = null

    init {
        System.loadLibrary("dexkit")
    }

    override fun onHook() {
        YLog.debug("Hooking...")
        dexKitBridge = DexKitBridge.create(appInfo.sourceDir)
        preferencesMonitor = PreferencesMonitor(dexKitBridge!!, object : PreferenceCallback {
            override fun onTranslationOptionChanged(isTranslationSelected: Boolean) {
                provider?.player?.setDisplayTranslation(isTranslationSelected)
            }
        })
        reinit(appClassLoader!!)

        onAppLifecycle {
            onCreate {
                setupLyricFileObserver()
                setupProvider()
            }
        }

        // 处理热更新加载器
        "com.tencent.tinker.loader.TinkerLoader".toClass(appClassLoader)
            .resolve()
            .method { name = "tryLoad" }
            .forEach {
                it.hook {
                    after {
                        val app = args[0] as Application
                        reinit(app.classLoader)
                    }
                }
            }
    }

    private fun reinit(classLoader: ClassLoader) {
        preferencesMonitor?.update(classLoader)
        hotHooker.rehook(classLoader)
    }

    /**
     * 初始化或重启歌词文件观察者。
     */
    fun setupLyricFileObserver() {
        lyricFileObserver?.stop()
        lyricFileObserver = LyricFileObserver(appContext ?: return, this@CloudMusic)
        lyricFileObserver?.start()
    }

    private fun setupProvider() {
        val application = appContext ?: return
        provider?.destroy()

        val newProvider = LyriconProvider(
            context = application,
            providerPackageName = APP_PACKAGE_NAME,
            playerPackageName = application.packageName,
            logo = ProviderLogo.fromSvg(ICON)
        )

        newProvider.player.apply {
            setPositionUpdateInterval(POSITION_UPDATE_INTERVAL.toInt())
            setDisplayTranslation(preferencesMonitor?.isTranslationSelected() == true)
        }
        newProvider.register()
        this.provider = newProvider
    }

    /**
     * 处理文件变更回调。
     * * 仅当变更文件对应当前播放歌曲且当前无歌词时触发重载。
     */
    override fun onFileChanged(event: Int, file: File) {
        val currentId = currentMusicId ?: return
        val fileName = file.name

        // 如果文件名不匹配当前ID，直接忽略
        if (fileName != currentId) return

        // 如果当前已经有有效歌词，且不是强制刷新场景，可以忽略（视需求而定）
        // 这里采用保守策略：如果文件变动，尝试重新加载
        mainScope.launch {
            val metadata = MediaMetadataCache.getMetadataById(currentId) ?: return@launch
            loadAndSetSong(metadata, forceReload = true)
        }
    }

    /**
     * 响应歌曲元数据变更。
     */
    private fun onSongChanged(metadata: MediaMetadataCache.Metadata) {
        val newId = metadata.id
        if (currentMusicId == newId) return // ID 未变，忽略

        currentMusicId = newId

        // 取消上一次正在进行的加载任务，避免竞态条件
        loadingJob?.cancel()

        // 启动新的加载任务
        loadingJob = mainScope.launch {
            loadAndSetSong(metadata)
        }
    }

    /**
     * 核心加载逻辑：加载并设置歌曲信息。
     * * 包含 内存 -> 原始文件解析 的降级策略。
     */
    private suspend fun loadAndSetSong(
        metadata: MediaMetadataCache.Metadata,
        forceReload: Boolean = false
    ) {
        val id = metadata.id

        // 1. 构建占位符（快速响应 UI）
        val placeholder = Song(
            id = id,
            name = metadata.title,
            artist = metadata.artist,
            duration = metadata.duration
        )

        // 2. 异步获取完整数据
        val songToSet = withContext(Dispatchers.IO) {

            // 尝试解析原始文件
            val rawFile = lyricFileObserver?.getFile(id)
            if (rawFile != null && rawFile.exists()) {
                runCatching {
                    val jsonString = rawFile.readText()
                    val response = LyricParser.parseResponse(jsonString)
                    val parsedSong = response.toSong()


                    // 如果解析结果有效，返回解析后的 Song
                    if (!parsedSong.lyrics.isNullOrEmpty() && !response.pureMusic) {
                        return@withContext parsedSong
                    }
                }.onFailure {
                    YLog.debug("Failed to parse lyric file for $id: ${it.message}")
                }
            }

            // 兜底返回占位符
            return@withContext null
        }

        // 3. 更新 Provider
        // 再次检查 ID，防止协程挂起期间歌曲已切换
        if (currentMusicId == id) {
            setSong(songToSet ?: placeholder)
        }
    }

    private fun setSong(song: Song) {
        // 去重逻辑：避免重复设置相同的空歌词
        if (song.lyrics.isNullOrEmpty() && lastSong?.lyrics.isNullOrEmpty()) {
            if (lastSong?.id == song.id) return
        }

        // 深度比较，如果完全一致则跳过（需 Song 类实现 equals）
        if (lastSong == song) return

        YLog.debug(msg = "setSong: ${song.name} (lyrics: ${song.lyrics?.size ?: 0})")
        lastSong = song
        provider?.player?.setSong(song)
    }

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
                val pos = readPosition()
                provider?.player?.setPosition(pos.toLong())
                delay(POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun readPosition(): Int {
        return try {
            hotHooker.getCurrentTimeMethod?.invoke(null) as? Int ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 内部 Hook 逻辑封装。
     */
    private class HotHooker {
        private val unhooks = mutableListOf<YukiMemberHookCreator.MemberHookCreator.Result?>()

        var getCurrentTimeMethod: Method? = null

        fun rehook(classLoader: ClassLoader) {
            unhooks.forEach { it?.remove() }
            unhooks.clear()

            val playServiceClass = "com.netease.cloudmusic.service.PlayService".toClass(classLoader)
            getCurrentTimeMethod = playServiceClass.getDeclaredMethod("getCurrentTime")

            val playServiceClassResolve = playServiceClass.resolve()

            // Hook 元数据变更
            unhooks += playServiceClassResolve
                .firstMethod {
                    name = "onMetadataChanged"
                    parameterCount = 1
                }
                .hook {
                    after {
                        val bizMusicMeta = args[0] ?: return@after
                        // 确保元数据解析在调用线程执行，耗时操作在 CloudMusic 内部异步化
                        val metadata = MediaMetadataCache.putAndGet(bizMusicMeta) ?: return@after
                        onSongChanged(metadata)
                    }
                }

            // Hook 播放状态变更
            unhooks += playServiceClassResolve
                .firstMethod {
                    name = "onPlaybackStatusChanged"
                    parameters(Int::class)
                }
                .hook {
                    after {
                        val status = args[0] as? Int ?: return@after
                        when (status) {
                            3 -> startSyncAction() // Playing
                            2 -> stopSyncAction()  // Paused
                        }
                    }
                }
        }
    }
}