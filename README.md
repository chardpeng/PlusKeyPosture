# Plus Key 姿态自定义（一加15 专用 LSPosed 模块）

把一加15 机身侧边的 **Plus 快捷键**（实测 keyCode = **780** / scanCode = **735**）扩展成「**按压时长 × 手机姿态**」的复合手势，实现原厂快捷键设置里做不到的功能。

> **状态**：工程已在本机**编译通过并产出 APK**（AGP 9.0.0 + Gradle 9.1.0 + JDK 25），当前版本 **v1.7.0**（versionCode 17）。
> 构建产物在 `dist/PlusKeyPosture-v<版本号>.apk`（release 签名，可直接 `adb install`）。
> 注意：v1.5.0 起为 release 构建（约 2.1 MB），v1.0.0–v1.4.0 为 debug 构建（约 7.5 MB），两者签名不同，debug 版升级到 release 版需先卸载。
> **尚未在真机上完整验证所有运行时行为** —— 改装完请按第六节逐条测，有问题看第七节。

## 你要的五条规则（默认已配好）

| 操作 | 触发条件 | 默认动作 |
|---|---|---|
| **短按** | 按下到抬起 < 400ms | 小布记忆 / 一键闪记 |
| **长按 + 正面朝上** | 按住 500ms 以上，屏幕朝天 | 微信付款码 |
| **长按 + 背面朝上** | 按住 500ms 以上，屏幕朝下 | 开启静音模式 |
| **长按 + 横屏** | 按住 500ms 以上，屏幕处于横向 | 切换自动旋转 |
| **长按 + 竖屏** | 按住 500ms 以上，屏幕处于纵向 | 打开相机 |

其中「正面/背面」由**重力+加速度传感器**的 Z 轴符号判定，「横屏/竖屏」由**系统当前显示旋转状态**判定（传感器作为兜底）。判定全部在**按下瞬间**完成并冻结，符合"长按时的姿态决定行为"的直觉。

---

## 一、原理说明（为什么必须用 LSPosed）

一加15 取消了经典的三段式 Alert Slider，改为 Plus Key。ColorOS 的派发链路是：

```
Plus Key 物理按下
   ↓
InputReader/InputDispatcher
   ↓
com.android.server.policy.PhoneWindowManagerExtImpl
   #overrideInterceptKeyBeforeQueueing(KeyEvent, int)
   ↓  keyCode = 780, scanCode = 735
系统原生行为（唤起小布 / 设为 AI 键）
```

本模块 hook 的就是这个 `overrideInterceptKeyBeforeQueueing`（返回值为 `int`，置 **0** 即吞掉事件，AOSP 语义 `ACTION_PASS_TO_USER=1` 为放行），然后由模块自己的逻辑接管。因此：

- **必须**作用域选 `系统框架`，因为该方法是 `system_server` 进程里的；
- 只有在这个进程里，才能直接调 `AudioManager.setRingerMode`、直接写 `Settings.System`，不受普通应用权限限制；
- 改完配置**不用重启**，改完立即生效。

---

## 二、准备工作

1. 手机已 root，且 root 方案为 **Magisk / KernelSU / APatch** 之一；
2. 在 root 管理器里开启 **Zygisk**；
3. 安装 **LSPosed**（Zygisk 版），重启后在通知栏或拨号盘输入 `*#*#5776733#*#*` 能打开 LSPosed 管理器；
4. 确认已开启 `设置 > 关于手机` 里的开发者选项（后面装 APK 要用到 ADB 或直接文件管理器安装）。

---

## 三、构建 APK

### 方式 A：Android Studio（推荐，最省事）

1. 打开 Android Studio → `Open` → 选择本工程根目录 `PlusKeyPosture/`；
2. 等待 Gradle Sync 完成（首次会下载 Gradle 9.1 和依赖，需要网络）；
3. 菜单 `Build > Build Bundle(s) / APK(s) > Build APK(s)`；
4. 构建完成后点通知里的 `locate`，APK 在：
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### 方式 B：命令行（已验证可用）

```bash
# 工程根目录下执行。wrapper 已内置（含 gradle-wrapper.jar），无需自行安装 Gradle。
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Windows Git Bash
./gradlew :app:assembleDebug
```

Windows CMD 下用 `gradlew.bat :app:assembleDebug`，
或直接双击 `build.bat`（Windows）、执行 `./build.sh`（Unix，脚本会自动找 JDK）。

> **构建环境（本机已实测编译通过）**：
> - **JDK 25** — 用 Android Studio 自带的 `jbr` 即可
> - **Gradle 9.1.0** — 已写入 `gradle/wrapper/gradle-wrapper.properties`
> - **AGP 9.0.0** — 已写入顶层 `build.gradle.kts`
> - **SDK**：`platforms/android-37.0` + `build-tools/36.0.0`
> - SDK 路径在 `local.properties`，**换机器必须改这一行**
>
> **Xposed API 用的是本地 jar**（`app/libs/xposed-api-82.jar`），
> 因为这个依赖不在 Google Maven / Maven Central 上，用远程坐标会解析失败。
> 它是 `compileOnly`，不会被打进 APK——运行时由 LSPosed 框架提供。

