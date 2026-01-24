/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.provider.common.util

import android.util.Log
import java.lang.reflect.Array

object ObjectUtils {

    private const val TAG = "ObjectUtils"
    private const val MAX_DEPTH = 5 // 防止过深递归

    /**
     * 打印对象的字段和方法信息到 Log（支持递归）
     */
    fun print(
        obj: Any?,
        tag: String? = null,
        logLevel: Int = Log.DEBUG,
        prefix: List<String>? = null,
        printList: Boolean = false // 保留参数，但逻辑已内建
    ) {
        if (obj == null) {
            logMessage(tag ?: TAG, logLevel, "Object is null")
            return
        }
        val logTag = tag ?: TAG
        val className = obj.javaClass.simpleName

        try {
            val visited = mutableSetOf<Any>()
            logMessage(logTag, logLevel, "╔═══════════════════════════════════════════")
            logMessage(logTag, logLevel, "║ Class: ${obj.javaClass.name}")
            logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")

            // 打印字段
            val fields = obj.javaClass.fields
            if (fields.isNotEmpty()) {
                logMessage(logTag, logLevel, "║ Fields (${fields.size}):")
                for (field in fields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(obj)
                        val formatted =
                            formatValueRecursive(value, visited, depth = 0, indent = "║   ")
                        logMessage(logTag, logLevel, "║   ${field.name}: $formatted")
                    } catch (e: IllegalAccessException) {
                        logMessage(logTag, Log.WARN, "║   ${field.name}: <inaccessible>")
                    } catch (e: Exception) {
                        logMessage(logTag, Log.ERROR, "║   ${field.name}: <error: ${e.message}>")
                    }
                }
                logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")
            } else {
                logMessage(logTag, logLevel, "║ No fields found")
                logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")
            }

            // 打印无参方法
            val declaredMethods = obj.javaClass.methods
            val noArgMethods = declaredMethods.filter { it.parameterCount == 0 }
            if (noArgMethods.isNotEmpty()) {
                logMessage(logTag, logLevel, "║ No-argument Methods (${noArgMethods.size}):")
                for (method in noArgMethods) {
                    if (method.name.startsWith("access$")) continue
                    if (prefix != null && !prefix.any { method.name.startsWith(it) }) continue

                    method.isAccessible = true
                    try {
                        val value = method.invoke(obj)
                        val formatted =
                            formatValueRecursive(value, visited, depth = 0, indent = "║   ")
                        logMessage(logTag, logLevel, "║   ${method.name}(): $formatted")
                    } catch (e: IllegalAccessException) {
                        logMessage(logTag, Log.WARN, "║   ${method.name}(): <inaccessible>")
                    } catch (e: Exception) {
                        logMessage(logTag, Log.ERROR, "║   ${method.name}(): <error: ${e.message}>")
                    }
                }
            } else {
                logMessage(logTag, logLevel, "║ No no-argument methods found")
            }

            logMessage(logTag, logLevel, "╚═══════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(logTag, "Error while printing object $className", e)
        }
    }

