/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

tasks.register("scanAllApks") {
    group = "build"
    description = "Scan all APKs, extract versionName, and rename output files"

    doLast {
        // --- 1. 获取 Android SDK 路径 ---
        val sdkDir = run {
            val localProperties = File(project.rootDir, "local.properties")
            if (localProperties.exists()) {
                java.util.Properties().apply { load(localProperties.inputStream()) }
                    .getProperty("sdk.dir")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return@run File(it) }
            }

            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { System.getenv(it) }
                .firstOrNull { it.isNotBlank() }
                ?.let { return@run File(it) }

            throw GradleException("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")
        }

        // --- 2. 查找 aapt2 ---
        val aapt2 = run {
            val buildTools = File(sdkDir, "build-tools")
            val latestBuildTool = buildTools.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: throw GradleException("No build-tools found")

            val os = org.gradle.internal.os.OperatingSystem.current()
            val executable = File(latestBuildTool, if (os.isWindows) "aapt2.exe" else "aapt2")
            if (!executable.exists()) throw GradleException("aapt2 not found at $executable")
            executable
        }

        // --- 3. 准备输出目录 ---
        val outputDir = File(project.rootDir, ".outputs").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // --- 4. 遍历处理所有 APK ---
        project.rootDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .forEach { apkFile ->
                println("Processing: ${apkFile.name}")

                // 提取 versionName
                val versionName = run {
                    val proc =
                        ProcessBuilder(aapt2.absolutePath, "dump", "badging", apkFile.absolutePath)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()

                    val version = proc.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence()
                            .firstOrNull { it.contains("versionName='") }
                            ?.let { line ->
                                Regex("""versionName='([^']*)'""")
                                    .find(line)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.takeIf { it.isNotBlank() }
                            }
                    }
                    proc.waitFor()
                    version
                }

                // 生成新文件名
                val buildType = apkFile.parentFile.name
                val baseName = apkFile.nameWithoutExtension
                val cleanBaseName = if (baseName.contains("-$buildType")) {
                    baseName.substringBefore("-$buildType")
                } else {
                    baseName
                }

                val newFileName = buildString {
                    append(cleanBaseName)
                    versionName?.takeIf { it.isNotBlank() }?.let { append("-v$it") }
                    // append("-$buildType") // 可选：是否添加构建类型后缀
                    append(".").append(apkFile.extension)
                }

                // 复制到输出目录
                val outDir = File(outputDir, apkFile.parentFile.name).apply { mkdirs() }
                val outFile = File(outDir, newFileName)
                apkFile.copyTo(outFile, overwrite = true)

                println("✅ Saved as: ${outFile.relativeTo(project.rootDir)}")
            }

        println("\n🎯 All APKs processed successfully!")
    }
}