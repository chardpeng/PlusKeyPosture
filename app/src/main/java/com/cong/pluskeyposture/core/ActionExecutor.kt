package com.cong.pluskeyposture.core

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * 动作执行器 —— **只负责"系统注册表里没有对应场景"的那几个动作**。
 *
 * ## 设计边界（v1.2.1 精简后）
 *
 * 系统的「设置 > 快捷键」提供了一整套现成功能（一键闪记 / 相机 / 手电筒 /
 * 截屏 / 录音 / 翻译 / 免打扰 / 静音振动响铃）。这些一律**走原生注入**
 * （见 [NativeActionDispatcher]），本类完全不参与 —— 不再保留"原生失败就自己实现"
 * 的兜底分支。理由：
 *  1. 原生注入是同一台手机上的既有能力，能注入就能生效，不存在"注入失败"的常态；
 *  2. 那些兜底实现（自己拉相机、自己反射截屏……）与原生实现**行为不一致**，
 *     会让"同一个动作在不同时机表现不同"，反而制造难以复现的 bug。
 *
 * 所以这里只剩四类真正**没有原生场景可复用**的动作：
 *  - 打开相机（`camera` 场景的 click 通道是私有 service，外调起不来 → 直接发 action）
 *  - 识码（系统扫一扫 `com.coloros.ocrscanner`，键位表里没有）
 *  - 微信付款码 / 微信扫一扫 / 支付宝付款码 / 支付宝扫一扫（三方 App，系统键位表里没有）
 *  - 静音 ↔ 响铃（系统的 ring_mode 是三态循环，用户要的是二态）
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "PlusKeyPosture"

        private const val PKG_WECHAT = "com.tencent.mm"

        /** 微信付款码（离线钱包）。实测长期有效的显式组件 */
        private const val WECHAT_PAY_ACTIVITY =
            "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"

        /** 微信扫一扫 */
        private const val WECHAT_SCAN_ACTIVITY =
            "com.tencent.mm.plugin.scanner.ui.BaseScanUI"

        private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        /**
         * 支付宝官方 scheme —— 比显式 Activity 稳（Activity 类名随版本漂移）。
         *
         * 2026-09-27 真机实测：
         *  - 付款码 `appId=20000056` → 收付款页 `com.alipay.mobile.onsitepay.merge.OnsitepayActivity`
         *  - 扫一扫 `appId=10000007` → 扫码主界面 `com.alipay.mobile.scan.as.main.MainCaptureActivity`
         */
        private const val ALIPAY_PAY_SCHEME = "alipays://platformapi/startapp?appId=20000056"
        private const val ALIPAY_SCAN_SCHEME = "alipays://platformapi/startapp?appId=10000007"

        /** 支付宝扫一扫的显式组件（scheme 解析不到时的兜底） */
        private const val ALIPAY_SCAN_ACTIVITY = "com.alipay.mobile.scan.as.main.MainCaptureActivity"

        /** 支付宝付款码的显式组件（scheme 解析不到时的兜底） */
        private const val ALIPAY_PAY_ACTIVITY = "com.alipay.mobile.onsitepay.merge.OnsitepayActivity"

        /**
         * 系统相机「快捷键」专用 action —— 就是系统场景表 `camera` 里
         * `long_press_action_info` 那一串 Intent 的 action。
         *
         * 2026-09-27 真机实测：直接 `am start -a com.oplus.action.CAMERA --ei mode 0 --ez rear true`
         * 相机必定启动；而注入 781/782 都不启动。故模块直接照发这个 Intent。
         */
        private const val ACTION_OPLUS_CAMERA = "com.oplus.action.CAMERA"

        /**
         * ColorOS 系统「扫一扫」的识码 action。
         *
         * `coloros.intent.action.CODE_SCANNER` → `com.coloros.ocrscanner/...CameraActivity`，
         * 实测直接落在「识码」tab，扫到码后由它自己跳转对应 App（详见 [Action.CODE_SCANNER]）。
         */
        private const val ACTION_CODE_SCANNER = "coloros.intent.action.CODE_SCANNER"

        /** 扫一扫主界面的显式组件（action 解析不到时的兜底） */
        private const val OCRSCANNER_PKG = "com.coloros.ocrscanner"
        private const val OCRSCANNER_CLS = "com.oplus.scanner.ui.main.CameraActivity"
    }

    private val audioManager: AudioManager? by lazy {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
            .getOrNull()
    }

    /**
     * 执行动作。只处理三类"模块特有"的动作，其余一律忽略
     * （原生动作不该走到这里 —— 走到了说明配置有问题，日志会提示）。
     */
    fun execute(action: Action) {
        try {
            when (action) {
                Action.NATIVE_CAMERA -> openCamera()
                Action.CODE_SCANNER -> openCodeScanner()

                Action.WECHAT_PAY -> openComponent(PKG_WECHAT, WECHAT_PAY_ACTIVITY)
                Action.WECHAT_SCAN -> openComponent(PKG_WECHAT, WECHAT_SCAN_ACTIVITY)

                Action.ALIPAY_PAY -> openAlipayPay()
                Action.ALIPAY_SCAN -> openAlipayScan()

                Action.SILENT_TOGGLE -> toggleSilentRing()

                Action.NONE, Action.SYSTEM_DEFAULT -> Unit

                else -> Log2.w(
                    "动作 ${action.label} 属于原生场景，不应由模块执行（请检查配置）"
                )
            }
        } catch (t: Throwable) {
            Log2.e("动作执行失败：${action.id}", t)
        }
    }

    // ==================== 静音 ↔ 响铃 ====================
    //
    // 【ColorOS 16 实测结论 —— 修正记录】
    //
    // 早期注释曾写"用 cmd audio set-ringer-mode SILENT 得到 zen=ZEN_MODE_OFF，
    // 不会进勿扰"。**该结论是错误的**：那一次测量时勿扰本来就是关的，
    // 所以看到的是"旧状态"而非"本次操作的结果"。
    //
    // ★ 用户实测（真机）：走 `AudioManager.setRingerMode(SILENT)` 静音时，
    //   ColorOS **会连带把勿扰打开**（色系把"静音"与"勿扰"在设置层耦合）。
    //
    // 因为用户机器自带「翻转移到静音」已覆盖静音需求，本动作默认不再绑定；
    // 但既然保留了实现，就要做对：**切完后强制把勿扰关掉**，只留纯静音。

    private fun ringerMode(): Int =
        runCatching { audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL }
            .getOrDefault(AudioManager.RINGER_MODE_NORMAL)

    /**
     * 静音 ↔ 响铃 双态切换，**不连带勿扰**。
     *
     * 流程：
     *  1. 切换 ringerMode（静音 / 响铃，不经过振动）；
     *  2. **显式把勿扰（zen mode）关掉** —— ColorOS 切静音时会顺带开勿扰，
     *     这里把它纠正回 `INTERRUPTION_FILTER_ALL`（= ZEN_MODE_OFF）。
     */
    private fun toggleSilentRing() {
        val current = ringerMode()
        val target = if (current == AudioManager.RINGER_MODE_SILENT) {
            AudioManager.RINGER_MODE_NORMAL
        } else {
            AudioManager.RINGER_MODE_SILENT
        }
        val ok = runCatching {
            audioManager?.ringerMode = target
            ringerMode() == target
        }.getOrDefault(false)

        val dndCleared = clearDndIfOn()

        Log2.i(
            "静音↔响铃：${modeName(current)} -> ${modeName(target)}（生效=$ok）" +
                " | 勿扰=${if (dndCleared) "已强制关闭" else "本来就是关的"}"
        )
    }

    /**
     * 若勿扰是开着的，关掉它。返回是否执行了关闭动作。
     *
     * `INTERRUPTION_FILTER_ALL` 即 ZEN_MODE_OFF（不拦截任何通知）。
     */
    private fun clearDndIfOn(): Boolean {
        val nm = runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull() ?: return false

        val filter = runCatching { nm.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) return false

        return runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }.onFailure {
            Log2.w("关闭勿扰失败：${it.javaClass.simpleName} ${it.message}")
        }.getOrDefault(false)
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "静音"
        AudioManager.RINGER_MODE_VIBRATE -> "振动"
        else -> "响铃"
    }

    // ==================== 打开相机 ====================

    /**
     * 打开系统相机。
     *
     * 照搬系统场景表 `camera` 里那条 Intent：
     * `com.oplus.action.CAMERA` + `mode=0` + `rear=true`。
     *
     * 实测这条路**必定启动相机**，而注入 781/782 都不行 —— 见 [Action.NATIVE_CAMERA]。
     * 若该 action 解析不到（个别 ROM 差异），退回到标准 `IMAGE_CAPTURE` 的
     * 主入口 `com.oplus.camera/.Camera`，保证仍能落到相机。
     */
    private fun openCamera(): Boolean {
        val byAction = runCatching {
            val intent = Intent(ACTION_OPLUS_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("mode", 0)
                putExtra("rear", true)
            }
            if (context.packageManager?.resolveActivity(intent, 0) == null) {
                Log2.w("相机 action $ACTION_OPLUS_CAMERA 无组件可解析，改用显式组件")
                null
            } else {
                context.startActivity(intent)
                Log2.i("已启动相机：action=$ACTION_OPLUS_CAMERA (mode=0, rear=true)")
                true
            }
        }.getOrElse {
            Log2.e("发相机 Intent 失败：${it.message}")
            null
        }
        if (byAction == true) return true
        return openComponent("com.oplus.camera", "com.oplus.camera.Camera")
    }

    // ==================== 识码（系统扫一扫）====================

    /**
     * 拉起 ColorOS 系统「扫一扫」的**识码**页。
     *
     * 首选发 `coloros.intent.action.CODE_SCANNER` —— 实测它会直接选中「识码」tab
     * （而不是扫一扫的主界面），扫到码后由 `com.coloros.ocrscanner` 自行识别类型并
     * 跳转对应 App，模块**不需要**也**拿不到**扫码结果。
     *
     * 锁屏下同样可拉起（实测 Activity 能被 Resumed），无需额外解锁处理。
     *
     * 若该 action 解析不到（个别 ROM 精简过扫一扫），退回到显式组件
     * `com.coloros.ocrscanner/com.oplus.scanner.ui.main.CameraActivity`。
     */
    private fun openCodeScanner(): Boolean {
        val byAction = runCatching {
            val intent = Intent(ACTION_CODE_SCANNER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager?.resolveActivity(intent, 0) == null) {
                Log2.w("识码 action $ACTION_CODE_SCANNER 无组件可解析，改用显式组件")
                null
            } else {
                context.startActivity(intent)
                Log2.i("已启动识码：action=$ACTION_CODE_SCANNER")
                true
            }
        }.getOrElse {
            Log2.e("发识码 Intent 失败：${it.message}")
            null
        }
        if (byAction == true) return true
        return openComponent(OCRSCANNER_PKG, OCRSCANNER_CLS)
    }

    // ==================== 支付宝 ====================

    /**
     * 打开支付宝付款码（收付款）。
     *
     * 首选官方 scheme `alipays://platformapi/startapp?appId=20000056`（长期有效，
     * 不随版本漂移），解析不到时退回显式组件 `OnsitepayActivity`。
     */
    private fun openAlipayPay(): Boolean {
        val byScheme = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ALIPAY_PAY_SCHEME)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager?.resolveActivity(intent, 0) == null) {
                Log2.w("支付宝付款码 scheme 无组件可解析，改用显式组件")
                null
            } else {
                context.startActivity(intent)
                Log2.i("已启动支付宝付款码：scheme=alipays://...appId=20000056")
                true
            }
        }.getOrElse {
            Log2.e("发支付宝付款码 scheme 失败：${it.message}")
            null
        }
        if (byScheme == true) return true
        return openComponent(PKG_ALIPAY, ALIPAY_PAY_ACTIVITY)
    }

    /**
     * 打开支付宝扫一扫。
     *
     * 首选官方 scheme `alipays://platformapi/startapp?appId=10000007`，
     * 解析不到时退回显式组件 `MainCaptureActivity`。
     */
    private fun openAlipayScan(): Boolean {
        val byScheme = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ALIPAY_SCAN_SCHEME)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager?.resolveActivity(intent, 0) == null) {
                Log2.w("支付宝扫一扫 scheme 无组件可解析，改用显式组件")
                null
            } else {
                context.startActivity(intent)
                Log2.i("已启动支付宝扫一扫：scheme=alipays://...appId=10000007")
                true
            }
        }.getOrElse {
            Log2.e("发支付宝扫一扫 scheme 失败：${it.message}")
            null
        }
        if (byScheme == true) return true
        return openComponent(PKG_ALIPAY, ALIPAY_SCAN_ACTIVITY)
    }

    // ==================== 启动组件 ====================

    /**
     * 启动一个显式组件。
     *
     * 用 PackageManager 预检可解析性 —— 避免 `startActivity` 不报错但什么都没发生的假成功。
     */
    private fun openComponent(pkg: String, cls: String): Boolean = runCatching {
        val intent = Intent().apply {
            setClassName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager?.resolveActivity(intent, 0) == null) {
            Log2.w("组件不可解析：$pkg/$cls（App 未安装或版本不匹配）")
            false
        } else {
            context.startActivity(intent)
            Log2.i("已启动：$pkg/$cls")
            true
        }
    }.getOrElse {
        Log2.e("启动 $pkg/$cls 失败：${it.message}")
        false
    }
}
