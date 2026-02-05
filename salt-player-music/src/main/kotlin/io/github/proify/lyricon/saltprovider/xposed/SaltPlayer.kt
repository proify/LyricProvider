/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object SaltPlayer : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader
    private var provider: LyriconProvider? = null
    
    // Flyme 状态栏歌词的 flag 值（来自椒盐音乐作者 Moriafly）
    private const val FLAG_ALWAYS_SHOW_TICKER = 0x1000000
    private const val FLAG_ONLY_UPDATE_TICKER = 0x2000000
    
    // 歌词超时控制
    private const val LYRIC_TIMEOUT_MS = 10000L
    private var lastLyricTime = 0L
    private var isLyricActive = false

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        
        YLog.info("=== Salt Player Provider initializing ===")
        YLog.info("Package: ${application.packageName}")
        YLog.info("FLAG_ALWAYS_SHOW_TICKER = 0x${FLAG_ALWAYS_SHOW_TICKER.toString(16)}")
        YLog.info("FLAG_ONLY_UPDATE_TICKER = 0x${FLAG_ONLY_UPDATE_TICKER.toString(16)}")
        
        initProvider()
        startHooks()
        
        YLog.info("=== Salt Player Provider initialized successfully ===")
    }

    private fun initProvider() {
        try {
            val helper = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = if (Constants.ICON.isNotEmpty()) {
                    ProviderLogo.fromBase64(Constants.ICON)
                } else {
                    null
                }
            )
            
            helper.register()
            this.provider = helper
            
            YLog.info("✓ Lyricon Provider registered")
        } catch (e: Exception) {
            YLog.error("✗ Failed to initialize Lyricon Provider", e)
        }
    }

    private fun startHooks() {
        YLog.info("Installing hooks...")
        hookNotificationManager()
        YLog.info("✓ All hooks installed")
    }

    /**
     * Hook NotificationManager.notify() 方法
     * 记录所有通知以便调试
     */
    private fun hookNotificationManager() {
        try {
            // Hook notify(int, Notification)
            NotificationManager::class.java.hook {
                injectMember {
                    method {
                        name = "notify"
                        param(Int::class.java, Notification::class.java)
                    }
                    beforeHook {
                        try {
                            val notificationId = args(0).cast<Int>() ?: return@beforeHook
                            val notification = args(1).cast<Notification>() ?: return@beforeHook
                            
                            // 记录所有通知
                            YLog.info(">>> Notification detected: id=$notificationId")
                            logNotificationDetails(notification)
                            
                            // 处理歌词
                            processNotification(notification)
                        } catch (e: Exception) {
                            YLog.error("Error in notify(int, Notification)", e)
                        }
                    }
                }
            }
            
            // Hook notify(String, int, Notification)
            NotificationManager::class.java.hook {
                injectMember {
                    method {
                        name = "notify"
                        param(String::class.java, Int::class.java, Notification::class.java)
                    }
                    beforeHook {
                        try {
                            val tag = args(0).cast<String?>()
                            val notificationId = args(1).cast<Int>() ?: return@beforeHook
                            val notification = args(2).cast<Notification>() ?: return@beforeHook
                            
                            // 记录所有通知
                            YLog.info(">>> Notification detected: tag=$tag, id=$notificationId")
                            logNotificationDetails(notification)
                            
                            // 处理歌词
                            processNotification(notification)
                        } catch (e: Exception) {
                            YLog.error("Error in notify(String, int, Notification)", e)
                        }
                    }
                }
            }
            
            YLog.info("✓ Hooked NotificationManager.notify()")
        } catch (e: Exception) {
            YLog.error("✗ Failed to hook NotificationManager", e)
        }
    }

    /**
     * 记录通知详细信息
     */
    private fun logNotificationDetails(notification: Notification) {
        try {
            // 记录 flags
            YLog.debug("  flags = 0x${notification.flags.toString(16)}")
            
            // 记录 tickerText
            val tickerText = notification.tickerText?.toString()
            if (tickerText != null) {
                YLog.info("  tickerText = \"$tickerText\"")
            } else {
                YLog.debug("  tickerText = null")
            }
            
            // 检查 Flyme 歌词 flags
            val hasShowFlag = (notification.flags and FLAG_ALWAYS_SHOW_TICKER) != 0
            val hasUpdateFlag = (notification.flags and FLAG_ONLY_UPDATE_TICKER) != 0
            YLog.debug("  FLAG_ALWAYS_SHOW_TICKER = $hasShowFlag")
            YLog.debug("  FLAG_ONLY_UPDATE_TICKER = $hasUpdateFlag")
            
            // 记录 extras
            try {
                val extras = notification.extras
                if (extras != null && !extras.isEmpty) {
                    YLog.debug("  extras:")
                    for (key in extras.keySet()) {
                        val value = extras.get(key)
                        YLog.debug("    $key = $value")
                    }
                }
            } catch (e: Exception) {
                YLog.debug("  Could not read extras: ${e.message}")
            }
        } catch (e: Exception) {
            YLog.error("Error logging notification details", e)
        }
    }

    /**
     * 处理通知中的歌词
     */
    private fun processNotification(notification: Notification) {
        try {
            // 获取 tickerText
            val tickerText = notification.tickerText?.toString()
            
            if (tickerText.isNullOrBlank()) {
                return
            }
            
            // 检查是否是 Flyme 歌词通知
            val hasShowFlag = (notification.flags and FLAG_ALWAYS_SHOW_TICKER) != 0
            val hasUpdateFlag = (notification.flags and FLAG_ONLY_UPDATE_TICKER) != 0
            
            // 根据 Moriafly 的文档，歌词通知应该有这两个 flag
            // 同时 extras 中应该有 ticker_icon 和 ticker_icon_switch
            val hasTickerIcon = try {
                notification.extras?.containsKey("ticker_icon") == true
            } catch (e: Exception) {
                false
            }
            
            YLog.debug("  Lyric check: hasShowFlag=$hasShowFlag, hasUpdateFlag=$hasUpdateFlag, hasTickerIcon=$hasTickerIcon")
            
            // 只要有 Flyme 歌词 flag 就认为是歌词（不强制要求 ticker_icon，因为可能不同版本实现不同）
            if (hasShowFlag || hasUpdateFlag) {
                YLog.info(">>> LYRIC FOUND: \"$tickerText\"")
                handleLyricData(tickerText, true)
            } else if (hasTickerIcon && tickerText.isNotEmpty()) {
                // 备用检测：如果有 ticker_icon 也可能是歌词
                YLog.info(">>> LYRIC FOUND (via ticker_icon): \"$tickerText\"")
                handleLyricData(tickerText, true)
            }
        } catch (e: Exception) {
            YLog.error("Error processing notification", e)
        }
    }

    /**
     * 处理拦截到的歌词数据
     */
    private fun handleLyricData(lyric: String, playing: Boolean) {
        try {
            if (lyric.isBlank()) {
                return
            }
            
            YLog.info(">>> Forwarding to Lyricon: \"$lyric\"")
            
            // 更新最后收到歌词的时间
            lastLyricTime = System.currentTimeMillis()
            
            // 如果之前已停止，现在重新激活
            if (!isLyricActive) {
                isLyricActive = true
                YLog.info("✓ Lyric service reactivated")
            }
            
            // 发送播放状态
            provider?.player?.setPlaybackState(playing)
            
            // 发送歌词文本
            provider?.player?.sendText(lyric)
            
            YLog.info("✓ Lyric forwarded successfully")
            
            // 启动超时检查
            checkLyricTimeout()
        } catch (e: Exception) {
            YLog.error("✗ Error forwarding lyric", e)
        }
    }
    
    /**
     * 检查歌词超时
     * 如果超过 3 秒没有新歌词，停止发送状态栏歌词
     */
    private fun checkLyricTimeout() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val currentTime = System.currentTimeMillis()
            val timeSinceLastLyric = currentTime - lastLyricTime
            
            if (timeSinceLastLyric >= LYRIC_TIMEOUT_MS && isLyricActive) {
                isLyricActive = false
                YLog.info("⏱ Lyric timeout (${timeSinceLastLyric}ms) - stopping lyric service until next lyric")
                
                // 发送空文本来清除状态栏歌词
                try {
                    provider?.player?.setPlaybackState(false)
                    provider?.player?.sendText("")
                } catch (e: Exception) {
                    YLog.error("Error clearing lyric on timeout", e)
                }
            }
        }, LYRIC_TIMEOUT_MS)
    }
}
