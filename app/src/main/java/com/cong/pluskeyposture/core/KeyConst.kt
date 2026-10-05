package com.cong.pluskeyposture.core

/**
 * 一加15 Plus Key 相关常量 —— **全部来自 2026-09-26 真机抓取 + 框架反汇编核对**。
 *
 * 一、物理键（内核层）
 *   /dev/input/event0 (gpio-keys) 的 BTN_TRIGGER_HAPPY32
 *   scanCode = 735（内核原始码，最稳定）
 *   keyCode  = 780 = KEYCODE_ACTION_BUTTON_CLICK，deviceId = 2
 *
 * 二、框架派发链（com.android.server.policy.StrategyActionButtonKeyLaunchApp）
 *   物理 780 ──> actionInterceptKeyBeforeQueueing() 拦截并 **返回 0（吞掉）**
 *              └─ 按下时 sendMessageDelayed(MSG_LONG_PRESS_DOWN, 495)
 *              └─ 抬起时：keyDownInterval < 495 → 注入 781 DOWN + 781 UP
 *                         否则若已注入长按 → 发 MSG_LONG_PRESS_UP
 *   782 DOWN 注入时机 = 按下后 495ms
 *   返回 0 = 拦截（不派发）；返回 1 = 放行
 *
 * 三、虚拟键（框架注入，deviceId = -1，source = 0x101）
 *   781 = KEYCODE_ACTION_BUTTON_SINGLE_TAP （短按）
 *   782 = KEYCODE_ACTION_BUTTON_LONG_PRESS （长按）
 *   ★ 双击（2026-09-27 真机抓包修正）：底层**没有专门的双击键码**，
 *     就是两次独立的 780 短按 → 两次独立 781。实测「第一次抬起 → 第二次按下」
 *     间隔约 102ms、两次 DOWN 间隔约 251ms。消费方 com.oplus.gesture 内部虽有
 *     `mDoubleClickCount` 计数逻辑，但双击动作是**硬编码**的、不查场景表，
 *     无法通过「改场景」控制。故本模块在状态机里**自己分辨单击 / 双击**
 *     （见 [KeyConst.DOUBLE_TAP_WINDOW_MS] 与 MainHook 的双击检测）。
 *
 * 四、消费方 com.oplus.gesture（PID 7075）
 *   收 781/782 → ActionKeyStartApp.startKeyAction(mIsSingleTap, switchState, currentScene)
 *   → 按系统注册表（content://uri.gesturemanagerprovider/action_button）执行对应动作
 *   ★ switchState 是「场景」不是「手势」：短按与长按共用同一个 scene，
 *     靠 mIsSingleTap 分流到 scene 的 clickAction / longPressAction。
 */
object KeyConst {

    /** 物理键 keyCode（实测 780） */
    const val KEYCODE_PLUS_KEY = 780

    /** 物理键 scanCode = 内核 BTN_TRIGGER_HAPPY32（704 + 31）。最稳定判据，不随 keyCode 映射变化 */
    const val SCANCODE_PLUS_KEY = 735

    /**
     * 框架注入的虚拟键：短按 = 781（KEYCODE_ACTION_BUTTON_SINGLE_TAP）
     * 实测由 `StrategyActionButtonKeyLaunchApp.interceptActionKeyUp` 注入 781 DOWN + 781 UP
     */
    const val KEY_VIRTUAL_SINGLE_TAP = 781

    /**
     * 框架注入的虚拟键：长按 = 782（KEYCODE_ACTION_BUTTON_LONG_PRESS）
     * 实测由 `OplusActionButtonHandler.handleMessage` 注入 782 DOWN / 782 UP
     */
    const val KEY_VIRTUAL_LONG_PRESS = 782

    /**
     * 原生长按判定阈值 = 495ms。
     *
     * 反汇编实证：`getLongPressTimeout()` **硬编码返回 495**（不是 AOSP 的 500）。
     * 我们吞掉物理键后由自己计时，因此改用同一个值，手感与原厂完全一致。
     */
    const val LONG_PRESS_TRIGGER_MS = 495L

    /**
     * 双击判定窗口 = 300ms。
     *
     * 第一次短按抬起后，若在该窗口内再次按下，判定为双击；否则判定为单击。
     * 与 AOSP `ViewConfiguration.getDoubleTapTimeout()` 一致。
     *
     * ★ 智能延迟：仅当「双击槽位」被配置了非「无操作」的动作时才启用该窗口；
     *   双击槽位为「无操作」时短按零延迟、行为与旧版完全一致。
     */
    const val DOUBLE_TAP_WINDOW_MS = 300L

    /** 超长按保护：超过该时长不再响应（避免误触） */
    const val MAX_PRESS_MS = 8000L

    /** 原生「场景」开关：`settings system oplus_action_button_switch_state` */
    const val SETTING_SWITCH_STATE = "oplus_action_button_switch_state"

    /** 改完场景后，多久恢复用户原本的选择（毫秒） */
    const val SCENE_RESTORE_DELAY_MS = 1500L
}

