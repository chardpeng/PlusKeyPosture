package com.cong.pluskeyposture.core

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * 原生动作派发器 —— 本模块 v1.2.3 重写。
 *
 * ## 原理（全部由真机抓取 + 反汇编实证）
 *
 * 一加把"识别手势"和"执行动作"拆成两段：
 * ```
 *   物理 780 ──> StrategyActionButtonKeyLaunchApp     （system_server：识别短按/长按）
 *                   ├─ 短按 → 注入 781 DOWN + 781 UP
 *                   └─ 长按 → 注入 782 DOWN，抬起时注入 782 UP
 *                        ↓
 *                 com.oplus.gesture                      （消费方：真正执行动作）
 *                   └─ ActionKeyStartApp.startKeyAction(mIsSingleTap, switchState, currentScene)
 *                          switchState  = settings system oplus_action_button_switch_state
 *                          currentScene = content://uri.gesturemanagerprovider/action_button
 * ```
 * 消费方**每次都现场读 `switchState` 再现场查场景表**。所以本模块只要
 * 「改场景 + 注入同款 781/782」就能 100% 复用原厂实现。
 *
 * ## ★ v1.2.3 的关键修正：注入方式换成"借框架自己的手"
 *
 * 之前两版都试图自己反射 `android.hardware.input.InputManager` 再调
 * `injectInputEvent`，真机上 `available` **恒为 false**，且因为日志走了
 * `android.util.Log`（LSPosed 不收录）而完全看不到原因 —— 见 [Log2] 的说明。
 *
 * v1.2.3 换一个思路，**不去猜 ClassLoader / 不去反射 InputManager**，而是：
 *
 * > 被 hook 的那个类 `StrategyActionButtonKeyLaunchApp` **自己就有一个**
 * > `injectActionButtonPressKeyEvent(KeyEvent)` 方法，原厂正是用它注入 781/782。
 *
 * 那就**直接调用原厂那个方法**（方法是 public 的，Object 上直接 invoke）。
 * 好处：
 *  1. 不需要 InputManager、不需要 ClassLoader 猜测 —— 那是原厂自己会用的代码路径，
 *     必然可用，本模块只是"借用"；
 *  2. 事件构造参数（deviceId=-1、scanCode=0、source=0x101）由原厂方法自己保证，
 *     永不随 ROM 版本漂移；
 *  3. 与我们 hook 的是同一个对象，连 `this` 都是现成的。
 *
 * `InputManager` 反射链作为**第二方案**保留（部分 ROM 上原厂方法可能是 private 或被改名）。
 *
 * ## 注入事件的构造参数（逐位与框架反汇编对齐）
 * ```java
 * int code = 781;                       // 长按换 782
 * long now = SystemClock.uptimeMillis();
 * KeyEvent e = new KeyEvent(now, now, action, code, 0, 0, -1, 0, 0, 0x101);
 * InputManager.getInstance().injectInputEvent(e, 0);
 * ```
 */
object NativeActionDispatcher {

    /** 原厂注入事件的 source：0x101 = InputDevice.SOURCE_KEYBOARD */
    private const val SOURCE_OF_INJECTED = 0x101

    /** 原厂注入事件的 deviceId：虚拟键一律 -1 */
    private const val DEVICE_ID_VIRTUAL = -1

    /** injectInputEvent 的 mode：0 = ASYNC（与原厂一致） */
    private const val INJECT_MODE_ASYNC = 0

    /** 原厂注入方法的候选名字（不同 ColorOS 版本可能有别名） */
    private val INJECT_METHOD_NAMES = listOf(
        "injectActionButtonPressKeyEvent",
        "injectActionButtonKeyEvent",
        "injectPressKeyEvent",
    )

    // ==================== 场景读写 ====================

    fun currentScene(cr: ContentResolver): String? = runCatching {
        Settings.System.getString(cr, KeyConst.SETTING_SWITCH_STATE)
    }.getOrNull()

    fun setScene(cr: ContentResolver, scene: String): Boolean = runCatching {
        Settings.System.putString(cr, KeyConst.SETTING_SWITCH_STATE, scene)
    }.onFailure { Log2.w("写场景 $scene 失败：${it.message}") }.getOrDefault(false)

    // ==================== 注入能力（双方案）====================

    /**
     * 方案 A：借用被 hook 对象自己的原厂注入方法。
     *
     * 由 [MainHook] 在收到第一个物理键事件时通过 [bindInterceptor] 送进来 ——
     * 只有那时才拿得到 `StrategyActionButtonKeyLaunchApp` 的实例。
     */
    @Volatile private var interceptor: Any? = null

    /** 方案 A 解析出的方法（首个可用者） */
    @Volatile private var interceptorMethod: java.lang.reflect.Method? = null

    @Volatile private var interceptorBound = false

