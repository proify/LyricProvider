/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:OptIn(DexKitExperimentalApi::class)

package io.github.proify.lyricon.spotifyprovider.xposed

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主 okhttp 请求头捕获器。
 *
 * Lyricon 通过 [SpotifyApi] 直接请求 Spotify 的 color-lyrics 接口，该接口要求携带宿主
 * 当前登录态的 `authorization`、`client-token`、`user-agent`、`x-client-id` 请求头，
 * 因此需要在宿主进程中“偷听”okhttp 请求头的构造过程。
 *
 * Spotify 9.1.78+ 对全量 dex（含 okhttp/5.x）做了 R8 混淆重命名，`okhttp3.Headers`
 * 明文类已不存在（直接按类名 Hook 会抛 [ClassNotFoundException]）。这里提供两条路径：
 *
 * 1. 明文路径：未混淆（旧版本）时直接定位 `okhttp3.Headers` 的 `String[]` 构造方法；
 * 2. DexKit 路径：混淆版本用 DexKit 扫描 dex，按“构造方法仅接收一个 `String[]`
 *    参数（键值交替的 header 数组）”的结构特征定位混淆后的 Headers 等价类再 Hook。
 *
 * 由于歌词提供器每次随宿主进程启动都要工作，而 R8 混淆只随宿主版本更新而变化，
 * 结构扫描的结果通过 [DexKitCacheBridge] 按“宿主版本号 + 安装时间”维度持久化：
 *
 * - 首次启动（宿主升级/清数据后）：DexKit 实扫一次并写入缓存；
 * - 之后每次启动：直接命中缓存（纯 SharedPreferences 读取 + 反射解析），
 *   不再创建 DexKit native 桥、不再扫描 dex，Hook 安装开销降到毫秒级。
 *
 * Headers 每次构造都携带完整的键值数组，捕获后只按 [SpotifyApi.keysRequired] 过滤，
 * 即使命中多个候选类（结构近似但并非 Headers）也不会污染请求头。
 */
object HeaderCapture {

    private const val TAG = "SpotifyProvider"

    /** 结果缓存文件名（写入宿主应用私有目录，缓存内容按宿主版本区分） */
    private const val CACHE_PREFS_NAME = "lyricon_spotify_dexkit_cache"

    /** 防止同一进程被重复初始化（对头捕获只安装一次即可） */
    private val installed = AtomicBoolean(false)

    /**
     * 在宿主 classLoader 中安装请求头捕获 Hook。
     *
     * @param context 宿主 Application（仅用于读写结果缓存，缓存不可用不影响功能）
     * @param classLoader 宿主应用类加载器
     * @param apkPath 宿主 APK 路径（DexKit 扫描用）
     */
    fun install(context: Context, classLoader: ClassLoader, apkPath: String) {
        if (!installed.compareAndSet(false, true)) return

        if (hookPlaintextHeaders(classLoader)) {
            YLog.info(tag = TAG, msg = "已按明文 okhttp3.Headers Hook 请求头构造")
            return
        }

        YLog.info(tag = TAG, msg = "宿主未暴露 okhttp3.Headers（R8 混淆），改用 DexKit 结构扫描")
        hookObfuscatedHeaders(context, classLoader, apkPath)
    }

