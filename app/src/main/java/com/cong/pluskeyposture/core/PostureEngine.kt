package com.cong.pluskeyposture.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 姿态判定引擎 —— v1.2.4。
 *
 * ## ★ v1.2.4 的根本性修正：把「平放 / 立着」提为**第一维度**
 *
 * ### 之前错在哪（真机实测复现）
 *
 * v1.2.3 用「重力 z 的符号」直接判正反面，并写了一句兜底 `faceUp ?: true`。
 * 结果：**竖屏手持看屏幕**时手机是**立着**的，重力压在 Y 轴、**z ≈ 0**，
 * `decideFaceUp(0)` 判不出 → 兜底成"正面朝上" → 落到 `FACE_UP_PORTRAIT`
 * → 走「正面朝上」槽位，**竖屏相机的槽位永远进不去**。
 *
 * 用户实测原话：*「有加速度的时候可以正常打开，静止的时候会识别成正面朝上」*。
 * 原因是「从桌面拿起」那一瞬机身翻转，z 被甩到大负值，凑巧避开了这个兜底。
 *
 * ### 正确模型（两个正交维度）
 *
 * ```
 *   平放（|z| 大，重力压在 z 轴）──  此时才有「正/背面朝上」
 *        z > 0  屏幕朝天 = 正面朝上
 *        z < 0  屏幕朝地 = 背面朝上
 *
 *   立着（|z| 小，重力压在 x/y 轴）──  此时只有「横/竖屏」，无所谓正反面
 *        竖屏（重力主要在 y）
 *        横屏（重力主要在 x）
 * ```
 *
 * 「正面朝上」是**平放**才有的属性；「横竖屏」是**立着**才有的属性。
 * 把"立着"错当"正面朝上"，是这个 bug 的全部原因。
 *
 * ### 判定规则（本版）
 *
 * | 条件 | 结果 |
 * |---|---|
 * | `\|z\| > 8.49`（平放，允许 30° 倾斜） | 看 z 符号 → `FACE_UP_*` / `FACE_DOWN_*` |
 * | `\|z\| ≤ 8.49`（立着） | 看 x/y → `HOLDING_PORTRAIT` / `HOLDING_LANDSCAPE` |
 * | x/y 也判不出 | **`UNKNOWN`（不猜、不兜底）** |
 *
 * ## 平放门槛为什么是 8.49
 *
 * `9.8 × cos(30°) ≈ 8.487`。含义：手机与水平面夹角 **≤ 30° 就算平放**，
 * 这是用户明确要求的容差（"不可能完全水平"）。夹角超过 30° 视为立着。
 *
 * ## ★ 本版删除了所有兜底
 *
 * `evaluate()` 判不出就返回 [Posture.UNKNOWN]，**不再 `?: true` 假装成正面朝上**。
 * UNKNOWN 的姿态由上层 [Prefs.Snapshot.longActionFor] 决定，用户选择「不执行任何动作」。
 */
class PostureEngine(private val context: Context) {

    companion object {
        /** 首帧等待上限（ms）。窗口太短拿不到足够样本，太长则长按不跟手 */
        private const val FIRST_FRAME_WAIT_MS = 300L

        /** 首帧到达后额外收集时长（ms），用于攒够样本给中位数 */
        private const val EXTRA_SAMPLE_MS = 60L

        /** 每个通道最多留多少个样本（SENSOR_DELAY_GAME ≈ 50Hz，300ms≈15 帧） */
        private const val MAX_SAMPLES = 24

        /** 标准重力加速度（m/s²） */
        private const val G = 9.80665f

        /**
         * 平放门槛（m/s²）= `G × cos(30°)`。
         * `|z|` 超过它 → 手机与水平面夹角 ≤ 30°，算平放；否则算立着。
         */
        private val LYING_FLAT_Z: Float =
            (G * Math.cos(Math.toRadians(30.0))).toFloat()

        /**
         * 立着时判横竖的最小重力分量（m/s²）。
         *
         * 立着时重力几乎全在 x/y 上（合计 ≈ 9.8），单轴至少要有这么多
         * 分量，比值才可信；两轴都很小说明数据异常，判不出。
         */
        private const val MIN_TILT_COMPONENT = 2.0f

        /**
         * 横竖判定：主导轴占 x/y 合量的比例下限。
         *
         * `max(|x|,|y|) / hypot(x,y) > 0.62` ≈ 主导轴领先次轴约 20° 以上。
         * 竖屏抓持时 x 分量很小、y 接近满分，这个判据非常稳。
         */
        private const val AXIS_DOMINANCE = 0.62f
    }

    private val sensorManager: SensorManager? by lazy {
        runCatching { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }.getOrNull()
    }

    // ---- 采样缓存（传感器线程写、判定线程读）----

    private val lock = Any()
    private val zSamples = ArrayList<Float>(MAX_SAMPLES)
    private val xSamples = ArrayList<Float>(MAX_SAMPLES)
    private val ySamples = ArrayList<Float>(MAX_SAMPLES)

    private var firstFrameLatch: CountDownLatch? = null
    private var registered = false

    /** 是否只有加速度计（没有独立重力传感器） */
    private var accelOnly = true

    @Volatile private var frozenPosture: Posture? = null

    private var sampleThread: Thread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            val v = e.values ?: return
            if (v.size < 3) return
            synchronized(lock) {
                when (e.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        if (zSamples.size < MAX_SAMPLES) {
                            xSamples.add(v[0]); ySamples.add(v[1]); zSamples.add(v[2])
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // 没有独立重力传感器时，加速度计静止值即重力方向
                        if (accelOnly && zSamples.size < MAX_SAMPLES) {
                            xSamples.add(v[0]); ySamples.add(v[1]); zSamples.add(v[2])
                        }
                    }
                }
            }
            firstFrameLatch?.countDown()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ==================== 对外接口 ====================

