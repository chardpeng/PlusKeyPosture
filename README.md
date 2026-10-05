# Plus Key 姿态自定义（一加15 专用 LSPosed 模块）

把一加15 机身侧边的 **Plus 快捷键**（实测 `keyCode = 780` / `scanCode = 735`）扩展成「**按压时长 × 手机姿态**」的复合手势，实现原厂快捷键设置里做不到的功能。

**当前版本：v1.7.0**（versionCode 17）｜编译环境 AGP 9.0.0 + Gradle 9.1.0 + JDK 25

---

## 一、功能

### 默认手势映射

| 操作 | 触发条件 | 默认动作 |
|---|---|---|
| **短按** | 按下到抬起 < 400ms | 小布记忆 / 一键闪记 |
| **长按 + 正面朝上** | 按住 ≥500ms，屏幕朝天 | 微信付款码 |
| **长按 + 背面朝上** | 按住 ≥500ms，屏幕朝下 | 静音切换 |
| **长按 + 横屏** | 按住 ≥500ms，屏幕横向 | 自动旋转 |
| **长按 + 竖屏** | 按住 ≥500ms，屏幕纵向 | 相机 |

### 可选动作

设置界面里可替换的动作不止上面五个：

- 微信扫一扫 / 支付宝付款码
- 铃声 · 振动 · 静音 三态循环
- 自动旋转 开 / 关（单独指定）
- 手电筒、截屏、录音、免打扰、锁屏、播放暂停
- 静音 + 开启自动旋转（组合动作）

### 设计取舍

按下瞬间完成姿态判定并**冻结**，之后中途翻转手机不改变结果 —— 符合「长按时的姿态决定行为」的直觉。判定用**重力+加速度传感器的 Z 轴符号**判断正/背面，用**系统当前显示旋转状态**判断横/竖（传感器作兜底），采样窗口 120ms 多帧平均抗抖动。

---

## 二、实现原理

### 为什么必须用 LSPosed

一加15 取消了三段式 Alert Slider，改为 Plus Key。ColorOS 的派发链路：

```
Plus Key 物理按下
   ↓
InputReader / InputDispatcher
   ↓
com.android.server.policy.PhoneWindowManagerExtImpl
   #overrideInterceptKeyBeforeQueueing(KeyEvent, int)
   ↓  keyCode = 780, scanCode = 735
系统原生行为（唤起小布 / 设为 AI 键）
```

模块 hook 的就是这个 `overrideInterceptKeyBeforeQueueing`，由自己的逻辑接管按键。

**hook 点选在 `system_server` 带来的三个好处：**

1. **必须在 `系统框架` 作用域** —— 该方法是 `system_server` 进程里的，跑在别处根本不会被调用；
2. **不受普通应用权限限制** —— 可直接调 `AudioManager.setRingerMode`、直接写 `Settings.System`；
3. **改配置立即生效，无需重启**。

返回值是 `int`：AOSP 语义 `ACTION_PASS_TO_USER=1` 为放行，置 **0** 即吞掉事件。

### 模块结构

```
PlusKeyPosture/
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── build.bat / build.sh                  一键构建脚本
└── app/src/main/
    ├── AndroidManifest.xml               Xposed 模块元数据
    ├── assets/xposed_init                入口类声明
    ├── res/                              图标、主题、字符串
    └── java/com/cong/pluskeyposture/
        ├── SettingsActivity.kt           设置界面
        ├── core/
        │   ├── KeyConst.kt               keyCode / 阈值 / 姿态 / 动作枚举
        │   ├── PostureEngine.kt          姿态判定引擎（传感器 + 显示旋转）
        │   ├── ActionExecutor.kt         动作执行器（Intent / 系统 API）
        │   ├── NativeActionDispatcher.kt 原生 Action 名派发
        │   ├── ConfigProvider.kt         配置读取
        │   ├── Prefs.kt                  配置存储
        │   └── Log2.kt                   日志
        └── hook/
            └── MainHook.kt               Xposed 入口与按键拦截
```

### 关键设计决策

1. **入口类必须在 Zygote 阶段零副作用**
   字段初始化器（如 `Handler(Looper.getMainLooper())`）会在 `Class.newInstance()` 时执行，而那时主 Looper 还是 null，会导致模块**静默加载失败**。因此全部改用 `by lazy`。

