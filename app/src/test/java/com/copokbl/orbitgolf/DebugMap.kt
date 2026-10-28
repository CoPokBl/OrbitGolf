package com.copokbl.orbitgolf

import com.copokbl.orbitgolf.sim.EventType
import com.copokbl.orbitgolf.sim.Levels
import com.copokbl.orbitgolf.sim.Sim
import org.junit.Test
import kotlin.math.hypot

/**
 * Prints a release-timing map for the first stroke of the levels named in ORBIT_DEBUG
 * (comma separated 1-based indices). Skipped when the variable is unset.
 * Legend: S sink, . crash, - lost, a/b/c.. capture body (upper case = clockwise), digits = closest hole pass /100.
 */
class DebugMap {
    @Test
    fun map() {
        val which = System.getenv("ORBIT_DEBUG") ?: return
        for (idx in which.split(",").map { it.trim().toInt() }) {
            val level = Levels.all[idx - 1]
            println("== $idx ${level.name}")
            val orbit = Sim(level)
            val sb = StringBuilder()
            val n = orbit.orbitSteps() * (if (level.dynamic) 2 else 1)
            for (f in 0 until n) {
                val s = orbit.copy(); s.release()
                var minD = 1e9
                var c: Char
                while (true) {
                    val e = s.step()
                    minD = minOf(minD, hypot(s.x - level.holeX, s.y - level.holeY))
                    c = when (e) {
                        EventType.SINK -> 'S'
                        EventType.CRASH -> if (minD < 1000) ('0' + (minD / 100).toInt().coerceAtMost(9)) else '.'
                        EventType.LOST -> '-'
                        EventType.CAPTURE -> if (s.dir > 0) 'A' + s.body else 'a' + s.body
                        else -> continue
                    }
                    break
                }
                sb.append(c)
                orbit.step()
            }
            sb.chunked(100).forEach { println(it) }
        }
    }
}