    /** Plus Key 按下：清缓存、注册传感器、起后台线程等样本后冻结姿态 */
    fun onKeyDown() {
        stopSamplingInternal()
        synchronized(lock) {
            zSamples.clear(); xSamples.clear(); ySamples.clear()
        }
        frozenPosture = null

        val sm = sensorManager
        if (sm == null) {
            Log2.w("SensorManager 不可用，本次姿态判定为 UNKNOWN（不执行动作）")
            return
        }

        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelOnly = gravity == null
        if (accelOnly) Log2.w("该机没有独立 TYPE_GRAVITY，改用 TYPE_ACCELEROMETER（静止值即重力）")

        val latch = CountDownLatch(1)
        firstFrameLatch = latch

        val names = ArrayList<String>()
        runCatching {
            gravity?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME); names.add("GRAVITY") }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME); names.add("ACCEL")
            }
        }.onFailure { Log2.w("注册传感器失败：${it.message}") }

        registered = names.isNotEmpty()
        if (!registered) {
            Log2.w("该设备没有可用传感器，本次姿态判定为 UNKNOWN（不执行动作）")
            return
        }

        sampleThread = Thread({
            try {
                latch.await(FIRST_FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
                Thread.sleep(EXTRA_SAMPLE_MS)
            } catch (_: Throwable) {
            }
            runCatching { frozenPosture = evaluate(names) }
                .onFailure { Log2.e("姿态判定异常：${it.javaClass.simpleName} ${it.message}") }
            stopSamplingOnly()
        }, "PlusKeyPostureSample").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** 读取本次按压判定的姿态。采样线程没算完就现算一个 */
    fun current(): Posture = frozenPosture ?: evaluate(listOf("(即时)"))

    fun onKeyUp(): Posture {
        val r = current()
        stopSampling()
        return r
    }

    fun stopSampling() {
        stopSamplingInternal()
        frozenPosture = null
    }

    // ==================== 内部 ====================

    private fun stopSamplingInternal() {
        stopSamplingOnly()
        frozenPosture = null
    }

    private fun stopSamplingOnly() {
        if (!registered) return
        registered = false
        runCatching { sensorManager?.unregisterListener(listener) }
        firstFrameLatch = null
    }

    private fun evaluate(sensorNames: List<String>): Posture {
        val s = snapshot()

        val posture = decide(s.zMed, s.xMed, s.yMed)

        // 全量打印原始样本 + 中位数：出问题一眼定位是"判错"还是"数据脏"
        Log2.i(
            "姿态采样[${sensorNames.joinToString("/")} n=${s.count}] " +
                "z中位=${fmt(s.zMed)}(原始${fmtList(s.zRaw)}) " +
                "x中位=${fmt(s.xMed)} y中位=${fmt(s.yMed)} " +
                "平放门槛=±${fmt(LYING_FLAT_Z)} -> $posture"
        )
        return posture
    }

    private class Snap(
        val zMed: Float, val xMed: Float, val yMed: Float,
        val zRaw: List<Float>, val count: Int,
    )

    /** 取各通道**中位数**（抗孤立野值）。空样本返回 NaN */
    private fun snapshot(): Snap = synchronized(lock) {
        Snap(
            zMed = median(zSamples),
            xMed = median(xSamples),
            yMed = median(ySamples),
            zRaw = zSamples.toList(),
            count = zSamples.size,
        )
    }

    private fun median(src: List<Float>): Float {
        if (src.isEmpty()) return Float.NaN
        val a = src.sorted()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2f
    }

    /**
     * 核心判定。**判不出就返回 UNKNOWN，不做任何猜测/兜底。**
     *
     * ```
     * 1. |z| > 平放门槛  → 平放，看 z 符号给正/背面
     * 2. 否则（立着）    → 看 x/y 谁主导给横/竖
     * 3. 都判不出        → UNKNOWN
     * ```
     */
    private fun decide(zMed: Float, xMed: Float, yMed: Float): Posture {
        // 数据完全没到 → 判不出
        if (zMed.isNaN()) return Posture.UNKNOWN

        // ---- 第一维度：平放 还是 立着 ----
        if (kotlin.math.abs(zMed) > LYING_FLAT_Z) {
            // 平放：只有正/背面之分，横竖在此维度下无意义
            // （用户绑定的是"正面朝上/背面朝上"，不分横竖）
            return if (zMed > 0f) Posture.FACE_UP_PORTRAIT else Posture.FACE_DOWN_PORTRAIT
        }

        // ---- 立着：只有横/竖之分 ----
        if (xMed.isNaN() || yMed.isNaN()) return Posture.UNKNOWN

        val ax = kotlin.math.abs(xMed)
        val ay = kotlin.math.abs(yMed)
        val mag = kotlin.math.hypot(ax, ay)   // x/y 合量

        // 两轴都很小 → 数据异常（立着时合量应接近 g）→ 判不出
        if (mag < MIN_TILT_COMPONENT) return Posture.UNKNOWN

        val dominant = if (ax > ay) ax else ay
        // 主导轴不够突出（接近 45°，斜着）→ 判不出，不硬猜
        if (dominant / mag < AXIS_DOMINANCE) return Posture.UNKNOWN

        return if (ax > ay) Posture.HOLDING_LANDSCAPE else Posture.HOLDING_PORTRAIT
    }

    private fun fmt(v: Float): String =
        if (v.isNaN()) "NaN" else String.format(java.util.Locale.US, "%.2f", v)

    private fun fmtList(src: List<Float>): String =
        if (src.isEmpty()) "空"
        else src.joinToString(",", "[", "]") { String.format(java.util.Locale.US, "%.1f", it) }
}