/**
 * 姿态枚举 —— v1.2.4 重新建模。
 *
 * ## ★ v1.2.4 的核心修正：把「平放 / 立着」提为**第一维度**
 *
 * 之前把「正面朝上」简单等同于 `重力 z > 0`，结果**竖屏手持（手机立着看屏幕）**
 * 时 z≈0、判不出正反面，兜底成了「正面朝上」→ 竖屏相机的槽位永远进不去。
 * 实测复现：静止手持竖屏必被识别为「正面朝上」；从桌面拿起的瞬间反而能判对
 * （那一瞬机身翻转，z 被甩到负值）。
 *
 * 正确模型是两个**正交**维度：
 *
 * ```
 *   ┌ 平放（|z| 大，重力压在 z 轴）── 此时才有「正/背面朝上」
 *   │     z > 0  屏幕朝天 = 正面朝上
 *   │     z < 0  屏幕朝地 = 背面朝上
 *   └ 立着（|z| 小，重力压在 x/y 轴）── 此时只有「横/竖屏」，无所谓正反面
 *         竖屏（重力在 y）
 *         横屏（重力在 x）
 * ```
 *
 * 「正/面朝上」是**平放**才有的属性；「横/竖屏」是**立着**才有的属性。
 * 两两组合本无意义，但用户实际绑定了 4 个槽位 + 1 个兜底，所以这里保留
 * 4 个平放象限 + 2 个立着姿态 + 1 个未知，共 7 态，见各枚举项说明。
 */
enum class Posture {

    // ==================== 平放（手机大致水平）====================

    /** 平放，屏幕朝天（正面向上的平放） */
    FACE_UP_PORTRAIT,

    /** 平放，屏幕朝天，且呈横向摆放 */
    FACE_UP_LANDSCAPE,

    /** 平放，屏幕朝地（背面向上的平放） */
    FACE_DOWN_PORTRAIT,

    /** 平放，屏幕朝地，且呈横向摆放 */
    FACE_DOWN_LANDSCAPE,

    // ==================== 立着（手持，手机大致竖直）====================

    /** ★ 手持竖屏 —— 立着看屏幕，用户实测要开相机的就是这一态 */
    HOLDING_PORTRAIT,

    /** 手持横屏 —— 立着横向持握 */
    HOLDING_LANDSCAPE,

    /** 传感器数据不足或判据不足 —— **不猜、不兜底**，长按时不执行任何动作 */
    UNKNOWN;
}

/**
 * 可绑定的动作。**只保留真正会用的，不做任何"以防万一"的冗余项**。
 *
 * ## 两类动作
 * 1. **原生动作**（[nativeScene] != null）：模块改写 `oplus_action_button_switch_state`
 *    后注入 781/782，由系统原生消费方 `com.oplus.gesture` 执行 —— 100% 原厂实现，
 *    画面、动效、震动、二级设置全部与系统自带功能一致。
 * 2. **自实现动作**（[nativeScene] == null）：系统键位表里没有对应场景、或原生通道
 *    实测不可用的 —— 打开相机、微信/支付宝付款码与扫一扫、静音↔响铃，共 6 项。
 *
 * [id] 用于持久化，改动需谨慎（旧值见 [fromId] 的别名表）。
 */
enum class Action(val id: String, val label: String, val nativeScene: String? = null) {

    /** 什么都不做 */
    NONE("none", "无操作"),

    /** 原样交还系统：仍注入虚拟键，但场景保持用户自己在「系统设置 > 快捷键」里选的那个 */
    SYSTEM_DEFAULT("system_default", "交还系统（用系统设置里选的功能）"),

    // ==================== 走系统原生实现（7 项，与系统键位表一一对应）====================

    NATIVE_FLASH_MEMORY("native_flash_memory", "一键闪记（系统原生）", "flash_memory"),

    NATIVE_FLASHLIGHT("native_flashlight", "手电筒（系统原生）", "flash_light"),
    NATIVE_SCREENSHOT("native_screenshot", "截屏（系统原生）", "screen_shot"),
    NATIVE_RECORDING("native_recording", "录音（系统原生）", "recording"),
    NATIVE_TRANSLATE("native_translate", "翻译（系统原生）", "translate"),
    NATIVE_DND("native_dnd", "免打扰（系统原生）", "no_disturb"),
    NATIVE_RINGER("native_ringer", "静音/振动/响铃（系统原生）", "ring_mode"),

    // ==================== 模块自实现（系统键位表里没有，或原生通道不可用）====================

    /**
     * 打开相机。
     *
     * ## 为什么走自实现而不是原生 camera 场景（2026-09-27 真机实证）
     *
     * 系统场景表 `scene_id=6 camera` 的两条通道：
     * ```
     * click_action_info      = com.oplus.camera.feature.keymagic.KeyMagicService  (service) ← 需要私有权限，外调起不来
     * long_press_action_info = #Intent;action=com.oplus.action.CAMERA;...          (activity)
     * ```
     * 实测（root 直接执行）：
     *  - 注入 782（长按通道）→ **相机不启动**；
     *  - 注入 781（短按通道）→ **相机不启动**；
     *  - 直接 `am start -a com.oplus.action.CAMERA --ei mode 0 --ez rear true` → **相机正常启动**。
     *
     * 即：只有**直接发这个 Intent** 才有效。所以相机不走原生注入，由模块自己
     * 拉起系统相机的 KeyMagic 入口（同一个 Intent，参数逐位一致）。
     */
    NATIVE_CAMERA("native_camera", "打开相机"),