2. **不通过 `getSystemContext()` 调 `getSharedPreferences()`**
   该 Context 包名是 `"android"`，没有数据目录，会抛 `No data directory found for package android`。而长按计时器回调跑在**主线程**，未捕获异常会让 **system_server 崩溃、整机进安全模式**。
   模块改为在 hook 侧**直接解析 `shared_prefs/*.xml` 文件**（顺带解决跨进程配置不同步），且所有分派路径统一用 `safeRun` 兜底。

3. **方法签名动态适配**
   ColorOS 上该方法是 `(KeyEvent, int)` **两参数**，写死三参数必然 `NoSuchMethodError`。改为枚举 `declaredMethods` 按名筛选。

4. **int 返回值不能写成 boolean**
   写 `param.result = true` 会抛 `IllegalArgumentException`，必须给 `0` / `1`。

5. **同按键经过多个 hook 点、回调在不同线程**
   `pressing` / `longPressFired` 等状态必须加 `@Synchronized`，否则长按会重复触发。

---

## 三、安装

### 前置条件

1. 手机已 root（Magisk / KernelSU / APatch 任一）；
2. root 管理器里开启 **Zygisk**；
3. 安装 **LSPosed**（Zygisk 版）；
4. 模块作用域**只勾选「系统框架」**。

### 步骤

```bash
adb install -r PlusKeyPosture-v1.7.0.apk
```

1. 打开 **LSPosed 管理器** → `模块` → 启用 **Plus Key 姿态自定义**；
2. 进入作用域设置 → 勾选 **系统框架**；
3. **重启手机**（仅首次激活需要）；
4. 桌面出现图标后点开即可调整映射。

### 建议让原厂快捷键"让位"

模块拦截已足够，但可作双保险：

```
设置 > 系统设置 > 快捷键 > 快捷键功能 → 选「一键闪记」或「无」
```

---

## 四、构建

```bash
# 工程根目录执行，wrapper 已内置，无需自行装 Gradle
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Windows Git Bash
./gradlew :app:assembleDebug
```

Windows 可双击 `build.bat`，Unix 执行 `./build.sh`（脚本会自动找 JDK）。

**环境要求**

| 项 | 值 |
|---|---|
| JDK | 25（Android Studio 自带 jbr 即可） |
| Gradle | 9.1.0（已写入 wrapper.properties） |
| AGP | 9.0.0（已写入顶层 build.gradle.kts） |
| SDK | platforms/android-37.0 + build-tools/36.0.0 |
| SDK 路径 | `local.properties`，**换机器必须改** |

Xposed API 用本地 jar（`app/libs/xposed-api-82.jar`），因为该依赖不在 Google Maven / Maven Central 上，用远程坐标会解析失败。它是 `compileOnly`，不会被打进 APK，运行时由 LSPosed 框架提供。

> AGP 9.0 起内置 Kotlin 支持。若降级回 AGP 8.x，需在顶层 `build.gradle.kts` 加回 `org.jetbrains.kotlin.android` 插件声明，并确保 `settings.gradle.kts` 的插件仓库里保留 `google()`。

> **签名**：release 构建会自动读取项目根目录的 `keystore.properties`（该文件不入库）。**没有该文件时会回退到 debug key**，所以直接 clone 下来即可构建，无需额外配置。

---

## 五、扩展新动作

在 `core/KeyConst.kt` 的 `Action` 枚举里加一项，然后在 `ActionExecutor.execute()` 的 `when` 里加分支即可。

---

## 六、实测事实（一加15 / PLK110）

| 层级 | 值 |
|---|---|
| 输入设备 | `/dev/input/event0`（`gpio-keys`） |
| 内核码 | `BTN_TRIGGER_HAPPY32` = **735**（未在 `gpio-keys.kl` 中映射） |
| Android keyCode | **780**（AOSP 里的 301 在本机无效） |
| 短按实测 | 289ms |
| 长按实测 | 918ms |

模块的 `isPlusKey()` 用 **scanCode 735 或 keyCode 780** 双判据，任一命中即接管。（触摸屏 keyCode=782 / scanCode=0 高频出现，必须排除）

---

## 免责声明

本模块仅用于个人设备的功能自定义与学习研究。请勿用于违反法律法规或他人设备。修改系统行为存在风险，请确保你知道如何恢复（在 root 管理器里禁用模块，或进入安全模式）。