    /**
     * 明文路径：直接解析 okhttp3.Headers 的构造方法并 Hook。
     *
     * @return true 表示 Hook 成功（旧版本/未混淆场景）
     */
    private fun hookPlaintextHeaders(classLoader: ClassLoader): Boolean {
        return try {
            val headersClass = Class.forName("okhttp3.Headers", false, classLoader)
            val constructor = headersClass.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Array<String>::class.java
            } ?: return false
            hookHeadersConstructor(constructor)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 混淆路径入口：优先走 DexKitCacheBridge 结果缓存，不可用时退化为一次性实扫。
     */
    private fun hookObfuscatedHeaders(
        context: Context,
        classLoader: ClassLoader,
        apkPath: String
    ) {
        try {
            ensureDexKitLoaded()
            if (!tryInitCacheBridge(context)) {
                YLog.warn(tag = TAG, msg = "缓存桥不可用（可能已被同进程其它模块初始化），本次直接实扫")
                hookByDirectScan(classLoader, apkPath)
                return
            }

            val appTag = resolveAppTag(context)
            try {
                hookWithCacheBridge(classLoader, apkPath, appTag)
            } catch (e: Throwable) {
                // 缓存数据损坏等异常：清掉该版本缓存并退化为实扫，保证功能可用
                YLog.warn(tag = TAG, msg = "DexKit 缓存路径异常，清缓存后直接实扫: ${e.message}")
                runCatching { DexKitCacheBridge.clearCache(appTag) }
                hookByDirectScan(classLoader, apkPath)
            }
        } catch (e: Throwable) {
            YLog.error(
                tag = TAG,
                msg = "DexKit 定位 Headers 失败，歌词请求将缺少登录请求头",
                e = e
            )
        }
    }

    /**
     * 缓存路径：查询结果按查询指纹自动读写缓存，命中时完全不创建 native 桥。
     */
    private fun hookWithCacheBridge(classLoader: ClassLoader, apkPath: String, appTag: String) {
        val listener = object : DexKitCacheBridge.CacheBridgeListener() {
            override fun onQuerySuccess(info: DexKitCacheBridge.QuerySuccessEvent) {
                val source = if (info.source == DexKitCacheBridge.ResultSource.CACHE) "缓存命中" else "实扫"
                YLog.debug(
                    tag = TAG,
                    msg = "DexKit 查询完成（$source）: kind=${info.queryKind}, 命中 ${info.matchCount} 条"
                )
            }

            override fun onQueryFailure(info: DexKitCacheBridge.QueryFailureEvent) {
                YLog.error(tag = TAG, msg = "DexKit 查询失败: kind=${info.queryKind}", e = info.error)
            }
        }
        DexKitCacheBridge.addListener(listener)
        try {
            val hooked = DexKitCacheBridge.create(appTag, apkPath).use { bridge ->
                // 首选：构造方法 + String[] 单参；若该版本确实没有（结构变动），
                // 精确查询会返回缓存的空结果，此时再走宽松签名扫描兜底
                val precise = bridge.getMethodsOrEmpty(headersConstructorQuery())
                val methods = if (precise.isNotEmpty()) {
                    precise
                } else {
                    bridge.getMethodsOrEmpty(stringArrayParameterQuery())
                }
                installHeaderHooks(classLoader, methods)
            }
            if (hooked == 0) {
                YLog.warn(
                    tag = TAG,
                    msg = "DexKit 未找到 String[] 构造方法的 Headers 等价类，歌词请求将缺少登录请求头"
                )
            }
        } finally {
            DexKitCacheBridge.removeListener(listener)
        }
    }

    /**
     * 无缓存路径：直接用 [DexKitBridge] 实扫一次（结果不持久化）。
     */
    private fun hookByDirectScan(classLoader: ClassLoader, apkPath: String) {
        val methods = DexKitBridge.create(apkPath).use { bridge ->
            val precise = bridge.findMethod(headersConstructorQuery())
            // 精确查询无结果时，退化为按参数签名扫描
            val matched = if (precise.isEmpty()) {
                bridge.findMethod(stringArrayParameterQuery())
            } else {
                precise
            }
            matched.map { it.toDexMethod() }
        }
        val hooked = installHeaderHooks(classLoader, methods)
        if (hooked == 0) {
            YLog.warn(
                tag = TAG,
                msg = "DexKit 未找到 String[] 构造方法的 Headers 等价类，歌词请求将缺少登录请求头"
            )
        }
    }

    /**
     * 精确查询：构造方法，且唯一参数为 `String[]`。
     *
     * okhttp Headers 的存储结构是“键值交替的 String[]”，R8 只会重命名类与成员，
     * 不会改变 `<init>([Ljava/lang/String;)V` 这样的方法描述符，因此结构可跨版本稳定匹配。
     */
    private fun headersConstructorQuery(): FindMethod = FindMethod().apply {
        matcher {
            name("<init>")
            paramTypes("java.lang.String[]")
        }
    }

    /**
     * 兜底查询：仅要求唯一参数为 `String[]`（命中多些无妨，捕获侧会按键名过滤）。
     */
    private fun stringArrayParameterQuery(): FindMethod = FindMethod().apply {
        matcher {
            paramTypes("java.lang.String[]")
        }
    }

    /** 把 DexKit 结果解析成可 Hook 的构造方法并全部 Hook（去重），返回成功数量 */
    private fun installHeaderHooks(classLoader: ClassLoader, methods: List<DexMethod>): Int {
        val constructors = methods.mapNotNull { method ->
            try {
                method.getConstructorInstance(classLoader).takeUnless { it.declaringClass.isInterface }
            } catch (_: Throwable) {
                null
            }
        }.distinct()

        if (constructors.isEmpty()) return 0
        constructors.forEach { constructor ->
            hookHeadersConstructor(constructor)
            YLog.debug(
                tag = TAG,
                msg = "已按结构 Hook Headers 候选类: ${constructor.declaringClass.name}"
            )
        }
        YLog.info(
            tag = TAG,
            msg = "DexKit 共定位 ${constructors.size} 个 Headers 候选类: ${
                constructors.joinToString { it.declaringClass.name }
            }"
        )
        return constructors.size
    }

