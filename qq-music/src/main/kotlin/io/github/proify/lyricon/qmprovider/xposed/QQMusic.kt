/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object QQMusic : YukiBaseHooker() {
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }

    override fun onHook() {
        val classLoader = appClassLoader ?: return
        onAppLifecycle {
            onCreate {

            }
        }

//        XposedHelpers.findAndHookMethod(
//            "com.tencent.qqmusic.business.lyricnew.QRCDesDecrypt",
//            classLoader,
//            "doDecryptionLyric",
//            String::class.java,
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    super.beforeHookedMethod(param)
//                }
//
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    super.afterHookedMethod(param)
//                    YLog.debug("doDecryptionLyric src:"+param?.args?.get(0))
//                    YLog.debug("doDecryptionLyric dst:"+param?.result)
//                }
//            })


//        XposedHelpers.findAndHookConstructor(
//            "com.lyricengine.base.j",
//            classLoader,
//            Int::class.javaPrimitiveType,
//            Int::class.javaPrimitiveType,
//            String::class.java,
//            String::class.java,
//            String::class.java,
//            ArrayList::class.java,
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    super.beforeHookedMethod(param)
//                }
//
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    super.afterHookedMethod(param)
//                    mainScope.launch {
//                        delay(2000)
//                        ObjectUtils.print(param?.thisObject, printList = true)
//
//                    }
//                }
//            })

    }
}