package io.github.proify.lyricon.paprovider.xposed

/**
 * 全局常量定义
 */
object Constants {
    // 日志 TAG
    const val LOG_TAG = "LyricProvider-PowerAmp"

    // 插件自身的 Provider 包名，用于 Lyricon 识别
    const val PROVIDER_PACKAGE_NAME = "io.github.proify.lyricon.paprovider"

    // PowerAmp 的 SVG 图标
    const val ICON = """
        <svg width="24" height="24" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10-4.48,10-10S17.52,2,12,2z M10,16.5v-9l6,4.5L10,16.5z" fill="#FFFFFF"/>
        </svg>
    """
}