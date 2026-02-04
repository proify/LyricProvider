package io.github.proify.lyricon.paprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.charset.Charset
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern

/**
 * LRC 歌词解析与文件读取工具
 *
 * 支持标准 LRC 和 ELRC (逐字歌词) 格式。
 */
object LrcParser {

    // 匹配行首时间: [mm:ss.xx] 或 [mm:ss.xxx]
    private val LRC_LINE_REGEX = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(\\.(\\d{1,3}))?]")
    
    // 匹配字标签: <mm:ss.xx> 或 <mm:ss.xxx>
    private val WORD_TAG_REGEX = Pattern.compile("<(\\d{1,2}):(\\d{1,2})(\\.(\\d{1,3}))?>")

    /**
     * 解析 LRC 文本内容为结构化数据
     */
    fun parseLrcContent(raw: String?): List<RichLyricLine> {
        val entries = mutableListOf<RichLyricLine>()
        if (raw.isNullOrBlank()) {
            return entries
        }

        try {
            val startTs = System.currentTimeMillis()
            
            // 1. 初步解析：提取所有 [time] rawContent
            data class TempLine(val time: Long, val rawContent: String)
            val tempLines = mutableListOf<TempLine>()

            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach

                // 提取行首所有时间标签
                val matcher = LRC_LINE_REGEX.matcher(trimmed)
                val times = mutableListOf<Long>()
                var lastEndIndex = 0

                while (matcher.find()) {
                    times.add(parseTime(matcher.group(1), matcher.group(2), matcher.group(4)))
                    lastEndIndex = matcher.end()
                }

                if (times.isNotEmpty()) {
                    // 剩余部分即为内容（可能包含 <time> 标签）
                    val content = trimmed.substring(lastEndIndex).trim()
                    times.forEach { startTime ->
                        tempLines.add(TempLine(startTime, content))
                    }
                }
            }

            // 2. 分组处理：按时间排序 -> 识别主歌词(带标签)与翻译
            val grouped = tempLines.groupBy { it.time }.toSortedMap()
            
            // 临时存储处理后的结果
            class ProcessedLine(
                val time: Long, 
                val text: String, 
                val translation: String?,
                val words: List<LyricWord>
            )
            val processedList = mutableListOf<ProcessedLine>()

            grouped.forEach { (time, lines) ->
                var mainRaw = ""
                var transRaw: String? = null

                // 策略：优先找带 < > 标签的行作为主歌词
                // 虽然翻译也有标签，但通常主歌词排在前面（lines 保留了文件顺序）
                // 这里的 tagLineIndex 会返回第一个带标签的行（即英文行）
                val tagLineIndex = lines.indexOfFirst { it.rawContent.contains("<") && it.rawContent.contains(">") }
                
                if (tagLineIndex != -1) {
                    // 找到了逐字歌词行
                    mainRaw = lines[tagLineIndex].rawContent
                    // 剩下的行中，第一行非空行作为翻译
                    transRaw = lines.filterIndexed { index, _ -> index != tagLineIndex }
                        .firstOrNull { it.rawContent.isNotBlank() }?.rawContent
                } else {
                    // 没标签，按老规矩：第一行主歌词，第二行翻译
                    if (lines.isNotEmpty()) mainRaw = lines[0].rawContent
                    if (lines.size > 1 && lines[1].rawContent.isNotBlank()) transRaw = lines[1].rawContent
                }

                // 3. 解析主歌词中的逐字数据
                val (cleanText, words) = parseLyricWords(mainRaw)
                
                // 清理翻译中的潜在标签 (翻译行也可能带有 <time>，需要剥离)
                val cleanTrans = transRaw?.replace(Regex("<[^>]+>"), "")

                processedList.add(ProcessedLine(time, cleanText, cleanTrans, words))
            }

            // 4. 构建最终 RichLyricLine
            if (processedList.isNotEmpty()) {
                
                processedList.forEachIndexed { i, p ->
                    val nextTime = if (i < processedList.size - 1) {
                        processedList[i + 1].time
                    } else {
                        p.time + 5000 // 最后一行默认 5s
                    }
                    val duration = nextTime - p.time

                    // 修正字的 duration
                    val finalWords = p.words.map { word ->
                        if (word.duration <= 0) {
                             // 如果字没有解析出持续时间（通常是行末最后一个字），使用 (行结束时间 - 字开始时间)
                             val fixedDur = if (nextTime > word.begin) nextTime - word.begin else 0L
                             // 修复：使用 begin 而不是 start
                             word.copy(duration = fixedDur, end = word.begin + fixedDur)
                        } else {
                            word
                        }
                    }

                    entries.add(
                        RichLyricLine(
                            begin = p.time,
                            end = nextTime,
                            duration = duration,
                            text = p.text.ifEmpty { "..." },
                            translation = p.translation,
                            words = finalWords // 这里传入的是 List<LyricWord>，兼容 nullable 字段
                        )
                    )
                }
            }
            
            // 修复：List<LyricWord>? 需要安全调用
            val wordCount = entries.count { !it.words.isNullOrEmpty() }
            YLog.debug("[解析器] 解析成功。行数: ${entries.size}, 逐字行数: $wordCount")

        } catch (e: Exception) {
            YLog.error("[解析器] 异常: ${e.message}", e)
        }
        return entries
    }

    /**
     * 解析形如 "<00:00.000>字<00:00.500>..." 的字符串
     * 返回 (纯文本, 字列表)
     */
    private fun parseLyricWords(raw: String): Pair<String, List<LyricWord>> {
        if (!raw.contains("<") || !raw.contains(">")) {
            return raw to emptyList()
        }

        val words = mutableListOf<LyricWord>()
        val sb = StringBuilder()
        
        val matcher = WORD_TAG_REGEX.matcher(raw)
        
        // 记录上一个标签的信息
        var lastTime = -1L
        var lastEndIndex = 0

        while (matcher.find()) {
            val currentTime = parseTime(matcher.group(1), matcher.group(2), matcher.group(4))
            val startIndex = matcher.start()

            // 如果这不是第一个标签，那么上一个标签到这里之间的内容就是上一个字
            if (lastTime != -1L) {
                val text = raw.substring(lastEndIndex, startIndex)
                if (text.isNotEmpty()) {
                    // 修复：使用 begin 而不是 start
                    words.add(LyricWord(
                        begin = lastTime,
                        end = currentTime,
                        duration = currentTime - lastTime,
                        text = text
                    ))
                    sb.append(text)
                }
            }

            lastTime = currentTime
            lastEndIndex = matcher.end()
        }

        // 处理最后一个标签之后的内容
        if (lastTime != -1L && lastEndIndex < raw.length) {
            val text = raw.substring(lastEndIndex)
            if (text.isNotEmpty()) {
                // 使用 begin 而不是 start
                words.add(LyricWord(
                    begin = lastTime,
                    end = lastTime, // 暂定，外层修正
                    duration = 0,   // 暂定
                    text = text
                ))
                sb.append(text)
            }
        }

        if (words.isEmpty() && sb.isEmpty()) {
             return raw.replace(Regex("<[^>]+>"), "") to emptyList()
        }

        return sb.toString() to words
    }

    /**
     * 将歌词保存为本地 LRC 文件 (支持保存逐字标签)
     */
    fun saveToLrcFile(audioFile: File, lines: List<RichLyricLine>): Boolean {
        val lrcPath = audioFile.absolutePath.substringBeforeLast('.') + ".lrc"
        val lrcFile = File(lrcPath)

        YLog.debug("[写入器] 准备写入本地 LRC (v2): $lrcPath")

        return try {
            val sb = StringBuilder()
            lines.forEach { line ->
                val lineTimeStr = formatLrcTimestamp(line.begin)
                
                // 1. 写入主歌词
                sb.append(lineTimeStr)
                
                // 修复：空安全检查，words 可能是 null
                val currentWords = line.words
                if (!currentWords.isNullOrEmpty()) {
                    // 格式：[mm:ss.xx]<mm:ss.xxx>字<mm:ss.xxx>字...
                    currentWords.forEach { word ->
                        // 修复：使用 begin 而不是 start
                        sb.append(formatWordTimestamp(word.begin))
                        sb.append(word.text)
                    }
                } else {
                    sb.append(line.text)
                }
                sb.append("\n")
                
                // 2. 写入翻译 (如果有)
                if (!line.translation.isNullOrBlank()) {
                    sb.append(lineTimeStr).append(line.translation).append("\n")
                }
            }

            lrcFile.writeText(sb.toString(), Charset.forName("UTF-8"))
            YLog.debug("✅ [写入器] 写入成功")
            true
        } catch (e: Exception) {
            YLog.error("❌ [写入器] 写入失败: ${e.message}", e)
            false
        }
    }

    private fun parseTime(minStr: String?, secStr: String?, msStr: String?): Long {
        val min = minStr?.toLongOrNull() ?: 0L
        val sec = secStr?.toLongOrNull() ?: 0L
        val ms = if (msStr == null) {
            0L
        } else {
            var s = msStr
            while (s.length < 3) s += "0"
            if (s.length > 3) s = s.substring(0, 3)
            s.toLong()
        }
        return min * 60000 + sec * 1000 + ms
    }

    private fun formatLrcTimestamp(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val mm = totalSeconds / 60
        val ss = totalSeconds % 60
        val ms = (timeMs % 1000) / 10 
        return String.format("[%02d:%02d.%02d]", mm, ss, ms)
    }
    
    private fun formatWordTimestamp(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val mm = totalSeconds / 60
        val ss = totalSeconds % 60
        val ms = timeMs % 1000
        return String.format("<%02d:%02d.%03d>", mm, ss, ms)
    }

    fun parseEmbeddedTag(audioFile: File): String? {
        if (!audioFile.exists() || !audioFile.canRead()) return null
        return try {
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return null
            tag.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
                ?: tag.getFirst(FieldKey.COMMENT)?.takeIf { it.isNotBlank() && isLikelyLrc(it) }
        } catch (e: Throwable) {
            null
        }
    }

    fun readExternalLrcFile(audioFile: File): String? {
        val lrcPath = audioFile.absolutePath.substringBeforeLast('.') + ".lrc"
        val lrcFile = File(lrcPath)
        if (lrcFile.exists() && lrcFile.canRead()) {
            return runCatching { lrcFile.readText(Charset.forName("UTF-8")) }.getOrNull()
        }
        return null
    }

    private fun isLikelyLrc(text: String): Boolean {
        return text.contains("[0") || text.contains("[1")
    }
}