> **本工程已用 debug keystore 签名**（见 `app/build.gradle.kts` 的 `signingConfig`），
> 这样可以避免"未签名 APK 无法安装"的问题，LSPosed 也能正常加载。

> **注意**：AGP 9.0 起内置 Kotlin 支持，若你把它降级回 AGP 8.x，
> 需要同时在顶层 `build.gradle.kts` 加回 `org.jetbrains.kotlin.android` 插件声明，
> 并确保 `settings.gradle.kts` 的插件仓库里保留 `google()`。

### 已构建好的 APK

工程里已附带一份构建成功的产物，可直接安装：

```
dist/PlusKeyPosture-v1.7.0.apk
```

---

## 四、安装与激活

```bash
# 1. 安装 APK（-r 表示覆盖安装）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者在手机上用文件管理器直接点开 APK 安装（需要允许安装未知来源应用）。

然后：

1. 打开 **LSPosed 管理器** → 底部 `模块` → 找到 **Plus Key 姿态自定义** → 打开右侧开关；
2. 点击模块名进入作用域设置 → **只勾选「系统框架」**（System Framework）；
3. **重启手机**（仅首次激活需要）；
4. 重启后桌面会出现「Plus Key 姿态自定义」图标，点开可调整映射。

---

## 五、关键一步：让原厂快捷键"让位"

模块虽然会拦截按键，但为了双保险，建议去系统设置里把原厂快捷键功能设成一个无害项：

```
设置 > 系统设置 > 快捷键 > 快捷键功能
→ 选「一键闪记」或「无」（不要选「打开小布」等，避免干扰）
```

因为我们的 hook 返回 `true` 已经吞掉了事件，这一步只是保险。

---

## 六、逐条验证

装好后按顺序测，方便定位问题：

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 屏幕亮着，手机**竖着**平放桌面（正面朝上），**短按**快捷键 | 触发小布闪记/记忆 |
| 2 | 手机**屏幕朝上**放平，**长按** 0.5 秒以上 | 弹出微信付款码 |
| 3 | 手机**屏幕朝下**扣在桌上，**长按** 0.5 秒以上 | 静音模式开启（有提示） |
| 4 | 手机**横过来**拿在手里，**长按** 0.5 秒以上 | 自动旋转开关切换 |
| 5 | 手机**竖着**拿在手里，**长按** 0.5 秒以上 | 打开相机 |

> **提示**：测第 2/3 条时，手机放在桌面上判定最准；拿在手里晃动可能让 Z 轴抖动。
> 采样窗口是 120ms，多帧平均后会比较稳。

---

## 七、日志与排错

### 看模块日志

```bash
adb logcat -s LSPosed-Bridge:V Xposed:V | grep PlusKeyPosture
```

或直接在 **LSPosed 管理器 → 日志** 里筛选 `PlusKeyPosture`。

正常启动后应该看到：

```
[PlusKeyPosture] Zygote 初始化完成，模块已加载
[PlusKeyPosture] 发现系统框架进程，开始挂载 hook
[PlusKeyPosture] 已挂载 hook：com.android.server.policy.PhoneWindowManagerExtImpl#overrideInterceptKeyBeforeQueueing(KeyEvent, int)
[PlusKeyPosture] 已获取 system_server Context，姿态引擎与执行器就绪
[PlusKeyPosture] 共挂载 N 个 hook 点
```

按一下键应该出现：

```
[PlusKeyPosture] 短按 -> 小布记忆 / 一键闪记
```

或

```
[PlusKeyPosture] 长按 + 姿态=FACE_UP_PORTRAIT -> 微信付款码
```

### 常见问题

**Q1：按键完全没反应，日志里连"发现系统框架进程"都没有**

- 检查 LSPosed 里模块是否启用、作用域是否勾了「系统框架」；
- 检查是否真的重启过（首次激活必须重启）；
- 检查 Zygisk 是否开启。

**Q2：有"发现系统框架进程"但"未找到任何目标 hook 点"**

说明你的 ColorOS 版本里类名变了。在 Xposed 日志里会看到模块尝试过的类名。请用下面的命令抓真实的派发类：

```bash
# 边按键边抓，找带 KEYLOG 的 tag
adb logcat | grep -iE "KEYLOG|OplusKey"
```

社区实测一加15 的 tag 是 `KEYLOG_PhoneWindowManagerExtImpl` 和 `KEYLOG_OplusKeyEventUtil`。
把抓到的完整类名告诉我，我改 `MainHook.TARGET_CLASSES` 即可。

**Q3：hook 挂上了但按键没反应，keyCode 对不上**

模块内置了**按键诊断日志**：前 20 次经过拦截点的按键会无条件打印真实码值，格式如下：

```
[PlusKeyPosture] Plus Key：keyCode=780 scanCode=735 action=0 repeat=0 flags=8
```

直接按一下 Plus 键，看日志里打印出什么。如果出现 `<== 命中 Plus Key` 说明识别正常，
问题在后续动作执行；如果打印的是别的 keyCode，把该行发出来改 `KeyConst` 即可。

**已在本机实测确认的按键事实**（一加15 / ColorOS 16 / PLK110_16.0.9.400）：

| 层级 | 值 |
|---|---|
| 输入设备 | `/dev/input/event0`（`gpio-keys` / `soc:gpio-hall-sensor`） |
| 内核码 | `BTN_TRIGGER_HAPPY32` = **735**（未在 `gpio-keys.kl` 中映射） |
| Android keyCode | **780**（实测；AOSP 里 301 在本机无效） |
| 短按实测 | 289ms |
| 长按实测 | 918ms（1 秒左右） |

因此模块的 `isPlusKey()` 用 **scanCode 735 或 keyCode 780** 双判据，任一命中即接管。
（触摸屏 keyCode=782/scanCode=0，高频出现，必须排除）

如果你想自己复核，用这条命令（先插上 USB）：

```bash
adb shell su -c "getevent -lt /dev/input/event0"
# 按一下 Plus 键，会看到：
#   EV_KEY  BTN_TRIGGER_HAPPY32  DOWN
#   EV_KEY  BTN_TRIGGER_HAPPY32  UP
```

**Q4：小布记忆不触发**

ColorOS 的「一键闪记」需要先手动开启：
```
设置 > AI > 小布记忆 > 开启「一键闪记」
```
开启后三指上滑应该能触发闪记。如果三指上滑能用而快捷键不能，说明 action 名需要调整，请看日志里哪个 action 失败。

另外，ColorOS 16 上 Plus Key 支持的功能清单存在这里，可以用来核对可用项：

```bash
adb shell settings get secure action_button_support_all_fun
# 实测输出：
# flash_memory;ring_mode;no_disturb;camera;flash_light;recording;translate;screen_shot;nothing

