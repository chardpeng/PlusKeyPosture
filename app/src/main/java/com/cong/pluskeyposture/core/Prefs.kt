package com.cong.pluskeyposture.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * 配置存储。
 *
 * ## 三个必须解决的坑
 *
 * **坑 1：system_server 的 Context 没有数据目录**
 * hook 侧拿到的 Context 来自 `ActivityThread.getSystemContext()`，
 * `packageName` 是 `"android"`，直接调 `getSharedPreferences()` 会抛
 * `RuntimeException: No data directory found for package android`。
 *
 * **坑 2：跨进程 SharedPreferences 不会自动同步**
 * 设置界面跑在 App 进程，hook 跑在 system_server 进程，两边各有一份内存缓存。
 *
 * **坑 3（v1.2.0 修掉的真正阻塞项）：SELinux 直读文件被拒**
 * v1.1.0 靠绝对路径直读模块数据目录下的 shared_prefs XML
 * （`/data/user_de/0/com.cong.pluskeyposture/shared_prefs/` 里的配置文件），
 * 实测在 ColorOS 16 上被 MLS 类别挡住：
 * ```
 * open failed: EACCES (Permission denied)
 * 文件标签 u:object_r:app_data_file:s0:c112,c257,c512,c768
 * ```
 * 结果 hook 侧**永远拿不到配置**，`enabled=false` 形同虚设。
 *
 * ## v1.2.0 的读取链（三级）
 * 1. **ContentProvider（首选）** —— `com.cong.pluskeyposture.config`。
 *    由 App 进程读自己的 prefs，绕开全部 SELinux 与缓存问题；system_server
 *    属于 core uid，无需任何权限即可访问。
 * 2. **直读 XML（兜底）** —— 万一 provider 没起来（例如模块刚装还没启动 App），
 *    仍然尝试直读文件；在允许的机型/ROM 上这条路依然有效。
 * 3. **内置默认值** —— 保证永远不会因为读配置失败而"按键没反应"。
 *
 * UI 侧仍用标准 SharedPreferences 读写；按键频率很低，每次读一个几 KB 的配置无性能问题。
 */
object Prefs {

    private const val TAG = "PlusKeyPosture"
    const val NAME = "plus_key_posture_config"

    /** 模块自己的包名，用于定位数据目录 */
    const val MODULE_PKG = "com.cong.pluskeyposture"

    // ---- 键名 ----
    /** 短按（无姿态概念） */
    const val K_SHORT_PRESS = "short_press"

    /** 双击（两次快速短按，无姿态概念） */
    const val K_DOUBLE_PRESS = "double_press"

    const val K_FACE_UP_LONG = "face_up_long"          // 正面朝上 + 长按
    const val K_FACE_DOWN_LONG = "face_down_long"      // 背面朝上 + 长按
    const val K_LANDSCAPE_LONG = "landscape_long"      // 横屏 + 长按
    const val K_PORTRAIT_LONG = "portrait_long"        // 手持竖屏 + 长按

    // ---- 开关 ----
    const val K_ENABLED = "enabled"                    // 模块总开关
    const val K_TOAST = "toast_feedback"               // 触发弹提示

    /** 迁移标记 v2（silent_on -> silent_toggle） */
    const val K_MIGRATED_V2 = "migrated_v2_defaults"

    /** 迁移标记 v3（xiaobu -> native_flash_memory 等旧 id 升级） */
    const val K_MIGRATED_V3 = "migrated_v3_native"

    /**
     * 迁移标记 v5（v1.2.4 -> v1.2.5）。
     *
     * 把「背面朝上（平放）长按」的默认值从 `silent_toggle` 换成
     * `native_recording`。原因：用户机器自带「翻转移到静音」，
     * 静音需求已被覆盖，该槽位让给更适合"背面朝上平放"场景的录音。
     *
     * 只改**用户从未手改过**的槽位 —— 若用户自己在设置里改过，尊重其选择。
     */
    const val K_MIGRATED_V5 = "migrated_v5_recording"

    /**
     * 迁移标记 v6（v1.2.5 -> v1.2.6）。
     *
     * 把「手持竖屏长按」的默认值从 `native_camera` 换成 `code_scanner`
     * （系统扫一扫的识码页）—— 扫到码会自动跳对应 App，见 [Action.CODE_SCANNER]。
     *
     * 只改**用户从未手改过**的槽位。用户手改过（包括自己改成别的动作）的一律保留。
     */
    const val K_MIGRATED_V6 = "migrated_v6_code_scanner"

