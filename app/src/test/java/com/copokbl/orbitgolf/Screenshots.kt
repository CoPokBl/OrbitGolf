package com.copokbl.orbitgolf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders real frames of the game to PNGs (only when -Dshots.dir is given), for eyeballing
 * layout and visuals without a device. Drives frames and taps by hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Screenshots {
    private val dir = System.getProperty("shots.dir").orEmpty()
    private lateinit var view: GameView
    private var now = 0L

    private fun frames(seconds: Double) {
        val n = (seconds * 60).toInt()
        repeat(n) {
            now += 16_666_667L
            view.tick(now)
        }
    }

    private fun redraw() {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
    }

    private fun tap(x: Float, y: Float) {
        redraw()
        val t = now / 1_000_000L
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun shot(name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun capture() {
        if (dir.isEmpty()) return
        val act = Robolectric.buildActivity(MainActivity::class.java).create().get()
        view = act.window.decorView.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) } as GameView
        val w = 1080; val h = 2400
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        now = SystemClock.uptimeMillis() * 1_000_000L
        view.startClock(now)
        frames(0.3); shot("01_start")
        frames(1.5); shot("02_orbiting")
        // Shoot and watch the flight.
        tap(540f, 1500f); frames(0.6); shot("03_flight")
        frames(3.0); shot("04_later")
        // Pause menu.
        tap(60f, 120f); frames(0.2); shot("05_pause")
        // Level select via the pause menu: find "Level select" button roughly mid panel.
        view.onBack(); frames(0.1)

        // Sink a hole for real: find a release step that sinks, step there, tap.
        view.loadCampaign(0); frames(0.5)
        sinkNow(); frames(0.5); shot("06_sinking")
        frames(1.0); shot("07_complete")
        tap(540f, 300f); frames(2.0); shot("07b_next")

        for (i in listOf(5, 9, 15, 18, 19, 21, 25, 28, 31)) {
            view.loadCampaign(i); frames(2.2); shot("lv_%02d".format(i + 1))
        }
        view.openLevels(); frames(0.2); shot("08_levels")
        view.enterEndless()
        var guard = 0
        while (view.loading && guard++ < 2000) { Thread.sleep(5); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        frames(2.2); shot("09_endless")
    }

    private fun sinkNow() {
        val base = view.sim
        for (k in 0 until base.orbitSteps()) {
            val s = base.copy()
            repeat(k) { s.step() }
            s.release()
            var e: com.copokbl.orbitgolf.sim.EventType
            do { e = s.step() } while (e == com.copokbl.orbitgolf.sim.EventType.NONE)
            if (e == com.copokbl.orbitgolf.sim.EventType.SINK) {
                repeat(k) { base.step() }
                tapNoCatchUp()
                return
            }
        }
        error("no sink found")
    }

    private fun tapNoCatchUp() {
        redraw()
        // Event time in the past: the view won't advance the sim before releasing.
        val t = now / 1_000_000L - 100
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 1500f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 540f, 1500f, 0))
    }

    companion object { var hook: ((GameView) -> Unit)? = null }
}