adb shell settings get system oplus_action_button_switch_state
# 当前绑定的功能（会被本模块拦截并接管）
```

**Q5：微信付款码打不开**

微信大版本更新可能改 Activity 名。当前用的是社区长期有效的：
```
com.tencent.mm/com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI
```
如果失效，可在设置界面把该动作临时改成「微信扫一扫」验证链路是否正常，然后把新的付款码 Activity 反馈给我。

**Q6：静音的提示弹出了但状态栏图标没变**

`AudioManager.setRingerMode` 在 system_server 里调用后，ColorOS 的 SystemUI 有时不会主动刷新图标。可以额外发一个广播：
```bash
adb shell am broadcast -a android.media.RINGER_MODE_CHANGED
```
（模块已在 `setSilent` 里预留了扩展点，需要的话我加上。）

---

## 八、按键诊断（适配新 ROM 用）

模块内置按键诊断，无需手动开关。在 `MainHook.kt` 里由这个常量控制打印次数：

```kotlin
/** 前 N 次经过拦截点的按键事件会无条件打印 keyCode/scanCode */
private const val PROBE_FIRST_N = 20
```

日志格式：

```
[PlusKeyPosture] Plus Key：keyCode=780 scanCode=735 action=0 repeat=0 flags=8
[PlusKeyPosture] 按键事件：keyCode=24 scanCode=115 action=0 repeat=0 flags=0x8
```

规则：
- 只要 keyCode 或 scanCode 命中 Plus Key，**每次都会打印**（并标注 `<== 命中 Plus Key`）
- 其它按键在前 20 次内也会打印，之后就静默，避免刷屏

**实测参考**（一加15 / ColorOS 16 / Android 16）：Plus 键表现为 `keyCode=780 scanCode=735`。

适配新 ROM 的完整流程：
1. 装模块 → 重启 → 按一下 Plus 键 → 看日志打印的真实码值
2. 若与 780/735 不同，改 `core/KeyConst.kt` 里的 `KEYCODE_PLUS_KEY` / `SCANCODE_PLUS_KEY`
3. 若日志里**完全没有** `[PlusKeyPosture]` 输出，说明 hook 点没挂上，
   查启动日志里的"已挂载 hook"那几行，把类名加到 `MainHook.TARGET_CLASSES`

---

## 九、可扩展的动作

设置界面里可选的动作不止五条默认值，还包括：

- 微信扫一扫 / 支付宝付款码
- 铃声·振动·静音 三态循环
- 自动旋转 开 / 关（单独指定）
- 手电筒、截屏、录音、免打扰、锁屏、播放暂停
- 静音 + 开启自动旋转（组合动作）

要加新动作，在 `core/KeyConst.kt` 的 `Action` 枚举里加一项，然后在 `ActionExecutor.execute()` 的 `when` 里加分支即可。

---

## 十、工程结构

```
PlusKeyPosture/
├── build.gradle.kts              顶层构建配置
├── settings.gradle.kts
├── gradle.properties
├── build.bat / build.sh          一键构建脚本
└── app/
    ├── build.gradle.kts          模块配置（compileSdk 34, Kotlin 1.9.22）
    └── src/main/
        ├── AndroidManifest.xml   Xposed 模块元数据声明
        ├── assets/xposed_init    入口类声明
        ├── res/                  图标、主题、字符串
        └── java/com/cong/pluskeyposture/
            ├── SettingsActivity.kt        设置界面
            ├── core/
            │   ├── KeyConst.kt            keyCode/阈值/姿态/动作枚举
            │   ├── PostureEngine.kt       姿态判定引擎（传感器）
            │   ├── ActionExecutor.kt      动作执行器（Intent/系统API）
            │   └── Prefs.kt               配置存储
            └── hook/
                └── MainHook.kt            Xposed 入口与按键拦截
