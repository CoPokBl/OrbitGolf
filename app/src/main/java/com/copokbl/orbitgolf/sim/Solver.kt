package com.copokbl.orbitgolf.sim

/**
 * Brute-force breadth-first solver. From each orbit it tries releasing on every
 * simulation step of one full revolution, and only counts an outcome if a run of
 * at least [minWindow] consecutive release steps share it, so a "solution" is one a
 * human thumb can actually hit.
 */
object Solver {
    class Result(val strokes: Int, val window: Int, val nodes: Int) {
        val solved get() = strokes > 0
        override fun toString() = if (solved) "solved in $strokes (sink window $window steps, $nodes nodes)" else "UNSOLVED ($nodes nodes)"
    }

    private class Node(val sim: Sim, val depth: Int)

    fun solve(
        level: Level,
        maxStrokes: Int = 4,
        minWindow: Int = 5,
        maxNodes: Int = 60,
        cancelled: () -> Boolean = { false },
    ): Result {
        val queue = ArrayDeque<Node>()
        val visits = HashMap<Int, Int>()
        val maxVisits = if (level.dynamic) 3 else 1
        val start = Sim(level)
        queue.add(Node(start, 0))
        visits[key(start)] = 1
        var nodes = 0
        while (queue.isNotEmpty() && nodes < maxNodes) {
            if (cancelled()) return Result(-1, 0, nodes)
            val node = queue.removeFirst()
            nodes++
            val depth = node.depth + 1
            if (depth > maxStrokes) continue
            val outcomes = sweep(node.sim)
            // Group consecutive equal outcomes (wrapping around one revolution when static).
            val n = outcomes.size
            var i = 0
            var bestSink = 0
            while (i < n) {
                val o = outcomes[i].code
                var j = i
                while (j + 1 < n && outcomes[j + 1].code == o) j++
                val len = j - i + 1
                if (o != DEAD && len >= minWindow) {
                    if (o == SINK) {
                        if (len > bestSink) bestSink = len
                    } else {
                        // Explore the middle of the window plus both ends (which may differ in direction).
                        for (pick in intArrayOf((i + j) / 2, i + 1, j - 1)) {
                            val st = outcomes[pick].state!!
                            val k = key(st)
                            val v = visits[k] ?: 0
                            if (v < maxVisits) {
                                visits[k] = v + 1
                                queue.add(Node(st, depth))
                            }
                        }
                    }
                }
                i = j + 1
            }
            if (bestSink > 0) return Result(depth, bestSink, nodes)
        }
        return Result(-1, 0, nodes)
    }

    private const val DEAD = -1
    private const val SINK = -2

    private class Outcome(val code: Int, val state: Sim?)

    private fun key(s: Sim): Int {
        val base = s.body * 2 + (if (s.dir > 0) 1 else 0)
        if (!s.level.dynamic) return base
        // Coarse time bucket so moving levels can revisit a planet at a different phase.
        return base * 1000 + ((s.t / 1.5).toInt() % 1000)
    }

    private fun sweep(from: Sim): Array<Outcome> {
        val orbit = from.copy()
        var steps = orbit.orbitSteps()
        // Static: one revolution plus overlap so a window straddling the start isn't split.
        steps = if (from.level.dynamic) (steps * 1.5).toInt() else steps + 40
        return Array(steps) {
            val shot = orbit.copy()
            shot.release()
            val res = fly(shot)
            orbit.step()
            res
        }
    }

    private fun fly(s: Sim): Outcome {
        while (true) {
            when (s.step()) {
                EventType.SINK -> return Outcome(SINK, null)
                EventType.CRASH, EventType.LOST -> return Outcome(DEAD, null)
                EventType.CAPTURE -> return Outcome(s.body, s)
                else -> {}
            }
        }
    }
}
