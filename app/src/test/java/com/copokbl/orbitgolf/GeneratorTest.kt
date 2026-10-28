package com.copokbl.orbitgolf

import com.copokbl.orbitgolf.sim.Generator
import com.copokbl.orbitgolf.sim.Solver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeneratorTest {
    @Test
    fun generatesSolvableHolesQuickly() {
        var total = 0L
        for (hole in listOf(1, 3, 6, 10, 14, 18, 22, 26, 30, 40)) {
            val t0 = System.nanoTime()
            val g = Generator.generate(hole * 7919L, hole)
            val ms = (System.nanoTime() - t0) / 1_000_000
            total += ms
            assertNotNull("hole $hole", g)
            g!!
            println("hole %2d: %-12s par %d, %d bodies, seed +%d, %d ms".format(hole, g.level.name, g.par, g.level.bodies.size, g.seed - hole * 7919L, ms))
            // Rebuilding from the stored seed must give the same hole (that's how resume works).
            val again = Generator.build(g.seed, hole, g.par)
            assertEquals(g.level.bodies.size, again.bodies.size)
            assertEquals(g.level.holeX, again.holeX, 0.0)
            assertEquals(g.par, Solver.solve(again, maxStrokes = 4, minWindow = 10, maxNodes = 40).strokes)
        }
        println("total $total ms")
    }
}