    /**
     * 迁移标记 v7（v1.2.8 -> v1.2.9）。
     *
     * 自动旋转功能已删除：把「手持横屏长按」里绑定 `rotate_toggle` 的旧值
     * 刷成「无操作」，避免用户点开设置看到一个已不存在的动作。
     *
     * 只改**用户从未手改过**的槽位；用户手改过的一律保留。
     */
    const val K_MIGRATED_V7 = "migrated_v7_drop_rotate"

    /**
     * 迁移标记 v8（v1.6.0 -> v1.7.0）。
     *
     * 双击槽位只支持「模块自实现」动作：原生动作双击 = 注入 781 = 单击效果，
     * 没有独立的双击语义（用户实测确认）。故把双击槽位里已绑定的
     * 原生动作（isNative）或「交还系统」（SYSTEM_DEFAULT）刷成「无操作」。
     *
     * 注意：此处**不看 touched 标记** —— 即使用户手改过，绑原生动作本身
     * 就是无效配置，一律重置。
     */
    const val K_MIGRATED_V8 = "migrated_v8_double_tap_self_impl"

    /**
     * 迁移标记 v4（v1.2.0 -> v1.2.1）。
     *
     * v1.2.0 有一个隐蔽问题：**覆盖安装时用户看不到新默认值**。
     * `ensureDefaults` 只在"键不存在"时写默认，而覆盖安装会保留旧 prefs，
     * 于是 v1.1.0 时代写进去的 `silent_toggle`（当时是竖屏兜底的默认值）
     * 被原样留到 v1.2.0，把 v1.2.0 新的 `native_camera` 默认值完全遮蔽了。
     *
     * 叠加"显示旋转恒为 0 → 全部判成竖屏"的 bug，最终表现就是
     * 用户实测的：**横竖屏长按都变成切静音**。
     *
     * v4 迁移的策略：把**用户从未手动改过**的槽位刷新为当前版本默认值；
     * 用户手改过的槽位一律保留（每个槽位有一个 `touched_<key>` 标记）。
     */
    const val K_MIGRATED_V4 = "migrated_v4_defaults"

    /** 某个槽位是否被用户手动改过的标记前缀 */
    const val P_TOUCHED = "touched_"

    // ---- 默认映射（正是用户要的五条规则）----
    /** 短按 = 一键闪记（走系统原生 flash_memory 场景） */
    const val DEF_SHORT = "native_flash_memory"

    /**
     * 双击 = 无操作。
     *
     * 双击槽位默认不绑定（置为「无操作」可让短按保持零延迟，见
     * [KeyConst.DOUBLE_TAP_WINDOW_MS] 的智能延迟说明）；由用户在设置里自行改绑。
     */
    const val DEF_DOUBLE = "none"

    /** 正面朝上（平放）长按 = 微信付款码（模块自实现） */
    const val DEF_FACE_UP_LONG = "wechat_pay"

    /**
     * 背面朝上（平放）长按 = 录音（走系统原生 recording 场景）。
     *
     * 选录音的理由：手机背面朝上平放桌上时，人往往不在看屏幕，
     * 适合做"不需要看屏幕"的动作 —— 开会/上课时手机放桌上，长按开始录音。
     *
     * 注：曾用过 `silent_toggle`（静音↔响铃），但用户机器自带"翻转移到静音"
     * 已覆盖静音需求，该槽位让给录音更有价值。
     */
    const val DEF_FACE_DOWN_LONG = "native_recording"

    /**
     * 手持横屏长按 = 无操作。
     *
     * 曾绑定「自动旋转开关」（`rotate_toggle`），v1.2.9 起该功能已删除
     * （实测走系统官方链路仍无法让屏幕在静止姿态下立即重算方向）。
     * 槽位保留，默认置为无操作，由用户在设置里自行改绑。
     */
    const val DEF_LANDSCAPE_LONG = "none"

    /**
     * 手持竖屏长按 = 识码（系统扫一扫的识码页）。
     *
     * v1.2.6 起从 `native_camera` 换成 `code_scanner`：用户实测系统扫一扫的
     * 「识码」tab 扫到码后会自动跳转对应 App，比单纯开相机更贴合"竖屏拿来对着
     * 东西扫一下"的场景。详见 [Action.CODE_SCANNER]。
     */
    const val DEF_PORTRAIT_LONG = "code_scanner"

