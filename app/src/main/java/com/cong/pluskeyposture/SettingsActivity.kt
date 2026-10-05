package com.cong.pluskeyposture

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.SwitchPreference
import android.provider.Settings
import android.widget.Toast
import com.cong.pluskeyposture.core.Action
import com.cong.pluskeyposture.core.ConfigProvider
import com.cong.pluskeyposture.core.KeyConst
import com.cong.pluskeyposture.core.Prefs

/**
 * 设置界面 —— 基于 framework 内置的 [PreferenceActivity]（零 androidx 依赖）。
 *
 * 这样做的两个好处：
 *  1. 界面是系统原生的 Preference 列表（分组标题 / 开关 / 下拉选择 / 分隔线），
 *     与「系统设置」风格完全一致，且自动适配深浅色与厂商主题；
 *  2. 不再依赖 androidx.appcompat / androidx.core，APK 体积大幅下降。
 *
 * 配置读写仍走 [Prefs.ui] 的「设备加密存储」（DE），开机早期 system_server 经
 * ContentProvider 即可读。Preference 项全部 `isPersistent=false`，
 * 由 `onPreferenceChangeListener` 手动 commit 到 DE prefs。
 */
@Suppress("DEPRECATION")
class SettingsActivity : PreferenceActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs.ui(this)
        Prefs.ensureDefaults(prefs)

        val screen = preferenceManager.createPreferenceScreen(this)
        setPreferenceScreen(screen)

        screen.addPreference(category("模块开关"))
        screen.addPreference(switchPref("启用模块", Prefs.K_ENABLED, true))
        screen.addPreference(switchPref("触发时弹出提示", Prefs.K_TOAST, false))

        screen.addPreference(category("按键映射"))
        screen.addPreference(listPref("短按", Prefs.K_SHORT_PRESS, Prefs.DEF_SHORT))
        // 双击槽位只提供「模块自实现」动作：原生动作双击=注入 781=单击效果，无独立语义
        screen.addPreference(listPref("双击", Prefs.K_DOUBLE_PRESS, Prefs.DEF_DOUBLE) {
            !it.isNative && it != Action.SYSTEM_DEFAULT
        })
        screen.addPreference(listPref("正面朝上（平放）+ 长按", Prefs.K_FACE_UP_LONG, Prefs.DEF_FACE_UP_LONG))
        screen.addPreference(listPref("背面朝上（平放）+ 长按", Prefs.K_FACE_DOWN_LONG, Prefs.DEF_FACE_DOWN_LONG))
        screen.addPreference(listPref("横屏（手持）+ 长按", Prefs.K_LANDSCAPE_LONG, Prefs.DEF_LANDSCAPE_LONG))
        screen.addPreference(listPref("竖屏（手持）+ 长按", Prefs.K_PORTRAIT_LONG, Prefs.DEF_PORTRAIT_LONG))

        screen.addPreference(category("操作"))
        screen.addPreference(button("恢复默认映射") {
            Prefs.resetToDefault(prefs)
            recreate()
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
        })
        screen.addPreference(button("查看当前配置（调试）") {
            AlertDialog.Builder(this)
                .setTitle("当前配置")
                .setMessage(buildConfigReport())
                .setPositiveButton("知道了", null)
                .show()
        })

        screen.addPreference(category("关于"))
        screen.addPreference(button("使用说明") {
            AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setMessage(helpText())
                .setPositiveButton("知道了", null)
                .show()
        })
        screen.addPreference(info("版本", versionName()))
    }

    // ==================== 构建辅助 ====================

    private fun category(title: String) = PreferenceCategory(this).apply { this.title = title }

    private fun switchPref(title: String, key: String, def: Boolean) =
        SwitchPreference(this).apply {
            this.title = title
            isPersistent = false // 手动持久化到 DE prefs（framework 的自动持久化写的是 CE）
            isChecked = prefs.getBoolean(key, def)
            summaryOn = "已开启"
            summaryOff = "已关闭"
            setOnPreferenceChangeListener { _, newValue ->
                prefs.edit().putBoolean(key, newValue as Boolean).commit()
                true
            }
        }

    private fun listPref(
        title: String,
        key: String,
        defId: String,
        filter: (Action) -> Boolean = { true }
    ): ListPreference {
        val actions = Action.entries.toList().filter(filter)
        return ListPreference(this).apply {
            this.title = title
            isPersistent = false // 手动持久化到 DE prefs
            entries = actions.map { it.label }.toTypedArray()
            entryValues = actions.map { it.id }.toTypedArray()
            val current = Action.fromId(prefs.getString(key, defId))
            // 当前值若不在过滤后的列表里（如旧版双击槽位选了原生动作），回退到默认值
            val shown = if (actions.any { it.id == current.id }) current else Action.fromId(defId)
            value = shown.id
            summary = shown.label
            setOnPreferenceChangeListener { _, newValue ->
                // 打 touched 标记：版本升级迁移时尊重用户手改，不刷回默认
                prefs.edit()
                    .putString(key, newValue as String)
                    .putBoolean(Prefs.P_TOUCHED + key, true)
                    .commit()
                summary = Action.fromId(newValue).label
                true
            }
        }
    }

    private fun button(title: String, onClick: () -> Unit) = Preference(this).apply {
        this.title = title
        setOnPreferenceClickListener { onClick(); true }
    }

    private fun info(title: String, summary: String) = Preference(this).apply {
        this.title = title
        this.summary = summary
        isSelectable = false
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ==================== 文案 ====================

    private fun helpText(): String =
        "1. 需先安装 LSPosed 并启用本模块，作用域勾选「系统框架」，重启后生效。\n" +
        "2. 短按 / 双击 / 长按各自独立映射；长按再按手机姿态细分（平放正/背面、手持横/竖屏）。\n" +
        "3. 双击槽位默认「无操作」时短按零延迟；给双击配了动作后，短按会等 300ms 区分单击/双击。\n" +
        "4. 带「系统原生」字样的动作由系统组件执行；其余由模块自己实现。\n" +
        "5. 更新模块后需重启手机。"

    private fun buildConfigReport(): String = buildString {
        appendLine("短按 = ${Prefs.shortPress(prefs).label}")
        appendLine("双击 = ${Prefs.doublePress(prefs).label}")
        appendLine("正面朝上（平放）长按 = ${Prefs.faceUpLong(prefs).label}")
        appendLine("背面朝上（平放）长按 = ${Prefs.faceDownLong(prefs).label}")
        appendLine("横屏（手持）长按 = ${Prefs.landscapeLong(prefs).label}")
        appendLine("竖屏（手持）长按 = ${Prefs.portraitLong(prefs).label}")
        appendLine()

        val bundle = Bundle()
        runCatching { ConfigProvider.readAllInto(bundle, prefs) }
        appendLine("可序列化字段数：${bundle.size()}（为 0 则 hook 侧只能读到默认值）")

        val providerOk = runCatching {
            packageManager.resolveContentProvider(ConfigProvider.AUTHORITY, PackageManager.GET_META_DATA) != null
        }.getOrDefault(false)
        appendLine("ContentProvider 已注册：$providerOk")

        val scene = runCatching {
            Settings.System.getString(contentResolver, KeyConst.SETTING_SWITCH_STATE)
        }.getOrNull()
        appendLine()
        appendLine("系统当前场景 oplus_action_button_switch_state = ${scene ?: "读取失败"}")
    }
}