    /**
     * 识码（调用 ColorOS 系统「扫一扫」的**识码**能力）。
     *
     * ## 为什么不用相机 App
     *
     * 2026-09-27 真机实证：ColorOS 的扫码 UI **不在** `com.oplus.camera`，而在
     * `com.coloros.ocrscanner`（扫一扫 App，底部 tab：识物 / 识文 / 识码 / 文档 / 翻译）。
     * 系统为此留了一个公开 action：
     * ```
     * coloros.intent.action.CODE_SCANNER
     *   → com.coloros.ocrscanner/com.oplus.scanner.ui.main.CameraActivity
     * ```
     * 实测：
     *  - `am start -a coloros.intent.action.CODE_SCANNER` → ✅ 启动，且**直接落在「识码」tab**
     *    （截图确认该 tab 蓝字高亮，非相机主界面）；
     *  - 扫到码后由 `com.coloros.ocrscanner` **自行判断码类型并跳转对应 App**
     *    （用户实测：扫出链接/支付码会自动打开相应应用），模块无需拿扫码结果；
     *  - 锁屏状态（`isKeyguardShowing=true`）下同样能拉起并 Resumed。
     *
     * 所以"扫码"这件事**全部交给系统**，模块只负责把这个入口点到「识码」页。
     * 注意 `com.oplus.scanengine`（扫码引擎）虽然在相机包里，但**UI 在 ocrscanner**，
     * 直接调相机的识码能力反而没有这个入口稳。
     */
    CODE_SCANNER("code_scanner", "识码（系统扫一扫）"),

    WECHAT_PAY("wechat_pay", "微信付款码"),
    WECHAT_SCAN("wechat_scan", "微信扫一扫"),

    /**
     * 支付宝付款码（收付款）。
     *
     * 走支付宝官方 scheme `alipays://platformapi/startapp?appId=20000056`，
     * 实测（2026-09-27）直接落到收付款页 `com.alipay.mobile.onsitepay.merge.OnsitepayActivity`。
     * scheme 比显式 Activity 稳 —— 支付宝的 Activity 类名随版本漂移，scheme 长期有效。
     */
    ALIPAY_PAY("alipay_pay", "支付宝付款码"),

    /**
     * 支付宝扫一扫。
     *
     * 走支付宝官方 scheme `alipays://platformapi/startapp?appId=10000007`，
     * 实测（2026-09-27）直接落到扫码主界面 `com.alipay.mobile.scan.as.main.MainCaptureActivity`。
     */
    ALIPAY_SCAN("alipay_scan", "支付宝扫一扫"),

    /**
     * 二态切换：响铃 ⇄ 静音（只切铃声，**会显式关掉勿扰**）。
     *
     * 系统的 ring_mode 是三态循环（响铃→振动→静音），不是用户要的二态。
     * ColorOS 切静音时会连带开勿扰，本动作切完会强制把勿扰关掉 —— 见
     * [ActionExecutor.toggleSilentRing]。
     *
     * 注：用户机器自带「翻转移到静音」，此动作默认已不绑定任何槽位，
     * 保留仅为可选。
     */
    SILENT_TOGGLE("silent_toggle", "静音 ↔ 响铃（切换）");

    val isNative: Boolean get() = nativeScene != null

    companion object {

        /**
         * 由持久化的 id 还原动作。
         *
         * 别名表处理历史版本用过的 id：
         *  - `xiaobu`（v1.1.0 试图自己拉起小布记忆页面，实测是错的）→ 原生 flash_memory；
         *  - 不带 `native_` 前缀的旧 id → 对应的原生动作；
         *  - 已删除的动作（支付宝 / 锁屏 / 播放暂停 / 三态循环等）→ NONE。
         */
        fun fromId(id: String?): Action {
            if (id == null) return NONE
            entries.firstOrNull { it.id == id }?.let { return it }

            val aliases: Map<String, Action> = mapOf(
                // v1.1.0 旧 id
                "xiaobu" to NATIVE_FLASH_MEMORY,
                // 历史上等同原生动作的 id
                "camera" to NATIVE_CAMERA,
                "flashlight" to NATIVE_FLASHLIGHT,
                "screenshot" to NATIVE_SCREENSHOT,
                "voice_record" to NATIVE_RECORDING,
                "dnd_toggle" to NATIVE_DND,
                "silent_on" to SILENT_TOGGLE,
                // 已下线的动作 → 统一落到 NONE（用户会在设置里重新选）
                "silent_toggle_cleardnd" to SILENT_TOGGLE,
                "ringer_toggle" to SILENT_TOGGLE,
                "rotate_on" to NONE,
                "rotate_off" to NONE,
                "silent_rotate" to NONE,
                "lock_screen" to NONE,
                "play_pause" to NONE,
            )
            return aliases[id] ?: NONE
        }
    }
}
