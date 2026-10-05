package com.cong.pluskeyposture.core

import android.util.Log

/**
 * 统一日志出口 —— **本模块唯一的日志入口，不要再用 `android.util.Log`**。
 *
 * ## 为什么必须要有这一层（2026-09-27 实证踩坑）
 *
 * 模块跑在 `system_server` 进程里，日志有两个互不相通的去处：
 *
 * | 通道 | 写入位置 | LSPosed `modules_*.log` 是否收录 |
 * |---|---|---|
 * | `android.util.Log.i(tag, msg)` | logcat / `system` buffer | **否** |
 * | `XposedBridge.log(msg)` | LSPosed 框架日志 | 是 |
 *
 * 实测证据：模块内 `MainHook` 用 `XposedBridge.log`，全部日志可见；
 * 而 `NativeActionDispatcher` / `PostureEngine` / `ActionExecutor` / `Prefs`
 * 用 `android.util.Log`，日志里**一条都没有** —— 于是"注入能力探测失败"
 * 这类关键信息完全不可见，只能在黑盒里瞎猜（本轮前两版都栽在这）。
 *
 * 反查已安装 APK 的 dex 印证：
 * ```
 * classes3.dex（含 NativeActionDispatcher/PostureEngine/ActionExecutor/Prefs）
 *     Landroid/util/Log;                        -> 有
 *     Lde/robv/android/xposed/XposedBridge;     -> 0 次
 * classes4.dex（含 MainHook）
 *     Landroid/util/Log;                        -> 0 次
 *     Lde/robv/android/xposed/XposedBridge;     -> 有
 * ```
 *
 * ## 策略
 *
 * 优先 `XposedBridge.log`（在 system_server 里必然可用），**同时**再发一份
 * `android.util.Log` —— 后者让设置界面进程（普通 App）也能正常看到日志，
 * 且不影响 LSPosed 收录。两边都失败时静默吞掉，绝不因为日志把按键链路弄崩。
 */
object Log2 {

    /** 统一 TAG，与 MainHook 保持一致，便于 `grep PlusKeyPosture` 一把捞 */
    const val TAG = "PlusKeyPosture"

    /**
     * 反射持有 `XposedBridge.log(String)`。
     *
     * 【为什么用反射而不是直接 import】
     * `XposedBridge` 来自编译期 `compileOnly` 的 API jar，运行时只在**已被 Xposed
     * 注入的进程**里存在。设置界面（普通 App 进程，未被注入）里直接调用会
     * `NoClassDefFoundError`。用反射 + 缓存就能在两种进程里都安全落地。
     */
    @Volatile private var xposedLog: java.lang.reflect.Method? = null
    @Volatile private var xposedResolved = false

    private fun xposed(): java.lang.reflect.Method? {
        if (xposedResolved) return xposedLog
        synchronized(this) {
            if (xposedResolved) return xposedLog
            xposedResolved = true
            xposedLog = runCatching {
                Class.forName("de.robv.android.xposed.XposedBridge")
                    .getMethod("log", String::class.java)
            }.getOrNull()
            return xposedLog
        }
    }

    fun i(msg: String) = log(android.util.Log.INFO, msg)

    fun w(msg: String) = log(android.util.Log.WARN, msg)

    fun e(msg: String) = log(android.util.Log.ERROR, msg)

    fun e(msg: String, t: Throwable) =
        log(android.util.Log.ERROR, "$msg：${t.javaClass.simpleName} ${t.message}")

    private fun log(priority: Int, msg: String) {
        // 通道 1：XposedBridge —— LSPosed 日志的可见通道（system_server）
        runCatching {
            xposed()?.invoke(null, "[$TAG] $msg")?.let { return@runCatching }
        }
        // 通道 2：logcat —— App 进程 / Xposed 不可用时仍能看到
        runCatching { Log.println(priority, TAG, msg) }
    }
}