    /** 统一 Hook 入口：构造完成后读取键值数组，过滤出需要的请求头 */
    private fun hookHeadersConstructor(constructor: Constructor<*>) {
        val sourceClass = constructor.declaringClass.name
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val namesAndValues = param.args.firstOrNull() as? Array<*> ?: return
                    captureHeaders(namesAndValues, sourceClass)
                } catch (_: Throwable) {
                    // 捕获逻辑失败绝不影响宿主请求构造
                }
            }
        })
    }

    /** 从“键值交替”的数组中收集所需请求头（线程安全写入） */
    private fun captureHeaders(namesAndValues: Array<*>, sourceClass: String) {
        var capturedKey = false
        var i = 0
        while (i + 1 < namesAndValues.size) {
            val name = (namesAndValues[i] as? String) ?: run {
                i += 2
                continue
            }
            val key = name.lowercase(Locale.ENGLISH)
            if (key in SpotifyApi.keysRequired) {
                val value = namesAndValues[i + 1] as? String
                if (!value.isNullOrEmpty() && SpotifyApi.recordHeader(key, value) && !capturedKey) {
                    capturedKey = true
                    YLog.debug(tag = TAG, msg = "从 $sourceClass 捕获到请求头: $key")
                }
            }
            i += 2
        }
    }

    /**
     * 初始化全局 DexKitCacheBridge 缓存（进程内只允许一次）。
     *
     * @return false 表示缓存不可用（已被同进程其它模块初始化），调用方应退化为实扫
     */
    private fun tryInitCacheBridge(context: Context): Boolean {
        return try {
            DexKitCacheBridge.init(SharedPrefsCache(context))
            YLog.debug(tag = TAG, msg = "DexKit 结果缓存桥已初始化")
            true
        } catch (e: Throwable) {
            YLog.warn(tag = TAG, msg = "DexKit 结果缓存桥初始化失败: ${e.message}")
            false
        }
    }

    /**
     * 生成宿主版本维度的缓存标识。
     *
     * R8 混淆结果随宿主“版本号 + 安装时间”变化：宿主升级后（哪怕版本号未变）
     * 安装时间也会更新，旧的混淆结果自动作废并触发一次实扫重建缓存。
     */
    private fun resolveAppTag(context: Context): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = info?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                it.versionCode.toLong()
            }
        } ?: 0L
        val lastUpdateTime = info?.lastUpdateTime ?: 0L
        return "lyricon.spotify:v$versionCode:u$lastUpdateTime"
    }

    private fun ensureDexKitLoaded() {
        System.loadLibrary("dexkit")
    }

    /**
     * [DexKitCacheBridge.Cache] 的 SharedPreferences 实现。
     *
     * 缓存内容只是一些方法描述符字符串（体积可忽略）；宿主清除应用数据会导致缓存
     * 丢失，下次启动实扫一次即可自愈。
     */
    private class SharedPrefsCache(context: Context) : DexKitCacheBridge.Cache {

        private val prefs: SharedPreferences =
            context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

        override fun getString(key: String, default: String?): String? {
            return prefs.getString(key, default)
        }

        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun getStringList(key: String, default: List<String>?): List<String>? {
            val raw = prefs.getString(key, null) ?: return default
            if (raw.isEmpty()) return emptyList()
            return raw.split(LIST_SEPARATOR)
        }

        override fun putStringList(key: String, value: List<String>) {
            prefs.edit().putString(key, value.joinToString(LIST_SEPARATOR)).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        override fun getAllKeys(): Collection<String> = prefs.all.keys

        override fun clearAll() {
            prefs.edit().clear().apply()
        }

        private companion object {
            /** 描述符为纯 ASCII，用单位分隔符拼列表不会与内容冲突 */
            const val LIST_SEPARATOR = "\u001F"
        }
    }
}