    /**
     * 由 MainHook 调用：把被 hook 的实例交进来，用于借用其原厂注入方法。
     *
     * @param target `StrategyActionButtonKeyLaunchApp` 的实例（即 hook 的 thisObject）
     * @return 是否成功解析出可用的注入方法
     */
    fun bindInterceptor(target: Any?): Boolean {
        if (target == null) return false
        if (interceptorBound && interceptor === target) return true
        interceptor = target
        interceptorBound = true

        val cls = target.javaClass
        for (name in INJECT_METHOD_NAMES) {
            val m = runCatching {
                cls.declaredMethods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        android.view.KeyEvent::class.java.isAssignableFrom(it.parameterTypes[0])
                }
            }.getOrNull()
            if (m != null) {
                runCatching { m.isAccessible = true }
                interceptorMethod = m
                Log2.i("注入方案A 就绪：借用 ${cls.simpleName}#${m.name}(KeyEvent)")
                return true
            }
        }
        Log2.w(
            "注入方案A 未找到原厂注入方法（候选名 $INJECT_METHOD_NAMES）。" +
                "该类含 KeyEvent 参数的方法：${
                    runCatching {
                        cls.declaredMethods.filter {
                            it.parameterTypes.any { p -> android.view.KeyEvent::class.java.isAssignableFrom(p) }
                        }.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }
                    }.getOrDefault("(读取失败)")
                }"
        )
        return false
    }

    /** 方案 A 是否可用 */
    private fun injectViaInterceptor(keyCode: Int, action: Int): Boolean? {
        val target = interceptor ?: return null
        val m = interceptorMethod ?: return null
        return runCatching {
            val event = buildEvent(keyCode, action)
            m.invoke(target, event)
            Log2.i("注入(方案A 原厂方法) keyCode=$keyCode action=$action 已发出")
            true
        }.onFailure {
            Log2.e("注入(方案A) 失败：${it.javaClass.simpleName} ${it.message}")
            // 方案 A 一旦抛异常就作废，避免每次按键都撞墙
            interceptorMethod = null
        }.getOrDefault(false)
    }

    // ---- 方案 B：反射 InputManager（后备）----

    @Volatile private var cachedCl: ClassLoader? = null

    @Volatile private var inputManagerOrNull: Any? = null

    @Volatile private var injectMethodOrNull: java.lang.reflect.Method? = null

    @Volatile private var probedB = false

    fun setClassLoader(cl: ClassLoader?) {
        cachedCl = cl
        inputManagerOrNull = null
        injectMethodOrNull = null
        probedB = false
    }

    /**
     * 逐路尝试解析 `InputManager.getInstance().injectInputEvent(event, mode)`。
     *
     * 【关键修正】`InputManager` 是 @hide 类，但**编译期我们用字符串反射**，
     * 所以唯一的变量是 ClassLoader 能不能解析到它。三路候选全部走一遍并
     * 逐条记录结果（以前因为日志走 android.util.Log，这些结果完全不可见）。
     */
    private fun probeInputManager(): Boolean {
        if (probedB) return inputManagerOrNull != null
        probedB = true

        val candidates = ArrayList<Pair<String, ClassLoader>>()
        runCatching {
            // 最可靠：hook 侧送进来的 system_server PathClassLoader
            cachedCl?.let { candidates.add("hook 类加载器" to it) }
            // 次选：InputEvent 自身的类加载器（framework boot classloader）
            android.view.InputEvent::class.java.classLoader?.let {
                candidates.add("InputEvent 类加载器" to it)
            }
            // 末选：系统类加载器
            ClassLoader.getSystemClassLoader()?.let { candidates.add("系统类加载器" to it) }
        }

        Log2.i("方案B 探测 InputManager，候选 ClassLoader 共 ${candidates.size} 个")
        for ((label, cl) in candidates) {
            val r = runCatching {
                val cls = Class.forName("android.hardware.input.InputManager", false, cl)
                val inst = cls.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("getInstance() 返回 null")
                val m = cls.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                Triple(cls, inst, m)
            }
            r.onSuccess { (cls, inst, m) ->
                inputManagerOrNull = inst
                injectMethodOrNull = m
                Log2.i("方案B 就绪：经「$label」拿到 ${cls.name}（${cl.javaClass.simpleName}）")
                return true
            }.onFailure {
                Log2.w("方案B 失败（「$label」）：${it.javaClass.simpleName} ${it.message}")
            }
        }
        Log2.w("方案B 全部失败：拿不到 InputManager")
        return false
    }

    private fun injectViaInputManager(keyCode: Int, action: Int, sync: Boolean): Boolean? {
        if (!probeInputManager()) return null
        val im = inputManagerOrNull ?: return null
        val m = injectMethodOrNull ?: return null
        return runCatching {
            val event = buildEvent(keyCode, action)
            val mode = if (sync) 1 else INJECT_MODE_ASYNC
            val r = m.invoke(im, event, mode) as? Boolean ?: false
            Log2.i("注入(方案B InputManager) keyCode=$keyCode action=$action sync=$sync -> $r")
            r
        }.onFailure {
            Log2.e("注入(方案B) 抛异常：${it.javaClass.simpleName} ${it.message}")
        }.getOrDefault(false)
    }

    /** 构造与原厂逐位一致的 KeyEvent */
    private fun buildEvent(keyCode: Int, action: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now, now, action, keyCode,
            0,                              // repeatCount
            0,                              // metaState
            DEVICE_ID_VIRTUAL,              // deviceId = -1
            0,                              // scanCode = 0
            0,                              // flags = 0
            SOURCE_OF_INJECTED              // source = 0x101
        )
    }

    /** 原生注入能力是否可用（任一方案就绪即可） */
    val available: Boolean
        get() = (interceptorMethod != null) || probeInputManager()

    /** 能力诊断串，便于日志一键定位 */
    fun diagnostic(): String = buildString {
        append("方案A(原厂方法)=")
        append(if (interceptorMethod != null) "可用(${interceptorMethod?.name})" else "不可用")
        append(" | 方案B(InputManager)=")
        append(if (probeInputManager()) "可用" else "不可用")
    }

    // ==================== 注入 ====================

    private fun injectKey(keyCode: Int, action: Int, sync: Boolean = false): Boolean {
        // 先方案 A（借用原厂方法，最稳），再方案 B
        injectViaInterceptor(keyCode, action)?.let { if (it) return true }
        return injectViaInputManager(keyCode, action, sync) ?: false
    }

    /**
     * 短按：注入 781 DOWN + 781 UP。
     *
     * ## 时序依据（2026-09-26 真机 keylog.txt 实证，v1.2.3 修正）
     * ```
     * 22:52:50.045  KEYLOG  781 ACTION_DOWN  eventTime=572603000000 downTime=572603000000
     * 22:52:50.046  KEYLOG  781 ACTION_UP    eventTime=572603000000 downTime=572603000000
     * ```
     * 两件事：
     *  1. DOWN 与 UP 的 `eventTime` **完全相同**（572603000000）—— 原厂是背靠背连发，
     *     不是"间隔 14ms"（v1.2.1 按 14ms 补发是**基于误读**的修正，这里改回来）；
     *  2. 两者是**同一批**注入，接收侧 `GestureKeyEventManager` 在 1ms 内收到两条
     *     （22:52:50.046 与 22:52:50.060 是两处不同 TAG 打同一事件）。
     *
     * 双击由消费方 `com.oplus.gesture` 自行数"独立的 781 对"来判定，
     * 与 DOWN/UP 之间的间隔无关。所以这里干脆一次性发完，不再延时补发。
     */
    private fun injectSingleTap(): Boolean {
        val down = injectKey(KeyConst.KEY_VIRTUAL_SINGLE_TAP, KeyEvent.ACTION_DOWN, sync = true)
        val up = injectKey(KeyConst.KEY_VIRTUAL_SINGLE_TAP, KeyEvent.ACTION_UP)
        return down || up
    }

    /**
     * 长按：**完全套用短按的注入模式**，一次性发 782 DOWN + 782 UP。
     *
     * ## 为什么长按也一次发完（v1.2.4 修正）
     *
     * 短按（781 DOWN + 781 UP，背靠背连发）实测可用；长按此前是"只发 782 DOWN，
     * 等用户抬手再补 782 UP"，实测**不生效**。既然消费方 `com.oplus.gesture`
     * 需要的是一次**完整闭合**的按键事件，那就和短按一样一次发完 ——
     * 消费方收到完整的 782 DOWN/UP 对即执行场景的 longPressAction，
     * 不需要我们替它维持"按住"状态。
     */
    private fun injectLongPress(): Boolean {
        val down = injectKey(KeyConst.KEY_VIRTUAL_LONG_PRESS, KeyEvent.ACTION_DOWN, sync = true)
        val up = injectKey(KeyConst.KEY_VIRTUAL_LONG_PRESS, KeyEvent.ACTION_UP)
        return down || up
    }

    /** 兼容旧调用点：不再需要延时补发，保留为直通（MainHook 已改为走 dispatch） */
    fun injectSingleTapUp(): Boolean = false

    // ==================== 对外动作 ====================

    /**
     * 触发一次「原生场景」动作。
     *
     * @return true 表示已成功交给原生执行
     */
    fun dispatch(cr: ContentResolver, scene: String, longPress: Boolean): Boolean {
        if (!available) {
            Log2.w("原生动作不可执行：${diagnostic()}")
            return false
        }
        if (!setScene(cr, scene)) {
            Log2.w("原生动作不可执行：场景写入失败 scene=$scene")
            return false
        }

        return if (longPress) {
            injectLongPress()
        } else {
            injectSingleTap()
        }
    }

    /** 长按抬起：补发 782 UP（原厂抬起时也发） */
    fun releaseLongPress(): Boolean =
        injectKey(KeyConst.KEY_VIRTUAL_LONG_PRESS, KeyEvent.ACTION_UP)

    /**
     * 把场景恢复成用户原本的选择。
     *
     * 我们每次按键都会临时改写 `oplus_action_button_switch_state`，不恢复的话
     * 系统「设置 > 快捷键」里显示的会变成我们最后写入的值。
     */
    fun restoreScene(cr: ContentResolver, scene: String?) {
        if (scene.isNullOrEmpty()) return
        setScene(cr, scene)
    }
}
