package com.copokbl.orbitgolf

import android.content.Context

/** Everything persistent lives in one SharedPreferences file. */
class Save(context: Context) {
    private val p = context.getSharedPreferences("orbitgolf", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()
    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    /** Best stroke count per campaign level, 0 = never finished. */
    fun best(level: Int) = p.getInt("best_$level", 0)
    fun recordBest(level: Int, strokes: Int): Boolean {
        val old = best(level)
        if (old == 0 || strokes < old) {
            p.edit().putInt("best_$level", strokes).apply()
            return true
        }
        return false
    }

    // Endless: the current run lives here so hopping over to the campaign never loses it.
    /** Current endless hole number, 0 = no run in progress. */
    var eHole: Int
        get() = p.getInt("e_hole", 0)
        set(v) = p.edit().putInt("e_hole", v).apply()
    var eSeed: Long
        get() = p.getLong("e_seed", 0)
        set(v) = p.edit().putLong("e_seed", v).apply()
    var ePar: Int
        get() = p.getInt("e_par", 2)
        set(v) = p.edit().putInt("e_par", v).apply()
    /** Strokes relative to par over the run so far. */
    var eScore: Int
        get() = p.getInt("e_score", 0)
        set(v) = p.edit().putInt("e_score", v).apply()
    /** Consecutive holes at or under par. */
    var eStreak: Int
        get() = p.getInt("e_streak", 0)
        set(v) = p.edit().putInt("e_streak", v).apply()
    var eBestStreak: Int
        get() = p.getInt("e_best_streak", 0)
        set(v) = p.edit().putInt("e_best_streak", v).apply()
    var eAces: Int
        get() = p.getInt("e_aces", 0)
        set(v) = p.edit().putInt("e_aces", v).apply()

    /** The shot in progress, so the app can drop you straight back into it. */
    class Session(
        val endless: Boolean,
        val level: Int,
        val strokes: Int,
        val body: Int,
        val angle: Double,
        val dir: Int,
        val t: Double,
    )

    fun saveSession(s: Session) {
        p.edit()
            .putBoolean("s_valid", true)
            .putBoolean("s_endless", s.endless)
            .putInt("s_level", s.level)
            .putInt("s_strokes", s.strokes)
            .putInt("s_body", s.body)
            .putFloat("s_angle", s.angle.toFloat())
            .putInt("s_dir", s.dir)
            .putFloat("s_t", s.t.toFloat())
            .apply()
    }

    fun loadSession(): Session? {
        if (!p.getBoolean("s_valid", false)) return null
        return Session(
            p.getBoolean("s_endless", false), p.getInt("s_level", 0), p.getInt("s_strokes", 0),
            p.getInt("s_body", 0), p.getFloat("s_angle", 0f).toDouble(), p.getInt("s_dir", 1),
            p.getFloat("s_t", 0f).toDouble(),
        )
    }

    /** Where the campaign carries on from: first unfinished level. */
    fun firstUnfinished(count: Int): Int {
        for (i in 0 until count) if (best(i) == 0) return i
        return 0
    }

    /** You may play any finished level, plus the next two, so one tough hole never walls you off. */
    fun unlocked(level: Int, count: Int): Boolean {
        var done = -1
        for (i in 0 until count) if (best(i) > 0) done = i
        return level <= done + 2
    }
}
