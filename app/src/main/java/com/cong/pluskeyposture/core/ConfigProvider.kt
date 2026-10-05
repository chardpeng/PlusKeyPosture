package com.cong.pluskeyposture.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * 配置读取通道（运行在**模块 App 进程**）。
 *
 * ## 为什么需要它
 *
 * hook 侧跑在 system_server（uid 1000），而配置存在模块 App 的数据目录里：
 * `/data/user_de/0/com.cong.pluskeyposture/shared_prefs/plus_key_posture_config.xml`
 *
 * 直读这个文件在 ColorOS 16 上会被 SELinux 的 MLS 类别挡住：
 * ```
 * 解析配置失败：open failed: EACCES (Permission denied)
 * 文件标签 u:object_r:app_data_file:s0:c112,c257,c512,c768
 * ```
 * system_server 的 `s0` 覆盖不到 App 那一串随机类别，于是**永远读不到配置**——
 * 这正是「总开关关不掉 / 改了设置不生效」的根因。
 *
 * 另有第二个坑：即使能读到，SharedPreferences 也是**每进程一份内存缓存**，
 * UI 进程 commit 之后 system_server 侧不一定立刻看到。
 *
 * ## 解法
 *
 * 用 ContentProvider 把配置读取搬回 App 进程：
 *   - App 进程读自己目录**不受任何 SELinux 限制**；
 *   - SharedPreferences 就在本进程内，永远是权威值（无缓存不同步问题）；
 *   - system_server 属于 core uid，可以直接 `ContentResolver.call()` 访问，
 *     不需要申请任何权限。
 *
 * 安全性：只暴露「读配置」一个方法，且在 [call] 里校验调用方 uid，
 * 非 root / 非 system 一律返回空 Bundle。即使 provider 声明为 exported 也读不到东西。
 *
 * 注意：本类**不是** Xposed hook 的一部分，是普通 App 组件。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        private const val TAG = "PlusKeyPosture"

        /** authority，需与 AndroidManifest 中声明一致 */
        const val AUTHORITY = "com.cong.pluskeyposture.config"

        /** 读全部配置，返回 Bundle */
        const val METHOD_READ_ALL = "read_all"

        /** 探活（用于设置界面诊断） */
        const val METHOD_PING = "ping"

        val URI_CONFIG: Uri = Uri.parse("content://$AUTHORITY/config")

        /**
         * 直接调用（不走 ContentResolver），供 App 进程内部使用。
         * 目前仅用于让设置界面的「诊断」按钮复用同一套序列化逻辑。
         */
        fun readAllInto(bundle: Bundle, prefs: android.content.SharedPreferences) {
            for ((k, v) in prefs.all) {
                when (v) {
                    is String -> bundle.putString(k, v)
                    is Boolean -> bundle.putBoolean(k, v)
                    is Int -> bundle.putInt(k, v)
                    is Long -> bundle.putLong(k, v)
                    is Float -> bundle.putFloat(k, v)
                    else -> Unit
                }
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()

        // ---- 安全闸门：只服务 root / system 进程 ----
        val uid = Binder.getCallingUid()
        val isCore = uid == 0 || uid == 1000
        if (!isCore) {
            Log2.w("配置读取被拒绝：调用方 uid=$uid 不是 root/system")
            return out
        }

        val ctx = context ?: return out

        if (method == METHOD_PING) {
            out.putBoolean("ok", true)
            out.putInt("caller_uid", uid)
            return out
        }

        if (method != METHOD_READ_ALL) return out

        return runCatching {
            // 用「设备加密存储」：开机早期（用户解锁前）system_server 也能读到，
            // 与 Prefs.ui() 保持一致。
            val prefs = Prefs.ui(ctx)
            Prefs.ensureDefaults(prefs)
            readAllInto(out, prefs)
            out.putBoolean("__ok", true)
            out
        }.onFailure {
            Log2.w("读取配置失败：${it.message}")
        }.getOrDefault(out)
    }

    // 本 Provider 只用于 call()，其余标准接口一律不提供
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
