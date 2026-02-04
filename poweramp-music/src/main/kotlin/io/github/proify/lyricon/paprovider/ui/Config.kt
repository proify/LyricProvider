package io.github.proify.lyricon.paprovider.ui

import com.highcapable.yukihookapi.hook.xposed.prefs.data.PrefsData

/**
 * 全局配置常量定义
 */
object Config {
    // 使用 YukiHookAPI 的 PrefsData 包装 Key 和默认值
    val ENABLE_TRANSLATION = PrefsData("enable_translation", true)
    val ENABLE_NET_SEARCH = PrefsData("enable_net_search", true)
    val ENABLE_AUTO_SAVE = PrefsData("enable_auto_save", true)
}