```

---

## 免责声明

本模块仅用于个人设备的功能自定义与学习研究。请勿用于违反法律法规或他人设备。修改系统行为存在一定风险，请确保你知道如何通过 Magisk 关闭模块（在 Magisk 里禁用模块或进入安全模式）来恢复。

---

## 真机验证状态（一加15 PLK110 / ColorOS 16 / Android 16）

| 项目 | 状态 | 证据 |
|---|---|---|
| 模块被 LSPosed 加载 | ✅ | 日志 `Zygote 初始化完成，模块已加载` |
| 6 个 hook 点挂载 | ✅ | `PhoneWindowManagerExtImpl#overrideInterceptKeyBeforeQueueing(KeyEvent, int)` 等 |
| 取得 system_server Context | ✅ | `已获取 system_server Context，姿态引擎与执行器就绪` |
| 短按 → 小布记忆 | ✅ | 日志 `短按 -> 小布记忆 / 一键闪记` |
| 长按 + 正面朝上竖屏 → 微信付款码 | ✅ | 日志 `长按 + 姿态=FACE_UP_PORTRAIT -> 微信付款码`，且 `WalletOfflineCoinPurseUI` 确实被拉起 |
| 原厂"手电筒"动作被拦截 | ✅ | logcat 中无任何 flashlight/torch 记录 |
| system_server 稳定性 | ✅ | 连续多次触发零崩溃 |

待实测（依赖真实传感器姿态）：背面朝上长按（静音）、横屏长按（自动旋转）、竖屏长按（相机）。

### 无需真人按键的测试方法

`input keyevent 780` **不经过** `interceptKeyBeforeQueueing`，测不出来。用内核级注入：

```bash
# 短按
adb shell su -c "sendevent /dev/input/event0 1 735 1; sendevent /dev/input/event0 0 0 0; \
                 sleep 0.1; sendevent /dev/input/event0 1 735 0; sendevent /dev/input/event0 0 0 0"
# 长按：把 sleep 0.1 改成 1
```

### 已知坑与设计对策

1. **入口类必须在 Zygote 阶段零副作用**
   `private val handler = Handler(Looper.getMainLooper())` 会在 `Class.newInstance()`
   时执行，而那时主 Looper 还是 null → 模块静默加载失败。全部改 `by lazy`。

2. **不要用 `getSystemContext()` 的 Context 调 `getSharedPreferences()`**
   该 Context 的包名是 `"android"`，没有数据目录，会抛
   `No data directory found for package android`；而长按计时器回调跑在**主线程**，
   未捕获异常会让 **system_server 崩溃、整机进安全模式**。
   本模块改为 hook 侧**直接解析 `shared_prefs/*.xml` 文件**（顺带解决跨进程不同步），
   且所有分派路径统一用 `safeRun` 兜底。

3. **方法签名必须动态适配**
   ColorOS 上 `overrideInterceptKeyBeforeQueueing` 是 `(KeyEvent, int)` **两个参数**，
   写死三个参数必然 `NoSuchMethodError`。改为枚举 `declaredMethods` 按名筛选。

4. **int 返回值的方法不能写 boolean**
   `interceptKeyBeforeQueueing` 返回 int，AOSP 语义为 `ACTION_PASS_TO_USER=1` 放行、
   **0 为吞掉**。写 `param.result = true` 会抛 `IllegalArgumentException`。

5. **同一按键经过多个 hook 点，回调在不同线程**
   `pressing` / `longPressFired` 等状态必须加 `@Synchronized`，否则长按重复触发。
