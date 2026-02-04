/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed
//
//import android.content.Context
//import androidx.core.content.edit
//import org.luckypray.dexkit.wrap.DexMethod
//
//object DexkitCache {
//    private const val PREF_NAME = "dexkit_caches"
//
//    private fun getPrefs(context: Context) =
//        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
//
//    fun cacheMethod(context: Context, dexMethod: DexMethod, key: String) {
//        getPrefs(context).edit {
//            putString("m_$key", dexMethod.serialize())
//        }
//    }
//
//    fun getMethod(context: Context, key: String): DexMethod? {
//        val serialized = getPrefs(context).getString("m_$key", null) ?: return null
//        return runCatching { DexMethod.deserialize(serialized) }.getOrNull()
//    }
//
//    fun clearMethod(context: Context, key: String) {
//        getPrefs(context).edit { remove("m_$key") }
//    }
//}