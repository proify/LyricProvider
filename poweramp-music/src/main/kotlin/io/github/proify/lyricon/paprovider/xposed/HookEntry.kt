package io.github.proify.lyricon.paprovider.xposed

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.proify.lyricon.paprovider.BuildConfig

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        // 关闭 YukiHookAPI 内部的详细调试日志，
        // 在业务代码中手动输出更有意义的中文日志
        isDebug = false 
        debugLog {
            tag = Constants.LOG_TAG
        }
    }

    override fun onHook() = encase {
        loadApp(name = "com.maxmpz.audioplayer") {
            PowerAmp.hook(this)
        }
    }
}