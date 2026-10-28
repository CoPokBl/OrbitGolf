package com.copokbl.orbitgolf.sim

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Endless mode: random holes that get busier as you go, each one checked by the
 * [Solver] so it's always beatable along a comfortable route. Par is that route's length.
 */
object Generator {
    private val names = listOf(
        "Nebula", "Comet", "Quasar", "Halo", "Perigee", "Apogee", "Zenith", "Corona", "Eclipse", "Vortex",
        "Aurora", "Meteor", "Pulse", "Drift", "Cinder", "Lumen", "Orbit", "Tide", "Ember", "Echo",
    )

    class Generated(val level: Level, val seed: Long, val par: Int)

    /** Difficulty 0..1 for the n-th endless hole (1-based). */
    fun difficulty(hole: Int) = ((hole - 1) / 24.0).coerceIn(0.0, 1.0)

    /** Find a solvable hole, trying seeds from [baseSeed] upwards. */
    fun generate(baseSeed: Long, hole: Int, cancelled: () -> Boolean = { false }): Generated? {
        val d = difficulty(hole)
        var seed = baseSeed
        repeat(60) {
            if (cancelled()) return null
            val lvl = build(seed, hole, 1)
            val res = Solver.solve(lvl, maxStrokes = 4, minWindow = 10, maxNodes = 40, cancelled = cancelled)
            // Mostly avoid one-shot holes once things get going: they're the least interesting.
            val tooEasy = res.strokes == 1 && d > 0.15 && Random(seed).nextFloat() < 0.75
            if (res.solved && !tooEasy) return Generated(build(seed, hole, res.strokes), seed, res.strokes)
            seed++
        }
        return null
    }

    private class Spot(val x: Double, val y: Double, val clear: Double)

    fun build(seed: Long, hole: Int, par: Int): Level {
        val rnd = Random(seed)
        val d = difficulty(hole)
        val b = LevelBuilder(names[rnd.nextInt(names.size)] + " " + (hole), par)
        val taken = ArrayList<Spot>()

        fun free(x: Double, y: Double, clear: Double) = x - clear > 20 && x + clear < WORLD_W - 20 &&
            taken.none { hypot(it.x - x, it.y - y) < it.clear + clear + 25 }

        fun place(yMin: Double, yMax: Double, clear: Double): Pair<Double, Double>? {
            repeat(40) {
                val x = rnd.nextDouble(clear + 30, WORLD_W - clear - 30)
                val y = rnd.nextDouble(yMin, yMax)
                if (free(x, y, clear)) {
                    taken += Spot(x, y, clear); return x to y
                }
            }
            return null
        }

        // Start low, hole high, on random sides.
        val sx = rnd.nextDouble(170.0, 830.0)
        val sy = rnd.nextDouble(1340.0, 1440.0)
        b.orbit(sx, sy, 45)
        taken += Spot(sx, sy, 105.0)
        b.startDeg = listOf(0.0, 90.0, 180.0, 270.0)[rnd.nextInt(4)]
        b.clockwise = rnd.nextBoolean()
        val hx = rnd.nextDouble(130.0, 870.0)
        val hy = rnd.nextDouble(200.0, 420.0)
        b.hole(hx, hy)
        taken += Spot(hx, hy, GREEN_R)

        val rings = 1 + rnd.nextInt(if (d < 0.3) 2 else 3)
        repeat(rings) {
            val r = rnd.nextDouble(34.0, 48.0)
            val spot = place(480.0, 1180.0, r + 60) ?: return@repeat
            val kind = rnd.nextFloat()
            val moving = d > 0.35 && rnd.nextFloat() < 0.15 + d * 0.3
            val move = if (moving) sway(rnd.nextDouble(120.0, 260.0), 0, rnd.nextDouble(4.5, 8.0), rnd.nextDouble(0.0, 6.0)) else null
            when {
                d > 0.2 && kind < 0.2 -> b.orbit(spot.first, spot.second, r, launch = 760, move = move)
                d > 0.2 && kind < 0.35 -> b.orbit(spot.first, spot.second, r, ring = r + 55, spin = 320, move = move)
                else -> b.orbit(spot.first, spot.second, r, move = move)
            }
        }
        val solids = rnd.nextInt(0, 1 + (d * 3).toInt().coerceAtMost(2) + 1)
        repeat(solids) {
            val r = rnd.nextDouble(50.0, 50.0 + 60 * d + 20)
            val spot = place(450.0, 1200.0, r + 20) ?: return@repeat
            b.planet(spot.first, spot.second, r)
        }
        val rocks = if (d < 0.15) 0 else rnd.nextInt(0, 2 + (d * 4).toInt())
        repeat(rocks) {
            val spot = place(380.0, 1250.0, 30.0) ?: return@repeat
            val move = if (d > 0.5 && rnd.nextFloat() < 0.4) sway(rnd.nextDouble(100.0, 300.0), 0, rnd.nextDouble(2.5, 5.0), rnd.nextDouble(0.0, 6.0)) else null
            b.rock(spot.first, spot.second, rnd.nextDouble(14.0, 24.0), move)
        }
        if (d > 0.3 && rnd.nextFloat() < 0.35) {
            place(450.0, 1150.0, 70.0)?.let { b.pulsar(it.first, it.second, rnd.nextDouble(0.5, 0.9)) }
        }
        if (d > 0.45 && rnd.nextFloat() < 0.3) {
            // Bumper rails down both sides keep long shots in play.
            b.wall(40, 120, 40, 1560); b.wall(960, 120, 960, 1560)
        }
        if (d > 0.55 && rnd.nextFloat() < 0.2) {
            val a = place(900.0, 1250.0, PORTAL_R + 10)
            val c = place(450.0, 800.0, PORTAL_R + 10)
            if (a != null && c != null) b.portal(a.first, a.second, c.first, c.second)
        }
        return b.build()
    }
}
