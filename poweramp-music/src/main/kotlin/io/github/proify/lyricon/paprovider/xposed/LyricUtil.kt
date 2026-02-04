package io.github.proify.lyricon.paprovider.xposed

import android.content.Context
import android.os.Environment
import com.highcapable.yukihookapi.hook.factory.prefs
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.paprovider.ui.Config
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 歌词获取策略工具类
 * 已集成配置读取：
 * - [Config.ENABLE_NET_SEARCH]
 * - [Config.ENABLE_AUTO_SAVE]
 */
object LyricUtil {

    private var context: Context? = null
    
    private val cloudLyrics by lazy {
        try {
            YLog.debug("正在初始化 CloudLyrics 引擎...")
            CloudLyrics()
        } catch (t: Throwable) {
            YLog.error("🚨 CloudLyrics 初始化失败!", t)
            throw t
        }
    }

    fun init(ctx: Context) {
        this.context = ctx
    }

    private fun resolvePath(rawPath: String): String {
        if (rawPath.startsWith("primary/")) {
            return Environment.getExternalStorageDirectory().absolutePath + "/" + rawPath.removePrefix("primary/")
        }
        return rawPath
    }

    suspend fun getLyricLines(
        rawPath: String?,
        title: String,
        artist: String,
        duration: Long
    ): List<RichLyricLine>? {
        
        val ctx = context ?: return null
        var audioFile: File? = null
        
        // --- 本地策略 ---
        if (rawPath != null) {
            try {
                val absPath = resolvePath(rawPath)
                audioFile = File(absPath)
                
                if (audioFile.exists()) {
                    val tagLyric = LrcParser.parseEmbeddedTag(audioFile)
                    if (!tagLyric.isNullOrBlank()) {
                        val lines = LrcParser.parseLrcContent(tagLyric)
                        if (lines.isNotEmpty()) {
                            YLog.debug("✅ 命中内嵌歌词")
                            return lines
                        }
                    }

                    val lrcContent = LrcParser.readExternalLrcFile(audioFile)
                    if (!lrcContent.isNullOrBlank()) {
                        val lines = LrcParser.parseLrcContent(lrcContent)
                        if (lines.isNotEmpty()) {
                            YLog.debug("✅ 命中本地LRC文件")
                            return lines
                        }
                    }
                }
            } catch (e: Exception) {
                YLog.error("本地歌词读取发生异常", e)
            }
        }

        // --- 配置检查：是否允许云端搜索 ---
        // 修复：通过 ctx 显式调用 prefs()
        val isNetSearchEnabled = ctx.prefs().get(Config.ENABLE_NET_SEARCH)
        
        if (!isNetSearchEnabled) {
            YLog.debug("🚫 [配置] 云端搜索已禁用，跳过搜索")
            return null
        }

        // --- 云端策略 ---
        YLog.debug(">>> [策略3] 启动云端搜索: $title - $artist")
        val cloudResult = searchCloudLyrics(title, artist, duration)

        // --- 配置检查：是否允许自动保存 ---
        // 修复：通过 ctx 显式调用 prefs()
        val isAutoSaveEnabled = ctx.prefs().get(Config.ENABLE_AUTO_SAVE)

        if (!cloudResult.isNullOrEmpty() && isAutoSaveEnabled && audioFile != null && audioFile.exists()) {
            YLog.debug(">>> [缓存] 尝试将云端歌词保存到本地...")
            LrcParser.saveToLrcFile(audioFile, cloudResult)
        }

        return cloudResult
    }

    private suspend fun searchCloudLyrics(title: String, artist: String, duration: Long): List<RichLyricLine>? {
        return try {
            val engine = cloudLyrics

            val results = engine.search {
                this.trackName = title
                this.artistName = artist
                this.maxTotalResults = 5 
                this.perProviderLimit = 3
                prefer(score = 50) { _ -> true }
            }

            if (results.isEmpty()) {
                YLog.debug("⚪ 云端搜索结束，未找到匹配结果")
                return null
            }
            val bestMatch = results.first()
            YLog.debug("✅ 命中云端歌词: 源[${bestMatch.provider.id}]")
            bestMatch.lyrics.rich

        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            YLog.error("❌ 云端搜索执行异常", t)
            null
        }
    }
}