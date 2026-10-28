package com.copokbl.orbitgolf

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Every sound is synthesised at startup into a tiny WAV in the cache dir, so the APK ships no audio. */
class Sfx(context: Context) {
    enum class S { LAUNCH, CAPTURE, SINK, CRASH, BOUNCE, PORTAL, CLICK, LOST }

    var enabled = true
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val ids = IntArray(S.entries.size)

    init {
        val dir = File(context.cacheDir, "sfx_v1").apply { mkdirs() }
        for (s in S.entries) {
            val f = File(dir, s.name.lowercase() + ".wav")
            if (!f.exists() || f.length() < 64) writeWav(f, synth(s))
            ids[s.ordinal] = pool.load(f.path, 1)
        }
    }

    fun play(s: S, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        pool.play(ids[s.ordinal], volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    private companion object {
        const val RATE = 22050

        fun buf(seconds: Double) = DoubleArray((RATE * seconds).toInt())

        fun bell(out: DoubleArray, start: Double, freq: Double, amp: Double, decay: Double) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until out.size) {
                val t = (i - s0).toDouble() / RATE
                val env = exp(-t / decay) * minOf(1.0, t * 400)
                out[i] += amp * env * (sin(2 * PI * freq * t) + 0.35 * sin(2 * PI * freq * 2.01 * t) +
                    0.12 * sin(2 * PI * freq * 3.02 * t))
            }
        }

        fun synth(s: S): DoubleArray {
            val rnd = Random(7)
            return when (s) {
                S.LAUNCH -> buf(0.16).also { o ->
                    var ph = 0.0
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        ph += 2 * PI * (420 + 2600 * t) / RATE
                        o[i] = 0.5 * exp(-t / 0.05) * minOf(1.0, t * 600) * sin(ph) +
                            0.12 * exp(-t / 0.03) * (rnd.nextDouble() * 2 - 1)
                    }
                }
                S.CAPTURE -> buf(0.45).also { bell(it, 0.0, 784.0, 0.32, 0.12) }
                S.SINK -> buf(1.1).also {
                    val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
                    notes.forEachIndexed { k, f -> bell(it, k * 0.085, f, 0.24, 0.25 + k * 0.05) }
                }
                S.CRASH -> buf(0.4).also { o ->
                    var lp = 0.0
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        lp += ((rnd.nextDouble() * 2 - 1) - lp) * 0.18
                        o[i] = exp(-t / 0.09) * (0.9 * lp + 0.5 * sin(2 * PI * (110 - 120 * t) * t))
                    }
                }
                S.BOUNCE -> buf(0.07).also { o ->
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        o[i] = 0.4 * exp(-t / 0.015) * sin(2 * PI * 1250 * t)
                    }
                }
                S.PORTAL -> buf(0.32).also { o ->
                    var ph = 0.0
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        ph += 2 * PI * (300 + 2200 * t + 90 * sin(2 * PI * 28 * t)) / RATE
                        o[i] = 0.3 * sin(ph) * sin(PI * t / 0.32)
                    }
                }
                S.CLICK -> buf(0.035).also { o ->
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        o[i] = 0.25 * exp(-t / 0.008) * sin(2 * PI * 1800 * t)
                    }
                }
                S.LOST -> buf(0.5).also { o ->
                    var ph = 0.0
                    for (i in o.indices) {
                        val t = i.toDouble() / RATE
                        ph += 2 * PI * (620 - 760 * t) / RATE
                        o[i] = 0.25 * sin(ph) * exp(-t / 0.2) * minOf(1.0, t * 300)
                    }
                }
            }
        }

        fun writeWav(f: File, data: DoubleArray) {
            val pcm = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in data) pcm.putShort((v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort())
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(data.size * 2)
            FileOutputStream(f).use { it.write(h.array()); it.write(pcm.array()) }
        }
    }
}

class Haptics(context: Context) {
    enum class H { TAP, CAPTURE, CRASH, SINK }

    var enabled = true
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun buzz(h: H) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 29) when (h) {
            H.TAP -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            H.CAPTURE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            H.CRASH -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            H.SINK -> VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 18, 70, 30), -1)
        } else when (h) {
            H.TAP -> VibrationEffect.createOneShot(8, 80)
            H.CAPTURE -> VibrationEffect.createOneShot(14, 140)
            H.CRASH -> VibrationEffect.createOneShot(40, 255)
            H.SINK -> VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 18, 70, 30), -1)
        }
        v.vibrate(effect)
    }
}
