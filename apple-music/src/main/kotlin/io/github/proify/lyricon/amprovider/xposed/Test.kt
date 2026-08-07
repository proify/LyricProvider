package io.github.proify.lyricon.amprovider.xposed

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

private const val TAG = "ASProviderTest"

/**
 * 原实现问题：
 * ```
 * fun testContentResolver(context: Context) {
 *     val uri = Uri.parse("content://com.rosan.installer.x.revived.fileprovider/...")
 *     resolver.query(uri, null, null, null, null)
 * }
 * ```
 *
 * 问题 1：URI 路径 "..." 无效；
 * 问题 2：FileProvider 默认 android:exported="false"，
 *         直接用 contentResolver.query() 访问未授权 URI 会被系统拒绝。
 *
 * 触发日志：
 *   Permission Denial: opening provider androidx.core.content.FileProvider
 *   from ProcessRecord{...com.rosan.installer.x.revived/u0a526} (pid=11994, uid=10526)
 *   that is not exported from UID 10202
 *
 * 含义：uid=10526 的 Rosan Installer 尝试打开 uid=10202 应用的 FileProvider，
 *       但该 provider 未导出且没有收到 FLAG_GRANT_READ_URI_PERMISSION 临时授权，
 *       系统在 ActivityManagerService 侧直接拒绝。
 *
 * 正确访问 FileProvider 的前提（三选一）：
 *   1) 通过 Intent 收到带 FLAG_GRANT_READ_URI_PERMISSION / FLAG_GRANT_WRITE_URI_PERMISSION 的 content:// URI；
 *   2) 持有方调用 grantUriPermission() 显式授权给你的包名；
 *   3) 与持有方同 UID 或同签名。
 */

/** 兼容旧签名：没有授权来源时，安全检查并给出明确提示，而不是直接抛异常。 */
fun testContentResolver(context: Context) {
    testContentResolver(context, null)
}

/**
 * 推荐：从带授权的 Intent 中取出 content:// URI 再访问。
 * 这是第三方应用访问 FileProvider 的标准姿势。
 */
fun testContentResolver(context: Context, intent: Intent?) {
    // 1. 尝试从 Intent 获取持有方已授权的 URI
    val uri = intent?.data
    if (uri == null) {
        Log.w(TAG, "未提供 content:// URI（Intent.data 为空）")
        return
    }
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        Log.w(TAG, "不是 content:// URI: $uri")
        return
    }

    // 2. 检查 Intent 是否携带了系统授予的临时权限
    val readGranted = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    if (!readGranted) {
        Log.w(TAG, "Intent 未携带 FLAG_GRANT_READ_URI_PERMISSION，访问会被 Permission Denial 拒绝")
        return
    }
    Log.i(TAG, "已获得临时读取授权: $uri")

    // 3. 执行实际查询
    queryUri(context, uri)
}

/** 另一种标准姿势：访问前先检查当前包是否对该 URI 持有授权。 */
fun testContentResolverWithPermissionCheck(context: Context, uri: Uri, targetPackage: String) {
    val pm = context.packageManager
    val selfUid = context.applicationInfo.uid
    val targetUid = try {
        pm.getApplicationInfo(targetPackage, 0).uid
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "目标包不存在: $targetPackage")
        return
    }

    // 同 UID 可以直接访问
    if (selfUid == targetUid) {
        Log.i(TAG, "与目标包同 UID，可直接访问")
        queryUri(context, uri)
        return
    }

    // 检查是否已持有该 URI 的读取授权（可能来自之前的 Intent 或持久化授权）
    val mode = context.checkUriPermission(uri, 0, 0, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (mode == PackageManager.PERMISSION_GRANTED) {
        Log.i(TAG, "已持有该 URI 的读取授权")
        queryUri(context, uri)
    } else {
        Log.w(TAG, "未持有读取授权 → 系统会抛出 Permission Denial")
    }
}

private fun queryUri(context: Context, uri: Uri) {
    val resolver: ContentResolver = context.contentResolver
    try {
        val cursor = resolver.query(uri, null, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                Log.i(TAG, "查询到 ${it.columnCount} 列")
            }
        } ?: Log.w(TAG, "query 返回 null")
    } catch (e: SecurityException) {
        // 这里就是日志中的 Permission Denial
        Log.e(TAG, "SecurityException (Permission Denial): ${e.message}")
    } catch (e: Exception) {
        Log.e(TAG, "查询异常: $e")
    }
}