    fun printSimple(obj: Any, tag: String? = null) {
        val logTag = tag ?: TAG
        val className = obj.javaClass.simpleName

        try {
            val fields = obj.javaClass.declaredFields
            if (fields.isEmpty()) {
                logMessage(logTag, Log.DEBUG, "$className: No fields")
                return
            }

            val visited = mutableSetOf<Any>()
            val fieldValues = fields.joinToString(", ") { field ->
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    "${field.name}=${formatValueRecursive(value, visited, depth = 0)}"
                } catch (e: Exception) {
                    "${field.name}=<error>"
                }
            }

            logMessage(logTag, Log.DEBUG, "$className { $fieldValues }")

        } catch (e: Exception) {
            Log.e(logTag, "Error while printing simple object $className", e)
        }
    }

    /**
     * 递归格式化值，支持嵌套对象、集合、数组
     */
    private fun formatValueRecursive(
        value: Any?,
        visited: MutableSet<Any>,
        depth: Int,
        indent: String = ""
    ): String {
        if (depth > MAX_DEPTH) return "<max depth reached>"

        return when {
            value == null -> "null"
            value is String -> "\"$value\""
            value is Boolean || value is Number || value is Char -> value.toString()
            value.javaClass.isArray -> formatArray(value, visited, depth, indent)
            value is Collection<*> -> formatCollection(value, visited, depth, indent)
            value is Map<*, *> -> formatMap(value, visited, depth, indent)
            else -> {
                // 自定义对象：防止循环引用
                if (!visited.add(value)) {
                    return "${value.javaClass.simpleName}@${Integer.toHexString(value.hashCode())} <circular>"
                }

                try {
                    val fields = value.javaClass.declaredFields
                    if (fields.isEmpty()) {
                        "${value.javaClass.simpleName}@${Integer.toHexString(value.hashCode())}"
                    } else {
                        val sb = StringBuilder("${value.javaClass.simpleName} {\n")
                        val nextIndent = "$indent  "
                        for (field in fields) {
                            field.isAccessible = true
                            try {
                                val fieldValue = field.get(value)
                                val formatted =
                                    formatValueRecursive(fieldValue, visited, depth + 1, nextIndent)
                                sb.append("$nextIndent${field.name} = $formatted\n")
                            } catch (e: Exception) {
                                sb.append("$nextIndent${field.name} = <error>\n")
                            }
                        }
                        sb.append("$indent}")
                        sb.toString()
                    }
                } finally {
                    visited.remove(value) // 允许其他路径再次访问（非严格 DAG）
                }
            }
        }
    }

    private fun formatArray(
        array: Any,
        visited: MutableSet<Any>,
        depth: Int,
        indent: String
    ): String {
        val len = Array.getLength(array)
        if (len == 0) return "[]"
        val sb = StringBuilder("[\n")
        val nextIndent = "$indent  "
        for (i in 0 until len) {
            val elem = Array.get(array, i)
            val formatted = formatValueRecursive(elem, visited, depth + 1, nextIndent)
            sb.append("$nextIndent$formatted,\n")
        }
        sb.append("$indent]")
        return sb.toString()
    }

    private fun formatCollection(
        collection: Collection<*>,
        visited: MutableSet<Any>,
        depth: Int,
        indent: String
    ): String {
        if (collection.isEmpty()) return "[]"
        val sb = StringBuilder("[\n")
        val nextIndent = "$indent  "
        for (item in collection) {
            val formatted = formatValueRecursive(item, visited, depth + 1, nextIndent)
            sb.append("$nextIndent$formatted,\n")
        }
        sb.append("$indent]")
        return sb.toString()
    }

    private fun formatMap(
        map: Map<*, *>,
        visited: MutableSet<Any>,
        depth: Int,
        indent: String
    ): String {
        if (map.isEmpty()) return "{}"
        val sb = StringBuilder("{\n")
        val nextIndent = "$indent  "
        for ((key, value) in map) {
            val keyStr = formatValueRecursive(key, visited, depth + 1, "")
            val valueStr = formatValueRecursive(value, visited, depth + 1, nextIndent)
            sb.append("$nextIndent$keyStr: $valueStr,\n")
        }
        sb.append("$indent}")
        return sb.toString()
    }

    private fun logMessage(tag: String, level: Int, message: String) {
        when (level) {
            Log.VERBOSE -> Log.v(tag, message)
            Log.DEBUG -> Log.d(tag, message)
            Log.INFO -> Log.i(tag, message)
            Log.WARN -> Log.w(tag, message)
            Log.ERROR -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    fun toString(obj: Any): String {
        val visited = mutableSetOf<Any>()
        return buildString {
            appendLine("Class: ${obj.javaClass.name}")
            val fields = obj.javaClass.declaredFields
            if (fields.isNotEmpty()) {
                appendLine("Fields:")
                for (field in fields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(obj)
                        val formatted =
                            formatValueRecursive(value, visited, depth = 0, indent = "  ")
                        appendLine("  ${field.name}: $formatted")
                    } catch (e: Exception) {
                        appendLine("  ${field.name}: <error: ${e.message}>")
                    }
                }
            }
        }
    }
}