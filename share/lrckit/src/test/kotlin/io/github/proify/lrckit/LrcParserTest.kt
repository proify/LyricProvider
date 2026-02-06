/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("RemoveRedundantBackticks", "NonAsciiCharacters", "GroovyUnusedDeclaration")

package io.github.proify.lrckit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import kotlin.test.Test

class LrcParserTest {

    @Test
    fun testSpecialLyrics() {
        val raw = readFile("eva.json")
        val lrc =
            Json.parseToJsonElement(raw).jsonObject["lrc"]?.jsonPrimitive?.contentOrNull
        val result = EnhanceLrcParser.parse(lrc)
        result.lines.forEach {
            println("$it")
        }

    }


    @Test
    fun testLrcELRCDiff() {
        fun testLrc() {
            val raw = readFile("jay.lrc")
            val result = EnhanceLrcParser.parse(raw)

            println("metadata:")
            result.metadata.forEach {
                println("${it.key}: ${it.value}")
            }
            println("-".repeat(100))
            println("lines:")
            println("size: ${result.lines.size}")
            result.lines.forEach {
                println("$it")
            }
        }

        fun testELrc() {
            val raw = readFile("jay.lrc")
            val result = EnhanceLrcParser.parse(raw)

            println("metadata:")
            result.metadata.forEach {
                println("${it.key}: ${it.value}")
            }
            println("-".repeat(100))
            println("lines:")
            println("size: ${result.lines.size}")
            result.lines.forEach {
                println("$it")
            }
        }

        println("lrc:")
        testLrc()
        println("-".repeat(100))
        println("elrc:")
        testELrc()
    }

    @Test
    fun testLrc() {
        val raw = readFile("jay.lrc")
        val result = EnhanceLrcParser.parse(raw)

        println("metadata:")
        result.metadata.forEach {
            println("${it.key}: ${it.value}")
        }
        println("-".repeat(100))
        println("lines:")
        println("size: ${result.lines.size}")
        result.lines.forEach {
            println("$it")
        }
    }

    @Test
    fun testELrc() {
        val raw = readFile("jay.elrc")
        val result = EnhanceLrcParser.parse(raw)

        println("metadata:")
        result.metadata.forEach {
            println("${it.key}: ${it.value}")
        }
        println("-".repeat(100))
        println("lines:")
        println("size: ${result.lines.size}")
        result.lines.forEach {
            println("$it")
        }
    }

    @Test
    fun `测试标准解析与小时级支持`() {
        val lrc = "[01:02:03.45]测试歌词"
        val result = EnhanceLrcParser.parse(lrc)

        assertEquals(1, result.lines.size)
        // 1h = 3600000, 2m = 120000, 3s = 3000, 45 = 450ms
        assertEquals(3723450L, result.lines[0].begin)
        assertEquals("测试歌词", result.lines[0].text)
    }

    @Test
    fun `测试多时间标签拆分`() {
        val lrc = "[00:10.00][00:20.00]重复歌词"
        val result = EnhanceLrcParser.parse(lrc)

        assertEquals(2, result.lines.size)
        assertEquals(10000L, result.lines[0].begin)
        assertEquals(20000L, result.lines[1].begin)
        assertEquals("重复歌词", result.lines[0].text)
        assertEquals("重复歌词", result.lines[1].text)
    }

    @Test
    fun `测试逐字解析与时长修正`() {
        val lrc = "[00:01.00]<00:01.00>生<00:01.50>命<00:02.00>"
        val result = EnhanceLrcParser.parse(lrc)

        val line = result.lines[0]
        assertEquals(1000L, line.begin)
        assertEquals(2000L, line.end)
        assertEquals(1000L, line.duration)
        assertEquals(2, line.words?.size)

        assertEquals("生", line.words?.get(0)?.text)
        assertEquals(500L, line.words?.get(0)?.duration)
        assertEquals("命", line.words?.get(1)?.text)
        assertEquals(500L, line.words?.get(1)?.duration)
    }

    @Test
    fun `测试背景音合并_同时间戳模式`() {
        val lrc = """
            [00:05.00]主唱歌词
            [00:05.00](背景和声)
        """.trimIndent()

        val result = EnhanceLrcParser.parse(lrc)

        // 应该被合并成一行，而不是两行
        assertEquals(1, result.lines.size)
        assertEquals("主唱歌词", result.lines[0].text)
        assertEquals("(背景和声)", result.lines[0].secondary)
    }

    @Test
    fun `测试背景音合并_显式BG标签模式`() {
        val lrc = """
            [00:05.00]v1: <00:05.00>主唱<00:06.00>
            [bg: <00:05.50>背景逐字<00:07.00>]
        """.trimIndent()

        val result = EnhanceLrcParser.parse(lrc)

        val line = result.lines[0]
        assertNotNull(line.secondaryWords)
        assertEquals("背景逐字", line.secondary)
        // 关键：行结束时间应该被背景音撑开
        assertEquals(7000L, line.end)
        assertEquals(2000L, line.duration)
    }

    @Test
    fun `测试元数据解析`() {
        val lrc = """
            [ti: 歌名]
            [ar: 歌手]
            [00:01.00]歌词内容
        """.trimIndent()

        val result = EnhanceLrcParser.parse(lrc)
        assertEquals("歌名", result.metadata["ti"])
        assertEquals("歌手", result.metadata["ar"])
    }

    @Test
    fun `测试空数据与非法数据稳定性`() {
        EnhanceLrcParser.parse("")
        EnhanceLrcParser.parse(null)
        EnhanceLrcParser.parse("[invalid] 123")
    }

    private fun readFile(path: String): String {
        return javaClass.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText() ?: ""
    }
}