    // ==================== UI 侧（App 进程）====================

    /**
     * 设置界面的 prefs 实例。
     *
     * 【为什么用「设备加密存储」】
     * 默认的 `getSharedPreferences()` 会写到**凭据加密（CE）**目录
     * `/data/user/0/<pkg>/shared_prefs/`，该目录在开机早期（用户解锁前）
     * 不可访问。改用**设备加密（DE）**目录 `/data/user_de/0/<pkg>/shared_prefs/`，
     * 开机即可读，system_server 侧（经 provider 或直读）无障碍。
     */
    fun ui(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 设置界面写入后调用，把改动同步落盘（用 commit 而非 apply，保证立即可见） */
    fun save(prefs: SharedPreferences) {
        prefs.edit().commit()
    }

    /**
     * 首次启动补齐默认值（不覆盖已有配置），并执行一次性迁移。
     */
    fun ensureDefaults(p: SharedPreferences) {
        val e = p.edit()
        if (!p.contains(K_SHORT_PRESS)) e.putString(K_SHORT_PRESS, DEF_SHORT)
        if (!p.contains(K_DOUBLE_PRESS)) e.putString(K_DOUBLE_PRESS, DEF_DOUBLE)
        if (!p.contains(K_FACE_UP_LONG)) e.putString(K_FACE_UP_LONG, DEF_FACE_UP_LONG)
        if (!p.contains(K_FACE_DOWN_LONG)) e.putString(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
        if (!p.contains(K_LANDSCAPE_LONG)) e.putString(K_LANDSCAPE_LONG, DEF_LANDSCAPE_LONG)
        if (!p.contains(K_PORTRAIT_LONG)) e.putString(K_PORTRAIT_LONG, DEF_PORTRAIT_LONG)
        if (!p.contains(K_ENABLED)) e.putBoolean(K_ENABLED, true)
        if (!p.contains(K_TOAST)) e.putBoolean(K_TOAST, false)

        // 迁移 1：背面朝上长按 silent_on -> silent_toggle（静音↔响铃，双向）
        if (!p.getBoolean(K_MIGRATED_V2, false)) {
            if (p.getString(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG) == "silent_on") {
                e.putString(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
                Log2.i("迁移 v2：背面朝上长按 silent_on -> $DEF_FACE_DOWN_LONG")
            }
            e.putBoolean(K_MIGRATED_V2, true)
        }

        // 迁移 2：把 v1.1.0 的旧动作 id 升级为「走系统原生场景」的新 id。
        // 旧的 xiaobu（模块自己拉起小布记忆页面）实测是错的 ——
        // 系统真正的「一键闪记」是 flash_memory 场景，必须切过去。
        if (!p.getBoolean(K_MIGRATED_V3, false)) {
            for (k in listOf(
                K_SHORT_PRESS, K_FACE_UP_LONG, K_FACE_DOWN_LONG,
                K_LANDSCAPE_LONG, K_PORTRAIT_LONG
            )) {
                val old = p.getString(k, null) ?: continue
                if (Action.entries.any { it.id == old }) continue   // 已是新 id
                val upgraded = Action.fromId(old)
                if (upgraded != Action.NONE) {
                    e.putString(k, upgraded.id)
                    Log2.i("迁移 v3：$k $old -> ${upgraded.id}")
                }
            }
            e.putBoolean(K_MIGRATED_V3, true)
        }

        // 迁移 3（v4）：把「用户从未手改过」的槽位刷新为当前版本默认值。
        // 见 K_MIGRATED_V4 的说明 —— 这是修「横竖屏都切静音」的关键一步：
        // 覆盖安装时旧默认值会遮蔽新默认值，必须显式刷掉。
        if (!p.getBoolean(K_MIGRATED_V4, false)) {
            val slots = mapOf(
                K_SHORT_PRESS to DEF_SHORT,
                K_FACE_UP_LONG to DEF_FACE_UP_LONG,
                K_FACE_DOWN_LONG to DEF_FACE_DOWN_LONG,
                K_LANDSCAPE_LONG to DEF_LANDSCAPE_LONG,
                K_PORTRAIT_LONG to DEF_PORTRAIT_LONG,
            )
            for ((k, def) in slots) {
                // 没有 touched_ 标记 => 用户从未手动设置过 => 可以安全刷成新默认
                if (!p.getBoolean(P_TOUCHED + k, false)) {
                    val old = p.getString(k, null)
                    if (old != def) {
                        e.putString(k, def)
                        Log2.i("迁移 v4：$k ${old ?: "(空)"} -> $def（用户未手改，刷新默认值）")
                    }
                } else {
                    Log2.i("迁移 v4：$k 保留用户手改值 ${p.getString(k, null)}")
                }
            }
            e.putBoolean(K_MIGRATED_V4, true)
        }

        // 迁移 4（v5）：背面朝上长按 silent_toggle -> native_recording。
        // 用户机器自带「翻转移到静音」，静音需求已被覆盖；
        // 该槽位换成更适合"背面朝上平放"姿态的录音。
        // 只刷用户从未手改过的槽位。
        if (!p.getBoolean(K_MIGRATED_V5, false)) {
            if (!p.getBoolean(P_TOUCHED + K_FACE_DOWN_LONG, false)) {
                val old = p.getString(K_FACE_DOWN_LONG, null)
                if (old == "silent_toggle" || old == null) {
                    e.putString(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
                    Log2.i("迁移 v5：背面朝上长按 ${old ?: "(空)"} -> $DEF_FACE_DOWN_LONG")
                }
            } else {
                Log2.i("迁移 v5：背面朝上长按保留用户手改值 ${p.getString(K_FACE_DOWN_LONG, null)}")
            }
            e.putBoolean(K_MIGRATED_V5, true)
        }

        // 迁移 5（v6）：手持竖屏长按 native_camera -> code_scanner（识码）。
        // 用户要求把"打开相机"换成"调系统扫一扫的识码"——扫到码自动跳对应 App。
        // 只刷用户从未手改过的槽位。
        if (!p.getBoolean(K_MIGRATED_V6, false)) {
            if (!p.getBoolean(P_TOUCHED + K_PORTRAIT_LONG, false)) {
                val old = p.getString(K_PORTRAIT_LONG, null)
                if (old == "native_camera" || old == "camera" || old == null) {
                    e.putString(K_PORTRAIT_LONG, DEF_PORTRAIT_LONG)
                    Log2.i("迁移 v6：竖屏长按 ${old ?: "(空)"} -> $DEF_PORTRAIT_LONG")
                }
            } else {
                Log2.i("迁移 v6：竖屏长按保留用户手改值 ${p.getString(K_PORTRAIT_LONG, null)}")
            }
            e.putBoolean(K_MIGRATED_V6, true)
        }

        // 迁移 6（v7）：删除自动旋转功能后，把手持横屏长按里的 rotate_toggle 刷成无操作。
        // 只刷用户从未手改过的槽位。
        if (!p.getBoolean(K_MIGRATED_V7, false)) {
            if (!p.getBoolean(P_TOUCHED + K_LANDSCAPE_LONG, false)) {
                val old = p.getString(K_LANDSCAPE_LONG, null)
                if (old == "rotate_toggle" || old == null) {
                    e.putString(K_LANDSCAPE_LONG, DEF_LANDSCAPE_LONG)
                    Log2.i("迁移 v7：横屏长按 ${old ?: "(空)"} -> $DEF_LANDSCAPE_LONG")
                }
            } else {
                Log2.i("迁移 v7：横屏长按保留用户手改值 ${p.getString(K_LANDSCAPE_LONG, null)}")
            }
            e.putBoolean(K_MIGRATED_V7, true)
        }

        // 迁移 7（v8）：双击槽位只支持自实现动作，原生动作/交还系统刷成无操作。
        // 不看 touched —— 绑原生动作本身即无效配置。
        if (!p.getBoolean(K_MIGRATED_V8, false)) {
            val old = p.getString(K_DOUBLE_PRESS, DEF_DOUBLE)
            val act = Action.fromId(old)
            if (act.isNative || act == Action.SYSTEM_DEFAULT) {
                e.putString(K_DOUBLE_PRESS, DEF_DOUBLE)
                Log2.i("迁移 v8：双击槽位 $old -> $DEF_DOUBLE（原生动作双击无独立语义）")
            }
            e.putBoolean(K_MIGRATED_V8, true)
        }
        e.commit()
    }

    /** 恢复默认映射（同时清掉 touched 标记，让后续升级迁移可以正常刷新） */
    fun resetToDefault(p: SharedPreferences) {
        val e = p.edit()
            .putString(K_SHORT_PRESS, DEF_SHORT)
            .putString(K_DOUBLE_PRESS, DEF_DOUBLE)
            .putString(K_FACE_UP_LONG, DEF_FACE_UP_LONG)
            .putString(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
            .putString(K_LANDSCAPE_LONG, DEF_LANDSCAPE_LONG)
            .putString(K_PORTRAIT_LONG, DEF_PORTRAIT_LONG)
        for (k in listOf(
            K_SHORT_PRESS, K_DOUBLE_PRESS, K_FACE_UP_LONG, K_FACE_DOWN_LONG,
            K_LANDSCAPE_LONG, K_PORTRAIT_LONG
        )) {
            e.putBoolean(P_TOUCHED + k, false)
        }
        e.commit()
        Log2.i("配置已恢复默认")
    }

    // ---- UI 侧便捷读取 ----

    fun actionOf(sp: SharedPreferences, key: String, defId: String): Action =
        Action.fromId(sp.getString(key, defId) ?: defId)

    fun shortPress(sp: SharedPreferences) = actionOf(sp, K_SHORT_PRESS, DEF_SHORT)
    fun doublePress(sp: SharedPreferences) = actionOf(sp, K_DOUBLE_PRESS, DEF_DOUBLE)
    fun faceUpLong(sp: SharedPreferences) = actionOf(sp, K_FACE_UP_LONG, DEF_FACE_UP_LONG)
    fun faceDownLong(sp: SharedPreferences) = actionOf(sp, K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
    fun landscapeLong(sp: SharedPreferences) = actionOf(sp, K_LANDSCAPE_LONG, DEF_LANDSCAPE_LONG)
    fun portraitLong(sp: SharedPreferences) = actionOf(sp, K_PORTRAIT_LONG, DEF_PORTRAIT_LONG)

    // ==================== hook 侧（system_server 进程）====================

    /**
     * 解析出的配置快照。值只允许 String / Boolean / Int / Long / Float
     * （这样能原样塞进 Bundle 跨进程传输）。
     */
    class Snapshot(private val map: Map<String, Any?>) {

        fun str(key: String, def: String): String = (map[key] as? String) ?: def
        fun bool(key: String, def: Boolean): Boolean = (map[key] as? Boolean) ?: def
        fun action(key: String, defId: String): Action = Action.fromId(str(key, defId))

        val size: Int get() = map.size

        fun shortPress() = action(K_SHORT_PRESS, DEF_SHORT)
        fun doublePress() = action(K_DOUBLE_PRESS, DEF_DOUBLE)
        fun faceUpLong() = action(K_FACE_UP_LONG, DEF_FACE_UP_LONG)
        fun faceDownLong() = action(K_FACE_DOWN_LONG, DEF_FACE_DOWN_LONG)
        fun landscapeLong() = action(K_LANDSCAPE_LONG, DEF_LANDSCAPE_LONG)
        fun portraitLong() = action(K_PORTRAIT_LONG, DEF_PORTRAIT_LONG)

        fun enabled() = bool(K_ENABLED, true)
        fun toast() = bool(K_TOAST, false)

        /**
         * 六个槽位是否全部为「无操作」。
         *
         * 全部是 NONE 时模块**完全不碰物理键**，原样放行给系统，
         * 避免"用户根本不想用这个模块，按键却被吞掉"。
         */
        fun isAllNone(): Boolean = listOf(
            shortPress(), doublePress(), faceUpLong(), faceDownLong(), landscapeLong(), portraitLong()
        ).all { it == Action.NONE }

        /**
         * 长按按姿态取动作。
         *
         * ## v1.2.4 映射规则（平放 / 立着 分家）
         *
         * 姿态是两个正交维度，槽位按**第一维度**分流：
         *
         * ```
         * 平放（手机水平）── 只看正反面，不看横竖
         *   FACE_UP_*    -> faceUpLong()     （默认：微信付款码）
         *   FACE_DOWN_*  -> faceDownLong()   （默认：静音 ↔ 响铃）
         *
         * 立着（手持）── 只看横竖，不看正反面
         *   HOLDING_PORTRAIT  -> portraitLong()   （默认：识码 / 系统扫一扫）
         *   HOLDING_LANDSCAPE -> landscapeLong()  （默认：无操作）
         * ```
         *
         * 之前的 bug：竖屏手持 z≈0 被兜底成 FACE_UP_PORTRAIT，
         * 于是竖屏永远走「正面朝上」槽位，相机的槽位进不去 ——
         * 详见 [Posture] 的说明。
         */
        fun longActionFor(posture: Posture): Action = when (posture) {
            // 平放：按正反面
            Posture.FACE_UP_PORTRAIT, Posture.FACE_UP_LANDSCAPE -> faceUpLong()
            Posture.FACE_DOWN_PORTRAIT, Posture.FACE_DOWN_LANDSCAPE -> faceDownLong()

            // 立着（手持）：按横竖
            Posture.HOLDING_PORTRAIT -> portraitLong()
            Posture.HOLDING_LANDSCAPE -> landscapeLong()

            // 判不出：什么都不做（MainHook 已在更早处拦截，这里是防御层）
            Posture.UNKNOWN -> Action.NONE
        }
    }

    /**
     * 读取配置（hook 侧每次按键调用）。
     *
     * 三级回退，任一级拿到数据即返回。
     */
    fun read(context: Context?): Snapshot {
        // ---- 1) ContentProvider（首选，绕开 SELinux / 跨进程缓存）----
        readViaProvider(context)?.let { return Snapshot(it) }

        // ---- 2) 直读 XML（兜底）----
        val file = resolvePrefsFile(context)
        if (file == null || !file.exists()) {
            Log2.i("配置读取：provider 与文件均不可用，使用默认值")
            return Snapshot(emptyMap())
        }
        return try {
            FileInputStream(file).use { fis -> Snapshot(parseXml(fis)) }
        } catch (t: Throwable) {
            Log2.w("解析配置失败：${t.message}，改用默认值")
            Snapshot(emptyMap())
        }
    }

    /**
     * 通过模块 App 的 ContentProvider 读配置。
     *
     * system_server 属于 core uid，可以直接访问未导出/未授权的 provider。
     * 任何异常都吞掉并返回 null，交由下一级兜底。
     */
    @Suppress("DEPRECATION")
    private fun readViaProvider(context: Context?): Map<String, Any?>? {
        val ctx = context ?: return null
        return runCatching {
            val bundle: Bundle = ctx.contentResolver.call(
                ConfigProvider.URI_CONFIG, ConfigProvider.METHOD_READ_ALL, null, null
            ) ?: return null

            if (!bundle.getBoolean("__ok", false)) return null

            val map = HashMap<String, Any?>()
            for (key in bundle.keySet()) {
                if (key.startsWith("__")) continue
                map[key] = bundle.get(key)
            }
            if (map.isEmpty()) return null
            Log2.i("配置读取：经 ContentProvider 成功，共 ${map.size} 项")
            map
        }.onFailure {
            Log2.w("经 ContentProvider 读取失败（将回退直读文件）：${it.message}")
        }.getOrNull()
    }

    /**
     * 定位 `shared_prefs/<NAME>.xml` 的绝对路径。
     *
     * 依次尝试多个候选目录，兼容 Android 的几种数据存储位置。
     */
    private fun resolvePrefsFile(context: Context?): File? {
        if (context != null) {
            runCatching { context.dataDir }.getOrNull()?.let { dir ->
                val f = File(dir, "shared_prefs/$NAME.xml")
                if (f.exists()) return f
            }
        }
        for (base in CANDIDATE_DATA_DIRS) {
            val f = File("$base/$MODULE_PKG/shared_prefs/$NAME.xml")
            if (f.exists()) return f
        }
        return File("${CANDIDATE_DATA_DIRS.first()}/$MODULE_PKG/shared_prefs/$NAME.xml")
    }

    private val CANDIDATE_DATA_DIRS = listOf(
        "/data/user_de/0",
        "/data/user/0",
        "/data/data",
    )

    /**
     * 解析 Android SharedPreferences 的 XML。
     *
     * ```xml
     * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
     * <map>
     *     <string name="short_press">native_flash_memory</string>
     *     <boolean name="enabled" value="true" />
     * </map>
     * ```
     */
    private fun parseXml(fis: FileInputStream): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(fis, "utf-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                val key = parser.getAttributeValue(null, "name")
                if (key != null) {
                    when (tag) {
                        "string" -> out[key] = parser.nextText()
                        "boolean" -> out[key] = parser.getAttributeValue(null, "value") == "true"
                        "int" -> out[key] =
                            parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                        "long" -> out[key] =
                            parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                        "float" -> out[key] =
                            parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                        "set" -> out[key] = true
                        else -> {}
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
