package com.copokbl.orbitgolf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.copokbl.orbitgolf.sim.BALL_R
import com.copokbl.orbitgolf.sim.Body
import com.copokbl.orbitgolf.sim.DT
import com.copokbl.orbitgolf.sim.EventType
import com.copokbl.orbitgolf.sim.GREEN_R
import com.copokbl.orbitgolf.sim.Generator
import com.copokbl.orbitgolf.sim.HOLE_R
import com.copokbl.orbitgolf.sim.Kind
import com.copokbl.orbitgolf.sim.Level
import com.copokbl.orbitgolf.sim.Levels
import com.copokbl.orbitgolf.sim.Mode
import com.copokbl.orbitgolf.sim.PORTAL_R
import com.copokbl.orbitgolf.sim.Sim
import com.copokbl.orbitgolf.sim.WORLD_W
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The whole game UI: one View, drawn with Canvas, driven by Choreographer.
 * The simulation runs at a fixed 120 Hz underneath whatever the display refresh rate is.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    private val save = Save(context)
    private val sfx = Sfx(context).also { it.enabled = save.sound }
    private val haptics = Haptics(context).also { it.enabled = save.haptics }
    private val dp = resources.displayMetrics.density
    private val levels = Levels.all

    private enum class Screen { PLAY, PAUSE, LEVELS, COMPLETE }

    private var screen = Screen.PLAY

    // ---- Current hole ----
    private var endless = false
    private var levelIndex = 0
    private var level: Level = levels[0]
    internal var sim = Sim(level)
        private set
    private var par = 1
    private var strokes = 0
    /** Endless hole number of the hole on screen. */
    private var curHole = 1

    /** After coming back to the app: frozen until a tap, and that tap doesn't shoot. */
    private var gated = false
    private var inputLock = 0.0
    private var hiddenTimer = 0.0
    private var sinkTimer = -1.0
    private var titleTimer = 0.0
    private var hintAlpha = 0f
    private var message = ""
    private var messageTimer = 0.0
    private var combo = 0
    internal var loading = false
        private set
    @Volatile private var genToken = 0

    // ---- Result card ----
    private var resultTitle = ""
    private var resultStars = 0
    private var resultLine = ""
    private var resultLine2 = ""

    // ---- Timing ----
    private var running = false
    private var simClock = 0L
    private var lastFrame = 0L
    private var renderAlpha = 1.0
    private var animTime = 0.0
    private var prevX = 0.0
    private var prevY = 0.0
    private val dtNanos = (DT * 1e9).toLong()

    // ---- Effects ----
    private val trailN = 44
    private val trailX = FloatArray(trailN)
    private val trailY = FloatArray(trailN)
    private var trailHead = 0
    private var trailCount = 0
    private val shotPath = ArrayList<Float>()
    private val ghost = Path()
    private var ghostValid = false
    private val predX = FloatArray(16)
    private val predY = FloatArray(16)
    private var predCount = 0
    private var shake = 0f
    private var ringFlash = FloatArray(0)
    private var popTimer = 0.0

    private val pMax = 260
    private val px = FloatArray(pMax); private val py = FloatArray(pMax)
    private val pvx = FloatArray(pMax); private val pvy = FloatArray(pMax)
    private val plife = FloatArray(pMax); private val pmax = FloatArray(pMax)
    private val pcol = IntArray(pMax); private val psize = FloatArray(pMax)

    // ---- Layout ----
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private var hudH = 0f

    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)
    private var stars = emptyList<Star>()

    private class Btn(val r: RectF, val action: () -> Unit)
    private val buttons = ArrayList<Btn>()

    // Level select scrolling
    private var scroll = 0f
    private var scrollMax = 0f
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false

    // ---- Paints & per-level graphics ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var bgShader: Shader? = null
    private var bodyShaders = arrayOf<Shader>()
    private var haloShaders = arrayOfNulls<Shader>(0)
    private var rockPaths = arrayOfNulls<Path>(0)
    private var ballGlow: Shader? = null
    private var greenShader: Shader? = null
    private val tmpPath = Path()
    private val tmpRect = RectF()

    companion object {
        const val C_BG_TOP = 0xFF0B0E1E.toInt()
        const val C_BG_BOT = 0xFF171C40.toInt()
        const val C_RING = 0xFF3FA9F5.toInt()
        const val C_BOOST = 0xFFFF9F43.toInt()
        const val C_FAST = 0xFFC77DFF.toInt()
        const val C_ROCK = 0xFF8A8FA3.toInt()
        const val C_PULSAR = 0xFFFF5FA2.toInt()
        const val C_GREEN = 0xFF2ECC71.toInt()
        const val C_FLAG = 0xFF5CF08A.toInt()
        const val C_WALL = 0xFF7FDBFF.toInt()
        const val C_PORTAL = 0xFFB69CFF.toInt()
        const val C_TEXT2 = 0xFF9AA3C7.toInt()
        const val C_PANEL = 0xF0161C3D.toInt()
        const val C_BTN = 0xFF262E5C.toInt()
        const val C_GOLD = 0xFFFFD166.toInt()
        val SOLIDS = intArrayOf(0xFFC9774A.toInt(), 0xFFB08B6E.toInt(), 0xFFD9B26F.toInt(), 0xFF9C6B5E.toInt(), 0xFFC4A484.toInt())
    }

    init {
        isFocusable = true
        val s = save.loadSession()
        if (s == null) {
            loadCampaign(save.firstUnfinished(levels.size))
        } else {
            restoreSession(s)
        }
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    fun resume() {
        if (running) return
        running = true
        lastFrame = System.nanoTime()
        simClock = lastFrame
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (screen == Screen.PLAY && strokes > 0 && sinkTimer < 0) gated = true
        persist()
    }

    /** Focus lost (notification shade, dialogs): freeze mid-shot without leaving. */
    fun focusLost() {
        if (screen == Screen.PLAY && strokes > 0 && sinkTimer < 0) gated = true
    }

    fun release() {
        genToken++
        sfx.release()
    }

    /** Returns true if handled. */
    fun onBack(): Boolean {
        when (screen) {
            Screen.PLAY -> openPause()
            Screen.PAUSE -> { screen = Screen.PLAY; gated = false }
            Screen.LEVELS -> if (sim.mode == Mode.DONE && !loading) nextHole() else { screen = Screen.PLAY; gated = strokes > 0 }
            Screen.COMPLETE -> openLevels()
        }
        click()
        return true
    }

    private fun persist() {
        if (loading) return
        val snapshot = when {
            sim.mode == Mode.ORBIT -> Save.Session(endless, levelIndex, strokes, sim.body, sim.angle, sim.dir, sim.t)
            // Mid-flight: rewind to before the shot so nothing is lost.
            sim.mode == Mode.FLIGHT -> Save.Session(endless, levelIndex, max(0, strokes - 1), sim.shotBody, sim.shotAngle, sim.shotDir, sim.t)
            // Finished hole: resume at the next one.
            !endless -> {
                val next = min(levelIndex + 1, levels.size - 1)
                val l = levels[next]
                Save.Session(false, next, 0, l.startBody, l.startAngle, l.startDir, 0.0)
            }
            else -> Save.Session(true, 0, 0, 0, 0.0, 1, 0.0)
        }
        save.saveSession(snapshot)
    }

    private fun restoreSession(s: Save.Session) {
        if (s.endless) {
            enterEndless()
            if (!loading && s.strokes > 0) {
                sim.restore(s.body, s.angle, s.dir, s.t)
                strokes = s.strokes
            }
        } else {
            loadCampaign(s.level.coerceIn(0, levels.size - 1))
            if (s.strokes > 0) {
                sim.restore(s.body, s.angle, s.dir, s.t)
                strokes = s.strokes
            }
        }
        if (strokes > 0) {
            gated = true
            titleTimer = 0.0
            hintAlpha = 0f
        }
    }

    // ======================================================================
    // Loading holes
    // ======================================================================

    internal fun loadCampaign(i: Int) {
        endless = false
        levelIndex = i
        loadLevel(levels[i], levels[i].par)
    }

    private fun loadLevel(l: Level, p: Int) {
        level = l
        par = p
        if (endless) curHole = save.eHole
        sim = Sim(l)
        strokes = 0
        screen = Screen.PLAY
        gated = false
        inputLock = 0.3
        hiddenTimer = 0.0
        sinkTimer = -1.0
        titleTimer = 1.9
        hintAlpha = if (l.hint != null) 1f else 0f
        messageTimer = 0.0
        combo = 0
        trailCount = 0
        shotPath.clear()
        ghostValid = false
        ghost.reset()
        for (i in 0 until pMax) plife[i] = 0f
        prevX = sim.x; prevY = sim.y
        buildLevelGraphics()
        persist()
    }

    internal fun enterEndless() {
        endless = true
        levelIndex = 0
        when {
            save.eHole <= 0 -> newEndlessRun()
            save.eSeed < 0 -> generateEndless(save.eHole)
            else -> loadLevel(Generator.build(save.eSeed, save.eHole, save.ePar), save.ePar)
        }
    }

    private fun newEndlessRun() {
        save.eHole = 1
        save.eScore = 0
        save.eStreak = 0
        save.eSeed = -1
        generateEndless(1)
    }

    private fun generateEndless(hole: Int) {
        loading = true
        screen = Screen.PLAY
        val token = ++genToken
        val seed0 = Random.nextLong(1, 1L shl 40)
        Thread {
            var seed = seed0
            var g: Generator.Generated? = null
            while (g == null && token == genToken) {
                g = Generator.generate(seed, hole) { token != genToken }
                seed += 100_003
            }
            if (g != null) post {
                if (token == genToken) {
                    loading = false
                    save.eHole = hole
                    save.eSeed = g.seed
                    save.ePar = g.par
                    endless = true
                    loadLevel(g.level, g.par)
                }
            }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun buildLevelGraphics() {
        val n = level.bodies.size
        ringFlash = FloatArray(n)
        bodyShaders = Array(n) { i ->
            val b = level.bodies[i]
            val base = bodyColor(b, i)
            val r = b.r.toFloat()
            RadialGradient(
                -r * 0.35f, -r * 0.4f, r * 1.45f,
                intArrayOf(lighten(base, 0.45f), base, darken(base, 0.55f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        haloShaders = Array(n) { i ->
            val b = level.bodies[i]
            if (b.kind == Kind.ROCK) null else {
                val hr = haloRadius(b)
                val c = if (b.kind == Kind.PULSAR) C_PULSAR else bodyColor(b, i)
                RadialGradient(0f, 0f, hr, intArrayOf(withAlpha(c, 46), withAlpha(c, 14), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            }
        }
        rockPaths = Array(n) { i ->
            val b = level.bodies[i]
            if (b.kind != Kind.ROCK) null else {
                val rnd = Random(i * 31 + 7)
                val p = Path()
                val k = 9
                for (j in 0 until k) {
                    val a = j * 2 * PI / k
                    val rr = b.r * (0.78 + rnd.nextDouble() * 0.32)
                    val x = (cos(a) * rr).toFloat()
                    val y = (sin(a) * rr).toFloat()
                    if (j == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                p
            }
        }
        greenShader = RadialGradient(
            0f, 0f, GREEN_R.toFloat(),
            intArrayOf(withAlpha(C_GREEN, 70), withAlpha(C_GREEN, 40), withAlpha(C_GREEN, 0)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        layoutWorld()
    }

    private fun haloRadius(b: Body) = (sqrt(abs(b.gm)) * 0.075).toFloat().coerceAtLeast(b.r.toFloat() * 1.6f)

    private fun bodyColor(b: Body, i: Int) = when (b.kind) {
        Kind.ORBIT -> if (b.isBoost) C_BOOST else if (b.isFast) C_FAST else C_RING
        Kind.SOLID -> SOLIDS[i % SOLIDS.size]
        Kind.ROCK -> C_ROCK
        Kind.PULSAR -> C_PULSAR
    }

    // ======================================================================
    // Layout
    // ======================================================================

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cut = insets.displayCutout
        insetTop = max(cut?.safeInsetTop ?: 0, 0).toFloat()
        insetBottom = max(cut?.safeInsetBottom ?: 0, 0).toFloat()
        layoutWorld()
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hudH = 64 * dp
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), C_BG_TOP, C_BG_BOT, Shader.TileMode.CLAMP)
        val rnd = Random(42)
        stars = List(170) {
            Star(
                rnd.nextFloat() * w, rnd.nextFloat() * h,
                (0.4f + rnd.nextFloat() * rnd.nextFloat() * 1.6f) * dp,
                rnd.nextFloat() * 6.28f, 0.5f + rnd.nextFloat() * 2f,
            )
        }
        layoutWorld()
    }

    private fun layoutWorld() {
        if (width == 0) return
        val top = insetTop + hudH
        val availH = height - top - insetBottom - 12 * dp
        sc = min(width / WORLD_W.toFloat(), availH / level.height.toFloat())
        ox = (width - WORLD_W.toFloat() * sc) / 2
        oy = top + (availH - level.height.toFloat() * sc) / 2
        val br = ballRadius()
        ballGlow = RadialGradient(0f, 0f, br * 3.2f, intArrayOf(0x66FFFFFF, 0x18FFFFFF, 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }

    /** Ball is drawn a bit bigger than it collides so it reads well on small screens. */
    private fun ballRadius() = max(BALL_R.toFloat() * 1.25f, 5.5f * dp / sc)

    // ======================================================================
    // Frame loop
    // ======================================================================

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        tick(frameTimeNanos)
    }

    /** Clock reset without starting the Choreographer loop (used by screenshot tests). */
    internal fun startClock(now: Long) {
        lastFrame = now
        simClock = now
    }

    internal fun tick(frameTimeNanos: Long) {
        val dt = ((frameTimeNanos - lastFrame) / 1e9).coerceIn(0.0, 0.1)
        lastFrame = frameTimeNanos
        animTime += dt
        update(dt)
        if (screen == Screen.PLAY && !gated && !loading) {
            advanceTo(frameTimeNanos)
        } else {
            simClock = frameTimeNanos
            renderAlpha = 1.0
        }
        invalidate()
    }

    private fun advanceTo(target: Long) {
        if (target - simClock > 100_000_000L) simClock = target - 100_000_000L
        while (simClock + dtNanos <= target) {
            stepOnce()
            simClock += dtNanos
        }
        renderAlpha = ((target - simClock).toDouble() / dtNanos).coerceIn(0.0, 1.0)
    }

    private fun update(dt: Double) {
        if (inputLock > 0) inputLock -= dt
        if (titleTimer > 0) titleTimer -= dt
        if (messageTimer > 0) messageTimer -= dt
        if (popTimer > 0) popTimer -= dt
        shake *= 0.86f
        for (i in ringFlash.indices) if (ringFlash[i] > 0) ringFlash[i] = max(0f, ringFlash[i] - dt.toFloat() * 2.2f)
        if (strokes > 0 && hintAlpha > 0) hintAlpha = max(0f, hintAlpha - dt.toFloat() * 1.5f)
        if (screen == Screen.PLAY && !gated) {
            if (hiddenTimer > 0) {
                hiddenTimer -= dt
                if (hiddenTimer <= 0) {
                    popTimer = 0.25
                    trailCount = 0
                    prevX = sim.x; prevY = sim.y
                }
            }
            if (sinkTimer > 0) {
                sinkTimer -= dt
                if (sinkTimer <= 0) showResult()
            }
        }
        val fdt = dt.toFloat()
        for (i in 0 until pMax) {
            if (plife[i] <= 0) continue
            plife[i] -= fdt
            px[i] += pvx[i] * fdt; py[i] += pvy[i] * fdt
            pvx[i] *= 0.97f; pvy[i] *= 0.97f
        }
    }

    private fun stepOnce() {
        prevX = sim.x; prevY = sim.y
        val ev = sim.step()
        if (sim.mode != Mode.DONE && hiddenTimer <= 0) {
            trailX[trailHead] = sim.x.toFloat(); trailY[trailHead] = sim.y.toFloat()
            trailHead = (trailHead + 1) % trailN
            if (trailCount < trailN) trailCount++
        }
        if (sim.mode == Mode.FLIGHT && shotPath.size < 4000 && (shotPath.isEmpty() || sim.flightTime % (3 * DT) < DT)) {
            shotPath.add(sim.x.toFloat()); shotPath.add(sim.y.toFloat())
        }
        when (ev) {
            EventType.CAPTURE -> {
                finishShotPath()
                combo++
                sfx.play(Sfx.S.CAPTURE, 0.9f, 1f + 0.12f * min(combo - 1, 6))
                haptics.buzz(Haptics.H.CAPTURE)
                ringFlash[sim.eventBody] = 1f
                burst(sim.x.toFloat(), sim.y.toFloat(), bodyColor(level.bodies[sim.eventBody], sim.eventBody), 10, 90f)
            }
            EventType.SINK -> {
                finishShotPath()
                sim.mode = Mode.DONE
                sinkTimer = 0.85
                sfx.play(Sfx.S.SINK)
                haptics.buzz(Haptics.H.SINK)
                burst(level.holeX.toFloat(), level.holeY.toFloat(), C_FLAG, 40, 260f)
                burst(level.holeX.toFloat(), level.holeY.toFloat(), Color.WHITE, 20, 160f)
                recordResult()
            }
            EventType.CRASH -> {
                finishShotPath()
                val b = sim.eventBody
                burst(sim.x.toFloat(), sim.y.toFloat(), bodyColor(level.bodies[b], b), 26, 220f)
                burst(sim.x.toFloat(), sim.y.toFloat(), Color.WHITE, 10, 160f)
                shake = 14 * dp
                sfx.play(Sfx.S.CRASH)
                haptics.buzz(Haptics.H.CRASH)
                showMessage(listOf("Crashed!", "Ouch!", "Splat!", "Kaboom!")[Random.nextInt(4)])
                respawn()
            }
            EventType.LOST -> {
                finishShotPath()
                sfx.play(Sfx.S.LOST, 0.8f)
                showMessage(if (sim.flightTime > 8.9) "Lost in orbit" else "Out of bounds")
                respawn()
            }
            EventType.BOUNCE -> {
                sfx.play(Sfx.S.BOUNCE, 0.7f, 0.9f + Random.nextFloat() * 0.2f)
                burst(sim.x.toFloat(), sim.y.toFloat(), C_WALL, 5, 70f)
            }
            EventType.PORTAL -> {
                sfx.play(Sfx.S.PORTAL, 0.8f)
                prevX = sim.x; prevY = sim.y
                trailCount = 0
                burst(sim.x.toFloat(), sim.y.toFloat(), C_PORTAL, 14, 120f)
            }
            EventType.NONE -> {}
        }
    }

    private fun respawn() {
        combo = 0
        sim.respawn()
        hiddenTimer = 0.75
        trailCount = 0
    }

    private fun finishShotPath() {
        ghost.reset()
        if (shotPath.size >= 4) {
            ghost.moveTo(shotPath[0], shotPath[1])
            var i = 2
            while (i < shotPath.size) { ghost.lineTo(shotPath[i], shotPath[i + 1]); i += 2 }
            ghost.lineTo(sim.x.toFloat(), sim.y.toFloat())
            ghostValid = true
        }
        shotPath.clear()
    }

    private fun showMessage(m: String) {
        message = m
        messageTimer = 1.3
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int, speed: Float) {
        var made = 0
        for (i in 0 until pMax) {
            if (plife[i] > 0) continue
            val a = Random.nextFloat() * 6.283f
            val s = speed * (0.3f + Random.nextFloat() * 0.9f)
            px[i] = x; py[i] = y
            pvx[i] = cos(a) * s; pvy[i] = sin(a) * s
            pmax[i] = 0.5f + Random.nextFloat() * 0.6f
            plife[i] = pmax[i]
            pcol[i] = color
            psize[i] = 2.5f + Random.nextFloat() * 4f
            if (++made >= n) break
        }
    }

    // ======================================================================
    // Results
    // ======================================================================

    private fun golfTerm(diff: Int, strokes: Int) = when {
        strokes == 1 -> "Hole in one!"
        diff <= -3 -> "Albatross!"
        diff == -2 -> "Eagle!"
        diff == -1 -> "Birdie!"
        diff == 0 -> "Par"
        diff == 1 -> "Bogey"
        diff == 2 -> "Double bogey"
        else -> "+$diff"
    }

    private fun recordResult() {
        val diff = strokes - par
        resultTitle = golfTerm(diff, strokes)
        resultStars = when {
            diff <= 0 -> 3
            diff <= 2 -> 2
            else -> 1
        }
        if (!endless) {
            val old = save.best(levelIndex)
            val newBest = save.recordBest(levelIndex, strokes)
            resultLine = "$strokes ${if (strokes == 1) "stroke" else "strokes"}  ·  par $par"
            resultLine2 = when {
                old == 0 -> if (levelIndex == levels.size - 1) "Course complete!" else ""
                newBest -> "New best! (was $old)"
                else -> "Best: $old"
            }
        } else {
            val hole = save.eHole
            save.eScore = save.eScore + diff
            save.eStreak = if (diff <= 0) save.eStreak + 1 else 0
            if (save.eStreak > save.eBestStreak) save.eBestStreak = save.eStreak
            if (strokes == 1) save.eAces = save.eAces + 1
            resultLine = "$strokes ${if (strokes == 1) "stroke" else "strokes"}  ·  par $par"
            resultLine2 = "Run ${scoreText(save.eScore)} after $hole  ·  streak ${save.eStreak} (best ${save.eBestStreak})"
            // Mark the run as "next hole needs generating" so a restart mid-way lands correctly.
            save.eHole = hole + 1
            save.eSeed = -1
        }
        persist()
    }

    private fun scoreText(s: Int) = when {
        s == 0 -> "E"
        s > 0 -> "+$s"
        else -> "$s"
    }

    private fun showResult() {
        screen = Screen.COMPLETE
        inputLock = 0.35
    }

    private fun nextHole() {
        click()
        if (endless) {
            generateEndless(save.eHole)
        } else if (levelIndex + 1 < levels.size) {
            loadCampaign(levelIndex + 1)
        } else {
            openLevels()
        }
    }

    private fun retryHole() {
        click()
        if (endless) {
            // Replaying an endless hole doesn't count again; just reload it for fun.
            val l = level
            loadLevel(l, par)
        } else loadCampaign(levelIndex)
    }

    // ======================================================================
    // Input
    // ======================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downScroll = scroll; dragging = false
                if (screen == Screen.PLAY) playTap(e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (screen == Screen.PLAY) {
                    val i = e.actionIndex
                    playTap(e.getX(i), e.getY(i), e.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (screen == Screen.LEVELS) {
                    if (abs(e.y - downY) > 10 * dp) dragging = true
                    if (dragging) scroll = (downScroll - (e.y - downY)).coerceIn(0f, scrollMax)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (screen != Screen.PLAY && !dragging && inputLock <= 0) menuTap(e.x, e.y)
            }
        }
        return true
    }

    private fun playTap(x: Float, y: Float, eventTimeMs: Long) {
        // HUD buttons first.
        for (b in buttons) if (b.r.contains(x, y)) { b.action(); return }
        if (loading) return
        if (gated) {
            gated = false
            inputLock = 0.12
            click()
            return
        }
        if (inputLock > 0 || hiddenTimer > 0 || sinkTimer >= 0) return
        if (sim.mode != Mode.ORBIT) return
        // Catch the sim up to the instant of the touch so timing is exact, not frame-quantised.
        val evNanos = eventTimeMs * 1_000_000L
        if (evNanos > simClock && evNanos - simClock < 50_000_000L) {
            while (simClock + dtNanos <= evNanos && sim.mode == Mode.ORBIT) {
                stepOnce(); simClock += dtNanos
            }
        }
        if (sim.mode != Mode.ORBIT) return
        sim.release()
        strokes++
        combo = 0
        shotPath.clear()
        shotPath.add(sim.x.toFloat()); shotPath.add(sim.y.toFloat())
        sfx.play(Sfx.S.LAUNCH, 0.8f)
        haptics.buzz(Haptics.H.TAP)
    }

    private fun menuTap(x: Float, y: Float) {
        for (b in buttons.toList()) if (b.r.contains(x, y)) { b.action(); return }
        if (screen == Screen.COMPLETE) nextHole()
    }

    private fun click() = sfx.play(Sfx.S.CLICK, 0.6f)

    private fun openPause() {
        if (screen != Screen.PLAY) return
        screen = Screen.PAUSE
        click()
    }

    internal fun openLevels() {
        screen = Screen.LEVELS
        // Scroll so the current level is in view.
        scroll = 0f
        pendingScrollToCurrent = true
    }

    private var pendingScrollToCurrent = false

    // ======================================================================
    // Drawing
    // ======================================================================

    override fun onDraw(canvas: Canvas) {
        buttons.clear()
        drawBackground(canvas)
        if (screen == Screen.LEVELS) {
            drawLevelSelect(canvas)
            return
        }
        if (loading) {
            drawCenterNote(canvas, "Finding a fresh hole…", "")
            return
        }
        drawWorld(canvas)
        drawHud(canvas)
        when (screen) {
            Screen.PAUSE -> drawPause(canvas)
            Screen.COMPLETE -> drawComplete(canvas)
            else -> {
                if (gated) drawCenterNote(canvas, "Paused", "Tap anywhere to continue")
            }
        }
    }

    private fun drawBackground(c: Canvas) {
        fill.color = Color.WHITE; fill.shader = bgShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        val t = animTime.toFloat()
        for (s in stars) {
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * s.speed + s.phase))
            fill.color = withAlpha(Color.WHITE, (a * 200 * min(1f, s.r / dp)).toInt().coerceIn(30, 255))
            c.drawCircle(s.x, s.y, s.r, fill)
        }
    }

    private fun drawWorld(c: Canvas) {
        val t = sim.t - (1 - renderAlpha) * DT
        val ft = animTime.toFloat()
        c.save()
        val sx = if (shake > 0.5f) (Random.nextFloat() - 0.5f) * shake else 0f
        val sy = if (shake > 0.5f) (Random.nextFloat() - 0.5f) * shake else 0f
        c.translate(ox + sx, oy + sy)
        c.scale(sc, sc)
        val px1 = 1f / sc // one screen pixel in world units

        // Play-area edge, very faint.
        stroke.shader = null
        stroke.color = 0x14FFFFFF
        stroke.strokeWidth = 1.5f * dp * px1
        tmpRect.set(0f, 0f, WORLD_W.toFloat(), level.height.toFloat())
        c.drawRoundRect(tmpRect, 30f, 30f, stroke)

        // Gravity halos.
        level.bodies.forEachIndexed { i, b ->
            val h = haloShaders[i] ?: return@forEachIndexed
            c.save(); c.translate(b.x(t).toFloat(), b.y(t).toFloat())
            fill.color = Color.WHITE; fill.shader = h
            c.drawCircle(0f, 0f, haloRadius(b), fill)
            fill.shader = null
            c.restore()
        }

        // Tracks of moving bodies.
        stroke.color = 0x22FFFFFF
        stroke.strokeWidth = 2f * dp * px1
        for (b in level.bodies) {
            val m = b.motion ?: continue
            if (m.circle) c.drawCircle(b.bx.toFloat(), b.by.toFloat(), m.ax.toFloat(), stroke)
            else c.drawLine((b.bx - m.ax).toFloat(), (b.by - m.ay).toFloat(), (b.bx + m.ax).toFloat(), (b.by + m.ay).toFloat(), stroke)
        }

        drawHole(c, ft, px1)

        // Walls.
        for (w in level.walls) {
            stroke.color = withAlpha(C_WALL, 50)
            stroke.strokeWidth = 18f
            c.drawLine(w.x1.toFloat(), w.y1.toFloat(), w.x2.toFloat(), w.y2.toFloat(), stroke)
            stroke.color = C_WALL
            stroke.strokeWidth = 7f
            c.drawLine(w.x1.toFloat(), w.y1.toFloat(), w.x2.toFloat(), w.y2.toFloat(), stroke)
        }

        // Portals.
        for (p in level.portals) {
            stroke.color = withAlpha(C_PORTAL, 40)
            stroke.strokeWidth = 2f * dp * px1
            c.drawLine(p.ax.toFloat(), p.ay.toFloat(), p.bx.toFloat(), p.by.toFloat(), stroke)
            drawPortal(c, p.ax.toFloat(), p.ay.toFloat(), ft, 1)
            drawPortal(c, p.bx.toFloat(), p.by.toFloat(), ft, -1)
        }

        // Bodies.
        level.bodies.forEachIndexed { i, b -> drawBody(c, b, i, t, ft, px1) }

        // Last shot's path, faint.
        if (ghostValid) {
            stroke.color = 0x30FFFFFF
            stroke.strokeWidth = 1.5f * dp * px1
            c.drawPath(ghost, stroke)
        }

        // Aim preview.
        if (sim.mode == Mode.ORBIT && hiddenTimer <= 0 && sinkTimer < 0 && !gated) {
            computePrediction()
            for (k in 0 until predCount) {
                val a = 1f - k / predCount.toFloat()
                fill.color = withAlpha(Color.WHITE, (170 * a).toInt())
                c.drawCircle(predX[k], predY[k], (3.2f + 1.6f * a) * max(1f, 1.4f * dp * px1 / 2f), fill)
            }
        }

        // Trail.
        val ballVisible = hiddenTimer <= 0
        if (ballVisible && trailCount > 1) {
            var prevXp = 0f
            var prevYp = 0f
            for (k in 0 until trailCount) {
                val idx = (trailHead - trailCount + k + trailN) % trailN
                val x = trailX[idx]
                val y = trailY[idx]
                if (k > 0) {
                    val f = k / trailCount.toFloat()
                    stroke.color = withAlpha(Color.WHITE, (f * f * 150).toInt())
                    stroke.strokeWidth = ballRadius() * 1.3f * f
                    c.drawLine(prevXp, prevYp, x, y, stroke)
                }
                prevXp = x; prevYp = y
            }
        }

        // Ball.
        if (ballVisible) {
            var bx = (prevX + (sim.x - prevX) * renderAlpha).toFloat()
            var by = (prevY + (sim.y - prevY) * renderAlpha).toFloat()
            var r = ballRadius()
            if (sinkTimer >= 0) {
                val k = (sinkTimer / 1.0).toFloat().coerceIn(0f, 1f)
                val f = ((k - 0.6f) / 0.4f).coerceIn(0f, 1f)
                bx = level.holeX.toFloat() + (bx - level.holeX.toFloat()) * f
                by = level.holeY.toFloat() + (by - level.holeY.toFloat()) * f
                r *= (0.15f + 0.85f * f)
            }
            if (popTimer > 0) r *= 1f + (popTimer / 0.25).toFloat() * 0.8f
            c.save(); c.translate(bx, by)
            fill.color = Color.WHITE; fill.shader = ballGlow
            c.drawCircle(0f, 0f, ballRadius() * 3.2f, fill)
            fill.shader = null
            fill.color = Color.WHITE
            c.drawCircle(0f, 0f, r, fill)
            c.restore()
        }

        // Particles.
        for (i in 0 until pMax) {
            if (plife[i] <= 0) continue
            val a = plife[i] / pmax[i]
            fill.color = withAlpha(pcol[i], (255 * a).toInt())
            c.drawCircle(px[i], py[i], psize[i] * (0.4f + 0.6f * a), fill)
        }
        c.restore()
    }

    private fun computePrediction() {
        val s = sim.copy()
        s.release()
        predCount = 0
        for (k in 1..72) {
            val ev = s.step()
            if (k % 6 == 0 && predCount < predX.size) {
                predX[predCount] = s.x.toFloat(); predY[predCount] = s.y.toFloat(); predCount++
            }
            if (ev != EventType.NONE && ev != EventType.BOUNCE) break
        }
    }

    private fun drawHole(c: Canvas, ft: Float, px1: Float) {
        val hx = level.holeX.toFloat()
        val hy = level.holeY.toFloat()
        c.save(); c.translate(hx, hy)
        fill.color = Color.WHITE; fill.shader = greenShader
        c.drawCircle(0f, 0f, GREEN_R.toFloat(), fill)
        fill.shader = null
        stroke.color = withAlpha(C_GREEN, 60)
        stroke.strokeWidth = 1.5f * dp * px1
        c.drawCircle(0f, 0f, GREEN_R.toFloat(), stroke)
        // Cup.
        val cr = HOLE_R.toFloat() * 0.85f
        fill.color = 0xFF05060D.toInt()
        c.drawCircle(0f, 0f, cr, fill)
        stroke.color = 0xCCFFFFFF.toInt()
        stroke.strokeWidth = 3f
        c.drawCircle(0f, 0f, cr, stroke)
        // Flag.
        stroke.color = 0xFFE8E8F0.toInt()
        stroke.strokeWidth = 3.5f
        c.drawLine(0f, 0f, 0f, -78f, stroke)
        tmpPath.reset()
        tmpPath.moveTo(0f, -78f)
        val wave = sin(ft * 4f) * 4f
        tmpPath.quadTo(18f, -78f + wave, 40f, -66f + wave * 0.5f)
        tmpPath.quadTo(18f, -56f - wave, 0f, -54f)
        tmpPath.close()
        fill.color = C_FLAG
        c.drawPath(tmpPath, fill)
        c.restore()
    }

    private fun drawPortal(c: Canvas, x: Float, y: Float, ft: Float, spin: Int) {
        val r = PORTAL_R.toFloat()
        c.save(); c.translate(x, y)
        fill.color = 0xFF0D0820.toInt()
        c.drawCircle(0f, 0f, r * 0.9f, fill)
        stroke.color = C_PORTAL
        stroke.strokeWidth = 4f
        tmpRect.set(-r, -r, r, r)
        val base = ft * 140f * spin
        for (k in 0 until 3) c.drawArc(tmpRect, base + k * 120f, 70f, false, stroke)
        stroke.color = withAlpha(C_PORTAL, 120)
        stroke.strokeWidth = 2.5f
        tmpRect.set(-r * 0.6f, -r * 0.6f, r * 0.6f, r * 0.6f)
        for (k in 0 until 3) c.drawArc(tmpRect, -base * 1.4f + k * 120f, 60f, false, stroke)
        c.restore()
    }

    private fun drawBody(c: Canvas, b: Body, i: Int, t: Double, ft: Float, px1: Float) {
        val x = b.x(t).toFloat()
        val y = b.y(t).toFloat()
        val r = b.r.toFloat()
        c.save(); c.translate(x, y)
        val color = bodyColor(b, i)
        when (b.kind) {
            Kind.ORBIT -> {
                // Capture ring: rotating dots.
                val ring = b.ring.toFloat()
                val current = sim.mode == Mode.ORBIT && sim.body == i
                val flash = ringFlash.getOrElse(i) { 0f }
                val n = max(12, (2 * PI * ring / 24).toInt())
                val rot = ft * (b.spin / b.ring).toFloat() * 0.35f
                val dotR = max(2.6f, 1.3f * dp * px1) * (1f + flash * 0.8f)
                fill.color = withAlpha(lighten(color, 0.3f), if (current) 230 else (130 + 120 * flash).toInt().coerceAtMost(255))
                for (k in 0 until n) {
                    val a = rot + k * 2 * PI.toFloat() / n
                    c.drawCircle(cos(a) * ring, sin(a) * ring, dotR, fill)
                }
                if (flash > 0) {
                    stroke.color = withAlpha(color, (flash * 160).toInt())
                    stroke.strokeWidth = 3f + 6f * flash
                    c.drawCircle(0f, 0f, ring + (1 - flash) * 18f, stroke)
                }
                fill.color = Color.WHITE; fill.shader = bodyShaders[i]
                c.drawCircle(0f, 0f, r, fill)
                fill.shader = null
                // Markers: chevrons for boost planets, a swirl for fast spinners.
                if (b.isBoost) {
                    stroke.color = 0xAAFFFFFF.toInt(); stroke.strokeWidth = 4f
                    for (k in 0..1) {
                        val yy = -r * 0.15f + k * r * 0.35f
                        c.drawLine(-r * 0.3f, yy + r * 0.15f, 0f, yy - r * 0.12f, stroke)
                        c.drawLine(0f, yy - r * 0.12f, r * 0.3f, yy + r * 0.15f, stroke)
                    }
                } else if (b.isFast) {
                    stroke.color = 0xAAFFFFFF.toInt(); stroke.strokeWidth = 4f
                    tmpRect.set(-r * 0.45f, -r * 0.45f, r * 0.45f, r * 0.45f)
                    c.drawArc(tmpRect, ft * 300f, 250f, false, stroke)
                }
            }
            Kind.SOLID -> {
                fill.color = Color.WHITE; fill.shader = bodyShaders[i]
                c.drawCircle(0f, 0f, r, fill)
                fill.shader = null
                // A few craters, fixed per planet.
                val rnd = Random(i * 97 + 3)
                fill.color = withAlpha(darken(color, 0.4f), 70)
                repeat(3) {
                    val a = rnd.nextFloat() * 6.28f
                    val d = rnd.nextFloat() * r * 0.55f
                    c.drawCircle(cos(a) * d, sin(a) * d, r * (0.1f + rnd.nextFloat() * 0.14f), fill)
                }
            }
            Kind.ROCK -> {
                fill.color = Color.WHITE; fill.shader = bodyShaders[i]
                c.rotate(ft * 25f * (if (i % 2 == 0) 1 else -1))
                rockPaths[i]?.let { c.drawPath(it, fill) }
                fill.shader = null
            }
            Kind.PULSAR -> {
                for (k in 0 until 3) {
                    val ph = ((ft * 0.6f + k / 3f) % 1f)
                    stroke.color = withAlpha(C_PULSAR, ((1 - ph) * 150).toInt())
                    stroke.strokeWidth = 3f
                    c.drawCircle(0f, 0f, r + ph * haloRadius(b) * 0.8f, stroke)
                }
                fill.color = Color.WHITE; fill.shader = bodyShaders[i]
                c.drawCircle(0f, 0f, r * (1f + 0.08f * sin(ft * 6f)), fill)
                fill.shader = null
            }
        }
        c.restore()
    }

    // ---- HUD ----

    private fun drawHud(c: Canvas) {
        val top = insetTop
        val cy = top + hudH / 2
        val pad = 14 * dp
        val br = 21 * dp
        // Pause button.
        val pbx = pad + br
        roundButtonBg(c, pbx, cy, br)
        fill.color = Color.WHITE
        c.drawRoundRect(pbx - 7 * dp, cy - 8 * dp, pbx - 2.5f * dp, cy + 8 * dp, 1.5f * dp, 1.5f * dp, fill)
        c.drawRoundRect(pbx + 2.5f * dp, cy - 8 * dp, pbx + 7 * dp, cy + 8 * dp, 1.5f * dp, 1.5f * dp, fill)
        buttons += Btn(RectF(0f, top, pbx + br + 10 * dp, top + hudH)) { openPause() }
        // Restart button.
        val rbx = width - pad - br
        roundButtonBg(c, rbx, cy, br)
        stroke.shader = null
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2.4f * dp
        tmpRect.set(rbx - 8 * dp, cy - 8 * dp, rbx + 8 * dp, cy + 8 * dp)
        c.drawArc(tmpRect, -60f, 290f, false, stroke)
        val ax = rbx + 8 * dp * cos(-60f / 180f * PI.toFloat())
        val ay = cy + 8 * dp * sin(-60f / 180f * PI.toFloat())
        tmpPath.reset()
        tmpPath.moveTo(ax + 4.5f * dp, ay - 1.5f * dp); tmpPath.lineTo(ax - 1f * dp, ay - 4.5f * dp); tmpPath.lineTo(ax - 0.5f * dp, ay + 3.5f * dp); tmpPath.close()
        fill.color = Color.WHITE
        c.drawPath(tmpPath, fill)
        buttons += Btn(RectF(rbx - br - 10 * dp, top, width.toFloat(), top + hudH)) {
            if (screen == Screen.PLAY && !loading && sim.mode != Mode.DONE) { click(); if (endless) loadLevel(level, par) else loadCampaign(levelIndex) }
        }

        // Title + strokes.
        text.typeface = bold
        text.textSize = 17 * dp
        text.color = Color.WHITE
        val title = if (endless) "Endless · Hole $curHole" else "${worldLabel(levelIndex)}  ${level.name}"
        c.drawText(ellipsize(title, width - 2 * (pad + 2 * br + 12 * dp)), width / 2f, cy - 3 * dp, text)
        text.typeface = medium
        text.textSize = 13 * dp
        text.color = C_TEXT2
        val sub = buildString {
            append("Stroke $strokes · Par $par")
            if (endless) append(" · Run ${scoreText(save.eScore)}")
        }
        c.drawText(sub, width / 2f, cy + 15 * dp, text)

        // Level title card, hint, messages.
        if (titleTimer > 0 && screen == Screen.PLAY && !gated) {
            val a = min(1.0, min(titleTimer / 0.4, (1.9 - titleTimer) / 0.25)).toFloat().coerceIn(0f, 1f)
            text.typeface = bold
            text.textSize = 30 * dp
            text.color = withAlpha(Color.WHITE, (a * 235).toInt())
            c.drawText(level.name, width / 2f, height * 0.42f, text)
            text.typeface = medium
            text.textSize = 15 * dp
            text.color = withAlpha(C_TEXT2, (a * 235).toInt())
            c.drawText("Par $par", width / 2f, height * 0.42f + 26 * dp, text)
        }
        val hint = level.hint
        if (hint != null && hintAlpha > 0 && screen == Screen.PLAY) {
            text.typeface = medium
            text.textSize = 15 * dp
            val tw = text.measureText(hint)
            val hy = height - insetBottom - 34 * dp
            fill.color = withAlpha(0xFF1E2550.toInt(), (hintAlpha * 220).toInt())
            c.drawRoundRect(width / 2f - tw / 2 - 16 * dp, hy - 22 * dp, width / 2f + tw / 2 + 16 * dp, hy + 12 * dp, 17 * dp, 17 * dp, fill)
            text.color = withAlpha(Color.WHITE, (hintAlpha * 255).toInt())
            c.drawText(hint, width / 2f, hy, text)
        }
        if (messageTimer > 0) {
            val a = min(1.0, messageTimer / 0.4).toFloat()
            text.typeface = bold
            text.textSize = 22 * dp
            text.color = withAlpha(0xFFFF8A80.toInt(), (a * 255).toInt())
            c.drawText(message, width / 2f, insetTop + hudH + 40 * dp, text)
        }
    }

    private fun worldLabel(i: Int): String {
        var idx = i
        Levels.worlds.forEachIndexed { w, world ->
            if (idx < world.levels.size) return "${w + 1}-${idx + 1}"
            idx -= world.levels.size
        }
        return "${i + 1}"
    }

    private fun roundButtonBg(c: Canvas, x: Float, y: Float, r: Float) {
        fill.color = 0x33FFFFFF
        c.drawCircle(x, y, r, fill)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    // ---- Overlays ----

    private fun dim(c: Canvas, alpha: Int = 150) {
        fill.color = withAlpha(0xFF05060F.toInt(), alpha)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
    }

    private fun drawCenterNote(c: Canvas, title: String, sub: String) {
        dim(c, 120)
        text.typeface = bold
        text.textSize = 28 * dp
        text.color = Color.WHITE
        c.drawText(title, width / 2f, height * 0.45f, text)
        if (sub.isNotEmpty()) {
            text.typeface = medium
            text.textSize = 16 * dp
            val a = 0.55f + 0.45f * sin(animTime.toFloat() * 3.5f)
            text.color = withAlpha(C_TEXT2, (a * 255).toInt())
            c.drawText(sub, width / 2f, height * 0.45f + 30 * dp, text)
        }
    }

    private fun panel(c: Canvas, w: Float, h: Float): RectF {
        val r = RectF(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2)
        fill.color = C_PANEL
        c.drawRoundRect(r, 22 * dp, 22 * dp, fill)
        stroke.color = 0x22FFFFFF
        stroke.strokeWidth = 1 * dp
        c.drawRoundRect(r, 22 * dp, 22 * dp, stroke)
        return r
    }

    private fun button(c: Canvas, r: RectF, label: String, primary: Boolean = false, action: () -> Unit) {
        fill.color = if (primary) C_RING else C_BTN
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        text.typeface = if (primary) bold else medium
        text.textSize = 16 * dp
        text.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + 5.5f * dp, text)
        buttons += Btn(RectF(r), action)
    }

    private fun drawPause(c: Canvas) {
        dim(c)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Resume" to { click(); screen = Screen.PLAY; gated = false }
        if (sim.mode != Mode.DONE) items += "Restart hole" to { click(); if (endless) loadLevel(level, par) else loadCampaign(levelIndex) }
        items += "Level select" to { click(); openLevels() }
        if (endless) {
            items += "Skip hole (+2)" to {
                click()
                save.eScore = save.eScore + 2
                save.eStreak = 0
                save.eHole = save.eHole + 1
                save.eSeed = -1
                generateEndless(save.eHole)
            }
            items += "New endless run" to { click(); newEndlessRun() }
        } else {
            items += "Endless mode" to { click(); enterEndless() }
        }
        items += "Sound: ${if (sfx.enabled) "on" else "off"}" to {
            sfx.enabled = !sfx.enabled; save.sound = sfx.enabled; click()
        }
        items += "Vibration: ${if (haptics.enabled) "on" else "off"}" to {
            haptics.enabled = !haptics.enabled; save.haptics = haptics.enabled
            haptics.buzz(Haptics.H.CAPTURE); click()
        }
        val bh = 50 * dp
        val gap = 10 * dp
        val pw = min(width - 48 * dp, 340 * dp)
        val ph = 76 * dp + items.size * (bh + gap) + 10 * dp
        val p = panel(c, pw, ph)
        text.typeface = bold
        text.textSize = 24 * dp
        text.color = Color.WHITE
        c.drawText("Paused", p.centerX(), p.top + 46 * dp, text)
        var y = p.top + 70 * dp
        items.forEachIndexed { k, (label, act) ->
            button(c, RectF(p.left + 20 * dp, y, p.right - 20 * dp, y + bh), label, k == 0, act)
            y += bh + gap
        }
    }

    private fun drawComplete(c: Canvas) {
        dim(c, 110)
        val pw = min(width - 48 * dp, 340 * dp)
        val p = panel(c, pw, 280 * dp)
        text.typeface = bold
        text.textSize = 30 * dp
        text.color = if (strokes - par < 0 || strokes == 1) C_GOLD else Color.WHITE
        c.drawText(resultTitle, p.centerX(), p.top + 52 * dp, text)
        // Stars.
        for (k in 0 until 3) {
            val sx = p.centerX() + (k - 1) * 46 * dp
            val sy = p.top + 98 * dp
            val on = k < resultStars
            val pop = ((animTime * 4 - k * 0.6) % 100).coerceAtMost(1.0).toFloat()
            fill.color = if (on) C_GOLD else 0x33FFFFFF
            c.drawPath(starPath(sx, sy, (if (on) 18 * (0.85f + 0.15f * pop) else 16f) * dp), fill)
        }
        text.typeface = medium
        text.textSize = 16 * dp
        text.color = Color.WHITE
        c.drawText(resultLine, p.centerX(), p.top + 148 * dp, text)
        if (resultLine2.isNotEmpty()) {
            text.textSize = 13.5f * dp
            text.color = if (resultLine2.startsWith("New best") || resultLine2.startsWith("Course")) C_FLAG else C_TEXT2
            c.drawText(ellipsize(resultLine2, pw - 24 * dp), p.centerX(), p.top + 172 * dp, text)
        }
        val bh = 50 * dp
        val y = p.bottom - bh - 22 * dp
        val mid = p.centerX()
        button(c, RectF(p.left + 20 * dp, y, mid - 6 * dp, y + bh), if (endless) "Levels" else "Retry") {
            if (endless) { click(); openLevels() } else retryHole()
        }
        val last = !endless && levelIndex == levels.size - 1
        button(c, RectF(mid + 6 * dp, y, p.right - 20 * dp, y + bh), if (last) "Levels" else "Next  ›", true) { nextHole() }
    }

    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (k in 0 until 10) {
            val rr = if (k % 2 == 0) r else r * 0.45f
            val a = -PI / 2 + k * PI / 5
            val x = cx + (cos(a) * rr).toFloat()
            val y = cy + (sin(a) * rr).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    // ---- Level select ----

    private fun drawLevelSelect(c: Canvas) {
        val pad = 18 * dp
        val cols = 4
        val gap = 10 * dp
        val tile = (width - 2 * pad - (cols - 1) * gap) / cols
        val tileH = tile * 0.86f
        val top0 = insetTop + 12 * dp

        // Measure content to clamp scrolling.
        var contentH = 120 * dp + 86 * dp
        for (w in Levels.worlds) contentH += 40 * dp + ((w.levels.size + cols - 1) / cols) * (tileH + gap)
        contentH += 70 * dp
        scrollMax = max(0f, contentH - (height - top0))

        if (pendingScrollToCurrent) {
            pendingScrollToCurrent = false
            var y = 120 * dp + 86 * dp
            var idx = if (endless) -1 else levelIndex
            for (w in Levels.worlds) {
                if (idx in 0 until w.levels.size) { y += 40 * dp + (idx / cols) * (tileH + gap); break }
                y += 40 * dp + ((w.levels.size + cols - 1) / cols) * (tileH + gap)
                idx -= w.levels.size
            }
            scroll = if (idx >= 0 && !endless) (y - height * 0.45f).coerceIn(0f, scrollMax) else 0f
        }

        c.save()
        c.translate(0f, -scroll)
        var y = top0
        text.typeface = bold
        text.textSize = 34 * dp
        text.color = Color.WHITE
        c.drawText("ORBIT GOLF", width / 2f, y + 46 * dp, text)
        text.typeface = medium
        text.textSize = 13.5f * dp
        text.color = C_TEXT2
        c.drawText("Tap to launch · fly through rings · sink it", width / 2f, y + 72 * dp, text)
        var totalStars = 0
        for (i in levels.indices) totalStars += starsFor(i)
        text.color = C_GOLD
        c.drawText("★ $totalStars / ${levels.size * 3}", width / 2f, y + 96 * dp, text)
        y += 120 * dp

        // Endless card.
        val er = RectF(pad, y, width - pad, y + 72 * dp)
        fill.color = C_BTN
        c.drawRoundRect(er, 18 * dp, 18 * dp, fill)
        stroke.color = withAlpha(C_FLAG, 120); stroke.strokeWidth = 1.5f * dp
        c.drawRoundRect(er, 18 * dp, 18 * dp, stroke)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 19 * dp
        text.color = Color.WHITE
        c.drawText("Endless", er.left + 18 * dp, er.top + 30 * dp, text)
        text.typeface = medium
        text.textSize = 13 * dp
        text.color = C_TEXT2
        val eh = save.eHole
        val eLine = if (eh > 0) "Hole $eh · run ${scoreText(save.eScore)} · best streak ${save.eBestStreak}" else "Fresh random holes, forever · best streak ${save.eBestStreak}"
        c.drawText(ellipsize(eLine, er.width() - 70 * dp), er.left + 18 * dp, er.top + 52 * dp, text)
        text.textAlign = Paint.Align.CENTER
        fill.color = C_FLAG
        val pcx = er.right - 30 * dp
        val pcy = er.centerY()
        tmpPath.reset(); tmpPath.moveTo(pcx - 6 * dp, pcy - 9 * dp); tmpPath.lineTo(pcx + 9 * dp, pcy); tmpPath.lineTo(pcx - 6 * dp, pcy + 9 * dp); tmpPath.close()
        c.drawPath(tmpPath, fill)
        buttons += Btn(RectF(er.left, er.top - scroll, er.right, er.bottom - scroll)) {
            click()
            if (endless && !loading && sim.mode != Mode.DONE) screen = Screen.PLAY else enterEndless()
        }
        y += 86 * dp

        var global = 0
        Levels.worlds.forEachIndexed { wi, world ->
            text.textAlign = Paint.Align.LEFT
            text.typeface = bold
            text.textSize = 15 * dp
            text.color = C_TEXT2
            c.drawText("${wi + 1}  ${world.name.uppercase()}", pad + 2 * dp, y + 26 * dp, text)
            text.textAlign = Paint.Align.CENTER
            y += 40 * dp
            world.levels.forEachIndexed { li, _ ->
                val idx = global + li
                val col = li % cols
                val row = li / cols
                val r = RectF(pad + col * (tile + gap), y + row * (tileH + gap), pad + col * (tile + gap) + tile, y + row * (tileH + gap) + tileH)
                drawLevelTile(c, r, idx, wi, li)
            }
            y += ((world.levels.size + cols - 1) / cols) * (tileH + gap)
            global += world.levels.size
        }
        c.restore()

        // Back-to-game chip, fixed.
        val bw = 92 * dp
        val br = RectF(width - pad - bw, insetTop + 10 * dp, width - pad, insetTop + 46 * dp)
        if (scroll < 40 * dp) {
            fill.color = 0x33FFFFFF
            c.drawRoundRect(br, 18 * dp, 18 * dp, fill)
            text.typeface = medium
            text.textSize = 14 * dp
            text.color = Color.WHITE
            c.drawText("‹ Back", br.centerX(), br.centerY() + 5 * dp, text)
            buttons.add(0, Btn(br) { onBack() })
        }
    }

    private fun starsFor(i: Int): Int {
        val b = save.best(i)
        if (b == 0) return 0
        val d = b - levels[i].par
        return if (d <= 0) 3 else if (d <= 2) 2 else 1
    }

    private fun drawLevelTile(c: Canvas, r: RectF, idx: Int, wi: Int, li: Int) {
        val unlocked = save.unlocked(idx, levels.size)
        val current = !endless && idx == levelIndex
        fill.color = if (unlocked) C_BTN else 0xFF181D3A.toInt()
        c.drawRoundRect(r, 14 * dp, 14 * dp, fill)
        if (current) {
            stroke.color = C_RING; stroke.strokeWidth = 2 * dp
            c.drawRoundRect(r, 14 * dp, 14 * dp, stroke)
        }
        if (!unlocked) {
            // Padlock.
            val cx = r.centerX()
            val cy = r.centerY()
            fill.color = 0x55FFFFFF
            c.drawRoundRect(cx - 9 * dp, cy - 3 * dp, cx + 9 * dp, cy + 11 * dp, 3 * dp, 3 * dp, fill)
            stroke.color = 0x55FFFFFF; stroke.strokeWidth = 2.5f * dp
            tmpRect.set(cx - 6 * dp, cy - 12 * dp, cx + 6 * dp, cy + 2 * dp)
            c.drawArc(tmpRect, 180f, 180f, false, stroke)
            return
        }
        text.typeface = bold
        text.textSize = 20 * dp
        text.color = Color.WHITE
        c.drawText("${wi + 1}-${li + 1}", r.centerX(), r.centerY() - 1 * dp, text)
        val st = starsFor(idx)
        for (k in 0 until 3) {
            fill.color = if (k < st) C_GOLD else 0x30FFFFFF
            c.drawPath(starPath(r.centerX() + (k - 1) * 15 * dp, r.centerY() + 18 * dp, 6 * dp), fill)
        }
        buttons += Btn(RectF(r.left, r.top - scroll, r.right, r.bottom - scroll)) {
            click()
            loadCampaign(idx)
        }
    }

    // ======================================================================
    // Colour helpers
    // ======================================================================

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun lighten(c: Int, f: Float): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return Color.rgb((r + (255 - r) * f).toInt(), (g + (255 - g) * f).toInt(), (b + (255 - b) * f).toInt())
    }

    private fun darken(c: Int, f: Float): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return Color.rgb((r * (1 - f)).toInt(), (g * (1 - f)).toInt(), (b * (1 - f)).toInt())
    }

    @Suppress("unused")
    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)
}
