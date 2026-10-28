package com.copokbl.orbitgolf

import com.copokbl.orbitgolf.sim.Levels
import com.copokbl.orbitgolf.sim.Solver
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {
    /**
     * Par must equal the strokes needed along a comfortable route (every release window
     * at least 10 sim steps = 83 ms). Tighter "ace" routes are allowed: they are the skill ceiling.
     */
    @Test
    fun parMatchesComfortableRoute() {
        val failures = ArrayList<String>()
        Levels.all.forEachIndexed { i, level ->
            val easy = Solver.solve(level, maxStrokes = 5, minWindow = 10, maxNodes = 300)
            val ace = Solver.solve(level, maxStrokes = 5, minWindow = 5, maxNodes = 200)
            println("%2d %-16s par %d | comfortable: %s | best: %s".format(i + 1, level.name, level.par, easy, ace))
            if (easy.strokes != level.par) failures += "${i + 1} ${level.name} (comfortable ${easy.strokes})"
        }
        assertTrue("Par mismatches: $failures", failures.isEmpty())
    }
}
