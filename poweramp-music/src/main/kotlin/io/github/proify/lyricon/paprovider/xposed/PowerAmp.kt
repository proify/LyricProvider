package io.github.proify.lyricon.paprovider.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.prefs
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.UnitType
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.paprovider.ui.Config
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * PowerAmp Hook 核心逻辑
 *
 * 负责监听 PowerAmp 的广播和状态变化，并同步给 Lyricon 服务。
 */
object PowerAmp {
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
    private const val ACTION_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED"

    /**
     * [延迟补偿]
     * PowerAmp 的音频输出路径通常比 MediaSession 报告的进度滞后。
     * -380ms 是经验值，用于对齐歌词与人声。
     */
    private const val LATENCY_COMPENSATION = -380L

    private var provider: LyriconProvider? = null

    // 使用 Volatile 确保多线程下的可见性，防止竞态条件
    @Volatile
    private var lastPath: String? = null
    @Volatile
    private var lastId: Long = 0L

    // 内存黑名单：记录导致严重崩溃的歌曲 ID，避免无限循环尝试
    private val errorBlacklist = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<String, Boolean>()))

    // 保存 Application Context
    private var appContext: Context? = null

    @Volatile
    private var lastPlaybackState: PlaybackState? = null

    // 暂存的 Intent：用于处理“应用刚启动收到粘性广播但 MediaSession 尚未就绪”的情况
    @Volatile
    private var pendingTrackIntent: Intent? = null

    // 使用 SupervisorJob 确保子协程失败不会取消整个 Scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    // 用于追踪当前的歌词搜索任务，以便切歌时取消旧任务
    private var searchJob: Job? = null

    fun hook(packageParam: PackageParam) {
        packageParam.apply {
            findClass("android.app.Application").hook {
                injectMember {
                    method { name = "onCreate"; emptyParam() }
                    afterHook {
                        runCatching {
                            init(instance as Application)
                        }.onFailure {
                            YLog.error("PowerAmp Hook 初始化严重失败", it)
                        }
                    }
                }
            }

            findClass("android.media.session.MediaSession").hook {
                injectMember {
                    method {
                        name = "setPlaybackState"
                        param(PlaybackState::class.java)
                        returnType = UnitType
                    }
                    afterHook {
                        runCatching {
                            val state = args[0] as? PlaybackState ?: return@afterHook
                            lastPlaybackState = state
                            syncPlaybackState(state)
                        }.onFailure {
                            YLog.error("同步播放状态失败", it)
                        }
                    }
                }
            }
        }
    }

    private fun init(context: Context) {
        this.appContext = context
        YLog.debug("PowerAmp Hook 初始化中...")
        
        // 安全初始化 LyricUtil
        try {
            LyricUtil.init(context)
        } catch (t: Throwable) {
            YLog.error("LyricUtil 初始化失败", t)
        }

        try {
            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = context.packageName,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )

            // 根据用户配置设置翻译开关
            val isTranslationEnabled = context.prefs().get(Config.ENABLE_TRANSLATION)
            YLog.debug("初始化配置 - 翻译显示: $isTranslationEnabled")
            provider?.player?.setDisplayTranslation(isTranslationEnabled)

            provider?.service?.addConnectionListener(object : ConnectionListener {
                override fun onConnected(provider: LyriconProvider) {
                    YLog.debug("Lyricon 服务: 已连接")
                    lastPlaybackState?.let { syncPlaybackState(it) }
                }

                override fun onDisconnected(provider: LyriconProvider) {
                    YLog.debug("Lyricon 服务: 已断开")
                    stopSyncAction()
                }

                override fun onReconnected(provider: LyriconProvider) {
                    YLog.debug("Lyricon 服务: 已重连")
                    lastPlaybackState?.let { syncPlaybackState(it) }
                }

                override fun onConnectTimeout(provider: LyriconProvider) {
                    YLog.error("Lyricon 服务: 连接超时")
                }
            })
            provider?.register()
        } catch (e: Throwable) {
            // 捕获 Throwable 防止 SDK 初始化导致宿主崩溃
            YLog.error("SDK 初始化失败", e)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_TRACK_CHANGED)
            addAction(ACTION_STATUS_CHANGED)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    // 获取是否为粘性广播（仅在 onReceive 中有效）
                    val isSticky = isInitialStickyBroadcast
                    
                    when (intent.action) {
                        ACTION_TRACK_CHANGED -> handleTrackChange(intent, isSticky)
                        ACTION_STATUS_CHANGED -> handleStatusChange(intent)
                    }
                }.onFailure {
                    YLog.error("广播处理异常: ${intent.action}", it)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun syncPlaybackState(state: PlaybackState) {
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        provider?.player?.setPlaybackState(isPlaying)

        // 【修复逻辑 2/2】状态激活检查
        // 当 MediaSession 状态变为活跃（播放或暂停）时，如果之前有被拦截的粘性广播，现在补发
        if (isPlaybackActive(state)) {
            val pending = pendingTrackIntent
            if (pending != null) {
                YLog.debug("状态已激活，补发挂起的切歌事件")
                // 补发时不再视为 sticky，强制处理
                handleTrackChange(pending, isSticky = false)
                pendingTrackIntent = null
            }
        }

        if (isPlaying) {
            startSyncAction()
        } else {
            stopSyncAction()
            val currentPos = calculateCurrentPosition()
            if (currentPos >= 0) {
                provider?.player?.setPosition(currentPos)
            }
        }
    }

    /**
     * 判断当前播放状态是否属于“活跃”状态 (播放或暂停，而非停止/错误)
     * 用于区分用户正常打开 App 和后台服务静默重启
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

    private fun startSyncAction() {
        if (progressJob?.isActive == true) return

        progressJob = scope.launch {
            while (isActive) {
                try {
                    val currentPos = calculateCurrentPosition()
                    if (currentPos >= 0) {
                        provider?.player?.setPosition(currentPos)
                    }
                    delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
                } catch (e: Exception) {
                    // 忽略进度更新的轻微异常
                    delay(1000)
                }
            }
        }
    }

    private fun stopSyncAction() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun calculateCurrentPosition(): Long {
        val state = lastPlaybackState ?: return -1L

        var rawPos = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            val deltaTime = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f
            rawPos += (deltaTime * speed).toLong()
        }

        return max(0L, rawPos + LATENCY_COMPENSATION)
    }

    private fun handleTrackChange(intent: Intent, isSticky: Boolean) {
        // 【修复逻辑 1/2】粘性广播拦截
        // 如果是粘性广播（App/Service 刚启动收到），且当前 MediaSession 尚未处于活跃状态
        // 则认为是后台复活，暂不处理，存入 pendingTrackIntent 等待状态激活
        if (isSticky && !isPlaybackActive(lastPlaybackState)) {
            YLog.debug("检测到后台复活 (Sticky广播且状态非活跃)，挂起切歌事件")
            pendingTrackIntent = intent
            return
        }
        // 如果不是 Sticky，或者已经是活跃状态，则立即清除挂起 Intent (新的覆盖旧的)
        pendingTrackIntent = null

        val bundle = intent.extras ?: return
        val title = bundle.getString("title") ?: "Unknown"
        val artist = bundle.getString("artist") ?: "Unknown"
        val path = bundle.getString("path")
        val duration = bundle.getInt("duration", 0) * 1000L // 转为毫秒
        val realId = bundle.getLong("id", 0L)

        // 简单的去重逻辑
        if (path == lastPath && realId == lastId && lastPath != null) {
            return
        }
        
        // 1. 立即取消上一首歌的搜索任务
        searchJob?.cancel()
        
        YLog.debug(
            """
            >>>>> 检测到切歌 <<<<<
            标题: $title
            歌手: $artist
            Real ID: $realId
            路径: $path
            时长: $duration ms
            """.trimIndent()
        )

        lastPath = path
        lastId = realId

        // 【修改点】切歌时实时刷新翻译开关状态
        appContext?.let { ctx ->
            val isEnabled = ctx.prefs().get(Config.ENABLE_TRANSLATION)
            YLog.debug("刷新切歌配置 - 翻译显示: $isEnabled")
            provider?.player?.setDisplayTranslation(isEnabled)
        }

        val songId = path?.hashCode()?.toString() ?: realId.toString()

        if (errorBlacklist.contains(songId)) {
            YLog.warn("⚠️ 检测到该歌曲在黑名单中 (曾导致崩溃)，跳过歌词获取: $title")
            return
        }

        val baseSong = Song(
            id = songId,
            name = title,
            artist = artist,
            duration = duration
        )

        // 立即发送歌曲信息（清除旧歌词）
        provider?.player?.setSong(baseSong)

        // 2. 启动新任务
        searchJob = scope.launch {
            try {
                if (!isActive) return@launch

                val lyricLines = LyricUtil.getLyricLines(
                    rawPath = path,
                    title = title,
                    artist = artist,
                    duration = duration
                )

                // 3. 一致性检查：防止网络延迟导致旧歌词覆盖新歌
                if (!isActive) {
                    // 注意：如果是取消异常，通常不会执行到这里，而是直接跳到 catch
                    YLog.debug("🛑 任务非活跃，停止处理: $title")
                    return@launch
                }

                // 双重校验：确保当前全局的歌曲仍然是发请求时的那首
                val currentGlobalPath = lastPath
                val currentGlobalId = lastId
                val isStillCurrentSong = (path == currentGlobalPath) && (realId == currentGlobalId)

                if (!isStillCurrentSong) {
                    YLog.debug("🚫 忽略已过期的歌词结果: $title (当前播放: $currentGlobalPath)")
                    return@launch
                }

                if (!lyricLines.isNullOrEmpty()) {
                    baseSong.lyrics = lyricLines
                    provider?.player?.setSong(baseSong)
                    YLog.debug("✅ 歌词已更新并发送。行数: ${lyricLines.size}")
                } else {
                    YLog.debug("⚪ 最终未找到任何歌词: $title")
                }
            } catch (e: CancellationException) {
                // 4. 正确处理取消：不记录 Error，不加黑名单
                YLog.debug("⚠️ 搜索任务已取消: $title (用户可能切歌了)")
            } catch (t: Throwable) {
                // 5. 仅处理真正的异常
                errorBlacklist.add(songId)
                YLog.error("❌❌❌ 加载歌词时发生严重崩溃! 已将歌曲加入黑名单。原因: ${t.javaClass.simpleName} - ${t.message}", t)
                t.printStackTrace()
            }
        }
    }

    private fun handleStatusChange(intent: Intent) {
        val paused = intent.getBooleanExtra("paused", true)
        val isPlaying = !paused
        YLog.debug("播放状态变更: 暂停=$paused, 播放中=$isPlaying")

        provider?.player?.setPlaybackState(isPlaying)

        if (isPlaying) {
            startSyncAction()
        } else {
            stopSyncAction()
        }
    }
}