package com.cong.pluskeyposture.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.cong.pluskeyposture.core.Action
import com.cong.pluskeyposture.core.ActionExecutor
import com.cong.pluskeyposture.core.KeyConst
import com.cong.pluskeyposture.core.Log2
import com.cong.pluskeyposture.core.NativeActionDispatcher
import com.cong.pluskeyposture.core.Posture
import com.cong.pluskeyposture.core.PostureEngine
import com.cong.pluskeyposture.core.Prefs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口 —— v1.2.3。
 *
 * ## 作用域
 * 只有 `android`（system_server）一个进程。
 *
 * ## Hook 点
 *
 * ```
 * com.android.server.policy.StrategyActionButtonKeyLaunchApp
 *     #actionInterceptKeyBeforeQueueing(KeyEvent, int, int, boolean, boolean) : int
 * ```
 *
 * 返回值语义由原厂定义：对物理键 780 返回 **0（拦截）**，对虚拟键 781/782 返回 1（放行）。
 * 因此在 `beforeHookedMethod` 里 `param.result = 0` 会**跳过原方法体**，
 * 原厂"495ms 后注入 781/782"的链路根本不会启动 —— 唯一且确定地接管。
 *
 * ## ★ v1.2.3 新增：绑定原厂实例，借用它的注入方法
 *
 * 我们接管了原厂方法体，但仍需要"注入 781/782"的能力。v1.2.3 不再自己反射
 * InputManager，而是**把被 hook 的实例交给 [NativeActionDispatcher]**，
 * 让它直接调用原厂自己的 `injectActionButtonPressKeyEvent(KeyEvent)` ——
 * 那是原厂必然会用到的代码路径，必然可用，见 [NativeActionDispatcher.bindInterceptor]。
 *
 * 绑定时机：第一个物理键事件进来时（`param.thisObject` 就是实例）。
 *
 * ## 日志
 * 全部走 [Log2]（system_server 侧走 `XposedBridge.log`，LSPosed 日志可见）。
 * v1.2.2 及以前用 `android.util.Log`，**LSPosed 日志里一条都看不到**，
 * 导致"注入能力探测失败"这类关键信息不可见 —— 见 [Log2] 的说明。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "PlusKeyPosture"

        private val TARGET_CLASSES = listOf(
            "com.android.server.policy.StrategyActionButtonKeyLaunchApp",
        )

        /** 拦截器方法名（实测签名 `(Landroid/view/KeyEvent;IIZZ)I`） */
        private const val TARGET_METHOD = "actionInterceptKeyBeforeQueueing"

        internal fun log(msg: String) = Log2.i(msg)
    }

    // ==================== 运行期状态 ====================

    @Volatile private var appContext: Context? = null
    @Volatile private var postureEngine: PostureEngine? = null
    @Volatile private var executor: ActionExecutor? = null

    /** 用户自己在「系统设置 > 快捷键」里选的场景 */
    @Volatile private var originalScene: String? = null

    /** 我们最后一次写进去的场景 */
    @Volatile private var lastWrittenScene: String? = null

    @Volatile private var hooked = false
    @Volatile private var pressActive = false
    @Volatile private var pressing = false
    @Volatile private var pressConfig: Prefs.Snapshot? = null

    /** 是否已成功把原厂实例交给注入器 */
    @Volatile private var interceptorBound = false

    private var longPressFired = false
    private var pendingNativeLong = false
    private var keyDownTime = 0L

    private var longPressTask: Runnable? = null
    private var restoreTask: Runnable? = null

    // ==================== 双击检测状态 ====================

    /** 双击窗口内已完成的短按次数（0 或 1） */
    private var tapCount = 0

    /** 双击窗口超时任务（超时后判定为单击） */
    private var doubleTapTask: Runnable? = null

    private val handler: Handler by lazy {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null) Handler(mainLooper) else Handler(ensureBackgroundLooper())
    }

    private fun ensureBackgroundLooper(): Looper {
        Looper.myLooper()?.let { return it }
        Looper.prepare()
        return Looper.myLooper()!!
    }

    // ==================== 生命周期 ====================

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        log("Zygote 初始化完成，模块已加载（v1.2.3 借用原厂注入）")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val info = lpparam ?: return
        val pkg = info.packageName
        if (pkg != "android" && pkg != "system") return

        log("发现系统框架进程（pkg=$pkg, process=${info.processName}），开始挂载 hook")

        // 方案 B 的 ClassLoader（InputManager 反射备用路径）
        NativeActionDispatcher.setClassLoader(info.classLoader)

        acquireContextImmediately(info.classLoader)
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread",
                info.classLoader,
                "systemMain",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getSystemContext") as? Context
                        }.getOrNull()
                        if (ctx != null) onContextReady(ctx)
                    }
                }
            )
        }.onFailure { log("钩 systemMain 失败：${it.javaClass.simpleName} ${it.message}") }
        startContextRetryThread(info.classLoader)

        if (!hookInterceptor(info.classLoader)) {
            log("首次挂载未成功，转后台重试")
            startHookRetryThread(info.classLoader)
        }
    }

    private fun acquireContextImmediately(cl: ClassLoader) {
        val ctx = runCatching {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", cl)
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            if (at != null) XposedHelpers.callMethod(at, "getSystemContext") as? Context else null
        }.getOrNull()
        if (ctx != null) onContextReady(ctx)
    }

    private fun startContextRetryThread(cl: ClassLoader) {
        Thread({
            var tries = 0
            while (tries < 40 && appContext == null) {
                Thread.sleep(500)
                tries++
                val ctx = runCatching {
                    val atClass = XposedHelpers.findClass("android.app.ActivityThread", cl)
                    val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
                    if (at != null) XposedHelpers.callMethod(at, "getSystemContext") as? Context else null
                }.getOrNull()
                if (ctx != null) {
                    onContextReady(ctx)
                    return@Thread
                }
            }
            if (appContext == null) log("重试 $tries 次后仍未拿到 system_server Context")
        }, "PlusKeyCtxAcquire").apply { isDaemon = true }.start()
    }

    private fun startHookRetryThread(cl: ClassLoader) {
        Thread({
            var tries = 0
            while (tries < 20 && !hooked) {
                Thread.sleep(500)
                tries++
                if (hookInterceptor(cl)) return@Thread
            }
        }, "PlusKeyHookRetry").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun onContextReady(ctx: Context) {
        if (appContext != null) return
        try {
            appContext = ctx
            postureEngine = PostureEngine(ctx)
            executor = ActionExecutor(ctx)

            log("system_server 就绪 | ${NativeActionDispatcher.diagnostic()}")

            // 配置与「用户原场景」延时读：开机早期 ContentResolver 还没就绪
            handler.postDelayed({ refreshRuntimeState("延时初始化") }, 3000)
            refreshRuntimeState("开机早期")
        } catch (t: Throwable) {
            log("模块初始化异常（已拦截，不影响系统）：$t")
        }
    }

    @Synchronized
    private fun refreshRuntimeState(tag: String) {
        val ctx = appContext ?: return
        val scene = runCatching {
            NativeActionDispatcher.currentScene(ctx.contentResolver)
        }.getOrNull()
        if (scene != null && originalScene == null) originalScene = scene
        log("$tag | 用户原场景=${originalScene ?: "未知"} | 本次读到=${scene ?: "失败"}")
        dumpCurrentConfig(tag)
    }

    // ==================== Hook 挂载 ====================

    private fun hookInterceptor(cl: ClassLoader): Boolean {
        if (hooked) return true
        var ok = false
        for (name in TARGET_CLASSES) {
            val clazz = try {
                XposedHelpers.findClass(name, cl)
            } catch (t: Throwable) {
                log("类不存在：$name（${t.javaClass.simpleName}）")
                continue
            }

            val methods = runCatching { clazz.declaredMethods }.getOrNull().orEmpty()
                .filter { it.name == TARGET_METHOD }

            if (methods.isEmpty()) {
                log("$name 中没有 $TARGET_METHOD，该类方法样例：" +
                    runCatching { clazz.declaredMethods }.getOrNull().orEmpty()
                        .take(25).joinToString { it.name })
                continue
            }

            for (m in methods) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, m.name, *m.parameterTypes, KeyInterceptor)
                    log("已挂载 hook：$name#${m.name}(" +
                        m.parameterTypes.joinToString { it.simpleName } + ")")
                    ok = true
                } catch (t: Throwable) {
                    log("挂载失败 $name#${m.name}：${t.javaClass.simpleName} ${t.message}")
                }
            }
        }
        if (ok) hooked = true
        log(if (ok) "按键拦截已就位。" else "警告：未能挂上任何 hook 点，请把日志发给开发者。")
        return ok
    }

    // ==================== 按键拦截 ====================

    private val KeyInterceptor = object : XC_MethodHook() {

        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                // ★ 第一次拿到实例时，立刻借用它的原厂注入方法
                if (!interceptorBound) {
                    val ok = runCatching { NativeActionDispatcher.bindInterceptor(param.thisObject) }
                        .getOrDefault(false)
                    interceptorBound = true
                    log("原厂注入方法绑定：${if (ok) "成功" else "未找到（将依赖 InputManager 反射）"} | " +
                        NativeActionDispatcher.diagnostic())
                }

                val event = param.args.firstOrNull { it is KeyEvent } as? KeyEvent ?: return
                if (!isPlusKeyEvent(event)) return

                val isDown = event.action == KeyEvent.ACTION_DOWN
                val isRepeat = event.repeatCount > 0

                if (isDown && !isRepeat) {
                    val cfg = loadConfig()
                    if (appContext == null || executor == null || !cfg.enabled() || cfg.isAllNone()) {
                        pressActive = false
                        log(
                            "本次按键不接管并交还原生（原因：" +
                                when {
                                    appContext == null -> "Context 未就绪"
                                    executor == null -> "执行器未就绪"
                                    !cfg.enabled() -> "模块总开关关闭"
                                    else -> "六个槽位全为无操作"
                                } + "）"
                        )
                        return
                    }
                    pressConfig = cfg
                    pressActive = true
                    param.result = 0
                    onKeyDown(cfg)
                } else if (!isDown) {
                    if (!pressActive) {
                        log("发现一次未接管的 UP 事件，原样放行")
                        return
                    }
                    param.result = 0
                    onKeyUp()
                } else {
                    if (pressActive) param.result = 0
                }
            } catch (t: Throwable) {
                log("按键拦截异常（已拦截）：$t")
            }
        }
    }

    /** 判据：scanCode == 735（内核原始码，最稳）或 keyCode == 780 */
    private fun isPlusKeyEvent(event: KeyEvent): Boolean =
        event.scanCode == KeyConst.SCANCODE_PLUS_KEY ||
            event.keyCode == KeyConst.KEYCODE_PLUS_KEY

    // ==================== 按压状态机 ====================

    @Synchronized
    private fun onKeyDown(cfg: Prefs.Snapshot) {
        cancelLongPressTask()
        cancelRestoreTask()
        if (pressing) log("检测到上一次按压未正常收尾，重置状态机")

        // ★ 双击窗口内的第二次按下：取消"单击"超时判定。
        //   这一次按压的结果将决定是「双击」（短按抬起）还是「长按」（长按触发）。
        if (tapCount == 1) {
            cancelDoubleTapTask()
            log("双击窗口内再次按下，取消单击判定")
        }

        pressing = true
        longPressFired = false
        pendingNativeLong = false
        keyDownTime = SystemClock.uptimeMillis()
        pressConfig = cfg

        runCatching {
            val ctx = appContext ?: return@runCatching
            val cur = NativeActionDispatcher.currentScene(ctx.contentResolver)
            if (cur != null && cur != lastWrittenScene) originalScene = cur
        }

        runCatching { postureEngine?.onKeyDown() }

        val task = Runnable { tryFireLongPress() }
        longPressTask = task
        handler.postDelayed(task, KeyConst.LONG_PRESS_TRIGGER_MS)

        log("按下：开始计时（长按阈值 ${KeyConst.LONG_PRESS_TRIGGER_MS}ms）")
    }

    @Synchronized
    private fun tryFireLongPress() {
        try {
            if (!pressing || longPressFired) return
            val elapsed = SystemClock.uptimeMillis() - keyDownTime
            if (elapsed < KeyConst.LONG_PRESS_TRIGGER_MS) return
            if (elapsed > KeyConst.MAX_PRESS_MS) {
                log("按压 ${elapsed}ms 超过保护上限，不再触发")
                return
            }
            longPressFired = true

            val posture = runCatching { postureEngine?.current() }.getOrNull() ?: Posture.UNKNOWN
            val cfg = pressConfig ?: loadConfig()

            // ★ 姿态判不出 → 不执行任何动作（用户明确要求：不猜、不兜底）
            if (posture == Posture.UNKNOWN) {
                log("长按 + 姿态=UNKNOWN -> 不执行任何动作（传感器判据不足）")
                return
            }

            val action = cfg.longActionFor(posture)

            log("长按 + 姿态=$posture -> ${action.label}")
            fire(action, longPress = true)
        } catch (t: Throwable) {
            log("长按处理异常（已拦截，不影响系统）：$t")
        }
    }

    @Synchronized
    private fun onKeyUp() {
        try {
            if (!pressing) return
            pressing = false
            cancelLongPressTask()
            runCatching { postureEngine?.stopSampling() }

            if (!longPressFired) {
                // 短按：进入「单击 / 双击」判定
                handleTap()
            } else {
                // 长按收尾：清理双击状态（双击窗口内的那次按压如果变成了长按）
                tapCount = 0
                cancelDoubleTapTask()
                if (pendingNativeLong) {
                    runCatching { NativeActionDispatcher.releaseLongPress() }
                    pendingNativeLong = false
                    scheduleSceneRestore()
                }
            }
        } catch (t: Throwable) {
            log("抬起处理异常（已拦截）：$t")
        } finally {
            pressConfig = null
            pressActive = false
        }
    }

    /**
     * 短按抬起后的「单击 / 双击」判定。
     *
     * ★ 智能延迟：双击槽位为「无操作」时不进入双击窗口，短按零延迟、立即执行；
     *   只有给双击配了动作，才等待 [KeyConst.DOUBLE_TAP_WINDOW_MS] 区分单击 / 双击。
     *
     * 状态机：
     * ```
     * 第一次短按抬起
     *   ├─ 双击槽位==NONE → 立即执行「短按」动作（零延迟）
     *   └─ 双击槽位!=NONE → tapCount 0→1，启动 300ms 双击窗口
     *        ├─ 窗口超时      → 执行「短按」动作（单击）
     *        └─ 窗口内再按下    → onKeyDown 取消超时；本次抬起
     *             ├─ 短按 → 执行「双击」动作
     *             └─ 长按 → 走长按收尾（onKeyUp 里 reset tapCount）
     * ```
     */
    private fun handleTap() {
        val cfg = pressConfig ?: loadConfig()
        val doubleAction = cfg.doublePress()

        // 双击未配置 → 短按零延迟，行为与旧版一致
        if (doubleAction == Action.NONE) {
            val action = cfg.shortPress()
            log("短按 -> ${action.label}")
            fire(action, longPress = false)
            return
        }

        if (tapCount == 0) {
            // 第一次短按：进入双击窗口
            tapCount = 1
            cancelDoubleTapTask()
            val shortAction = cfg.shortPress()
            val task = Runnable {
                if (tapCount == 1) {
                    tapCount = 0
                    log("短按（单击）-> ${shortAction.label}")
                    fire(shortAction, longPress = false)
                }
            }
            doubleTapTask = task
            handler.postDelayed(task, KeyConst.DOUBLE_TAP_WINDOW_MS)
            log("第一次短按，等待 ${KeyConst.DOUBLE_TAP_WINDOW_MS}ms 判断双击")
        } else {
            // 第二次短按 → 双击
            tapCount = 0
            cancelDoubleTapTask()
            log("双击 -> ${doubleAction.label}")
            fire(doubleAction, longPress = false)
        }
    }

    private fun cancelDoubleTapTask() {
        doubleTapTask?.let { handler.removeCallbacks(it) }
        doubleTapTask = null
    }

    private fun cancelLongPressTask() {
        longPressTask?.let { handler.removeCallbacks(it) }
        longPressTask = null
    }

    private fun cancelRestoreTask() {
        restoreTask?.let { handler.removeCallbacks(it) }
        restoreTask = null
    }

    // ==================== 动作分派 ====================

    private inline fun safeRun(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$tag 异常（已拦截）：$t")
        }
    }

    /**
     * 执行一次动作。
     *
     *  - 原生动作（`scene != null`）：改场景 + 注入 781/782，交给系统执行。
     *    注入不可用时**不兜底**，只打日志 —— 自己实现一套等价功能会造成行为不一致。
     *  - 自实现动作：交给 [ActionExecutor]。
     */
    private fun fire(action: Action, longPress: Boolean) = safeRun("执行动作") {
        if (action == Action.NONE) return@safeRun
        val ctx = appContext ?: return@safeRun
        val cfg = pressConfig ?: loadConfig()

        if (cfg.toast()) toast(ctx, action.label)

        val cr = ctx.contentResolver
        val scene: String? = if (action == Action.SYSTEM_DEFAULT) originalScene else action.nativeScene

        if (scene != null) {
            val ok = NativeActionDispatcher.dispatch(cr, scene, longPress)
            log("原生注入：scene=$scene 长按=$longPress -> ${if (ok) "成功" else "失败"}")
            if (!ok) {
                log("注入不可用，本动作未执行 | ${NativeActionDispatcher.diagnostic()}")
                return@safeRun
            }
            lastWrittenScene = scene
            if (longPress) {
                pendingNativeLong = true
            } else {
                scheduleSceneRestore()
            }
            return@safeRun
        }

        executor?.execute(action)
    }

    /** 延时把场景恢复成用户原本的选择 */
    private fun scheduleSceneRestore() {
        cancelRestoreTask()
        val ctx = appContext ?: return
        val orig = originalScene ?: return
        val task = Runnable {
            safeRun("恢复场景") {
                NativeActionDispatcher.restoreScene(ctx.contentResolver, orig)
                lastWrittenScene = null
                log("已把 ${KeyConst.SETTING_SWITCH_STATE} 恢复为用户原值：$orig")
            }
        }
        restoreTask = task
        handler.postDelayed(task, KeyConst.SCENE_RESTORE_DELAY_MS)
    }

    private fun loadConfig(): Prefs.Snapshot = Prefs.read(appContext)

    private fun toast(ctx: Context, msg: String) {
        runCatching {
            handler.post { runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
        }
    }

    /** 把生效配置打进日志，便于确认配置读取链路是否通畅 */
    private fun dumpCurrentConfig(tag: String) {
        runCatching {
            val cfg = loadConfig()
            log(
                "[$tag] 当前配置（共 ${cfg.size} 项，" +
                    "读自${if (cfg.size == 0) "默认值" else "配置文件"}）：" +
                    "短按=${cfg.shortPress().label} | " +
                    "双击=${cfg.doublePress().label} | " +
                    "正面朝上（平放）长按=${cfg.faceUpLong().label} | " +
                    "背面朝上（平放）长按=${cfg.faceDownLong().label} | " +
                    "横屏（手持）长按=${cfg.landscapeLong().label} | " +
                    "竖屏（手持）长按=${cfg.portraitLong().label} | " +
                    "总开关=${cfg.enabled()} 提示=${cfg.toast()}"
            )
        }.onFailure { log("读取配置失败：$it") }
    }
}
