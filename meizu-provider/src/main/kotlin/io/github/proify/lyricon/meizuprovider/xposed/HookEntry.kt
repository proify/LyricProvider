/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meizuprovider.xposed

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.proify.lyricon.library.meizuprovider.MeizuProvider
import io.github.proify.lyricon.provider.ProviderLogo

/**
 * 此应用为:share:meizhu-provider模块的包装
 */
@InjectYukiHookWithXposed(modulePackageName = Constants.PROVIDER_PACKAGE_NAME)
open class HookEntry : IYukiHookXposedInit {

    override fun onHook() =
        YukiHookAPI.encase {
            loadApp(
                isExcludeSelf = true,
                MeizuProvider(
                    providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                    logo = ProviderLogo.fromBase64(Constants.ICON)
                )
            )
        }

    override fun onInit() = YukiHookAPI.configs {
        debugLog { tag = "MeizhuProvider" }
    }
}