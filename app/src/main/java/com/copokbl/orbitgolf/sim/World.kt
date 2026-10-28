package com.copokbl.orbitgolf.sim

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure-Kotlin game simulation. No Android imports so it can be stepped by the
 * JVM unit tests and the level solver exactly the way the game steps it.
 *
 * World units: levels are 1000 wide and [Level.height] tall, y pointing down.
 */

const val DT = 1.0 / 120.0
const val WORLD_W = 1000.0
const val BALL_R = 9.0
const val HOLE_R = 24.0
const val HOLE_GM = 1.6e6
/** The "green": inside it the cup pulls harder and the ball loses speed. */
const val GREEN_R = 95.0
const val GREEN_PULL = 1400.0
const val GREEN_DRAG = 2.6
const val PORTAL_R = 42.0
const val MAX_FLIGHT = 9.0
const val OOB_MARGIN = 160.0
private const val SOFTEN2 = 20.0 * 20.0
const val DEFAULT_DENSITY = 2300.0
const val DEFAULT_SPIN = 210.0
const val DEFAULT_LAUNCH = 540.0

enum class Kind { ORBIT, SOLID, ROCK, PULSAR }

/** Sinusoidal motion: offset = (ax sin(wt+p), ay sin(wt+p+q)), q = PI/2 for circles. */
class Motion(val ax: Double, val ay: Double, val period: Double, val phase: Double, val circle: Boolean) {
    private val w = 2 * PI / period
    private val q = if (circle) PI / 2 else 0.0
    fun dx(t: Double) = ax * sin(w * t + phase)
    fun dy(t: Double) = ay * sin(w * t + phase + q)
    fun vx(t: Double) = ax * w * cos(w * t + phase)
    fun vy(t: Double) = ay * w * cos(w * t + phase + q)
}

class Body(
    val kind: Kind,
    val bx: Double,
    val by: Double,
    val r: Double,
    val gm: Double,
    /** Capture ring radius for ORBIT bodies, 0 otherwise. */
    val ring: Double = 0.0,
    /** Tangential speed while orbiting. */
    val spin: Double = DEFAULT_SPIN,
    /** Speed added along the tangent on release. */
    val launch: Double = DEFAULT_LAUNCH,
    val motion: Motion? = null,
) {
    fun x(t: Double) = if (motion == null) bx else bx + motion.dx(t)
    fun y(t: Double) = if (motion == null) by else by + motion.dy(t)
    fun vx(t: Double) = motion?.vx(t) ?: 0.0
    fun vy(t: Double) = motion?.vy(t) ?: 0.0
    val isBoost get() = kind == Kind.ORBIT && launch > DEFAULT_LAUNCH + 1
    val isFast get() = kind == Kind.ORBIT && spin > DEFAULT_SPIN + 1
}

class Wall(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

class Portal(val ax: Double, val ay: Double, val bx: Double, val by: Double)

class Level(
    val name: String,
    val par: Int,
    val bodies: List<Body>,
    val holeX: Double,
    val holeY: Double,
    val height: Double = 1600.0,
    val walls: List<Wall> = emptyList(),
    val portals: List<Portal> = emptyList(),
    val startBody: Int = 0,
    val startAngle: Double = PI / 2,
    /** +1 = clockwise on screen (y down), -1 = counter-clockwise. */
    val startDir: Int = 1,
    val hint: String? = null,
) {
    val dynamic = bodies.any { it.motion != null }
}

/** DONE = ball is in the cup; the world keeps ticking for moving planets. */
enum class Mode { ORBIT, FLIGHT, DONE }

enum class EventType { NONE, CAPTURE, SINK, CRASH, LOST, BOUNCE, PORTAL }

/**
 * Full mutable state of a hole in progress. Cheap to copy, which is what makes
 * trajectory previews and the solver possible.
 */
class Sim(val level: Level) {
    var t = 0.0
    var mode = Mode.ORBIT
    var body = level.startBody
    var angle = level.startAngle
    var dir = level.startDir
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var flightTime = 0.0
    var origin = -1
    var leftOrigin = false
    var portalCooldown = 0.0

    /** Index of the body the last event concerned (capture / crash). */
    var eventBody = -1

    // Where the ball was when the current shot was taken; used to respawn.
    var shotBody = level.startBody
    var shotAngle = level.startAngle
    var shotDir = level.startDir

    init {
        placeOnOrbit()
    }

    fun copy(): Sim {
        val s = Sim(level)
        s.t = t; s.mode = mode; s.body = body; s.angle = angle; s.dir = dir
        s.x = x; s.y = y; s.vx = vx; s.vy = vy
        s.flightTime = flightTime; s.origin = origin; s.leftOrigin = leftOrigin
        s.portalCooldown = portalCooldown; s.eventBody = eventBody
        s.shotBody = shotBody; s.shotAngle = shotAngle; s.shotDir = shotDir
        return s
    }

    fun placeOnOrbit() {
        val b = level.bodies[body]
        val c = cos(angle)
        val s = sin(angle)
        x = b.x(t) + b.ring * c
        y = b.y(t) + b.ring * s
        vx = b.vx(t) - dir * b.spin * s
        vy = b.vy(t) + dir * b.spin * c
    }

    fun release() {
        if (mode != Mode.ORBIT) return
        val b = level.bodies[body]
        shotBody = body; shotAngle = angle; shotDir = dir
        val c = cos(angle)
        val s = sin(angle)
        vx = b.vx(t) - dir * b.launch * s
        vy = b.vy(t) + dir * b.launch * c
        mode = Mode.FLIGHT
        flightTime = 0.0
        origin = body
        leftOrigin = false
        portalCooldown = 0.0
    }

    /** Put the ball back where the last shot was taken (after a crash / lost ball). */
    fun respawn() {
        mode = Mode.ORBIT
        body = shotBody; angle = shotAngle; dir = shotDir
        placeOnOrbit()
    }

    /** Resume a saved shot: ball on [body]'s ring at [angle], world clock at [time]. */
    fun restore(body: Int, angle: Double, dir: Int, time: Double) {
        if (body !in level.bodies.indices || level.bodies[body].kind != Kind.ORBIT) return
        t = time; mode = Mode.ORBIT
        this.body = body; this.angle = angle; this.dir = if (dir >= 0) 1 else -1
        shotBody = body; shotAngle = angle; shotDir = this.dir
        placeOnOrbit()
    }

    fun step(): EventType {
        if (mode == Mode.DONE) {
            t += DT
            return EventType.NONE
        }
        if (mode == Mode.ORBIT) {
            val b = level.bodies[body]
            t += DT
            angle += dir * b.spin / b.ring * DT
            if (angle > PI) angle -= 2 * PI else if (angle < -PI) angle += 2 * PI
            placeOnOrbit()
            return EventType.NONE
        }
        return flightStep()
    }

    private fun flightStep(): EventType {
        // Semi-implicit Euler: stable enough at 120 Hz and fully deterministic.
        var ax = 0.0
        var ay = 0.0
        val bodies = level.bodies
        for (i in bodies.indices) {
            val b = bodies[i]
            val dx = b.x(t) - x
            val dy = b.y(t) - y
            val d2 = dx * dx + dy * dy + SOFTEN2
            val f = b.gm / (d2 * sqrt(d2))
            ax += dx * f
            ay += dy * f
        }
        run {
            val dx = level.holeX - x
            val dy = level.holeY - y
            val d2 = dx * dx + dy * dy + SOFTEN2
            val f = HOLE_GM / (d2 * sqrt(d2))
            ax += dx * f
            ay += dy * f
            val d = sqrt(dx * dx + dy * dy)
            if (d < GREEN_R && d > 1e-6) {
                ax += dx / d * GREEN_PULL - vx * GREEN_DRAG
                ay += dy / d * GREEN_PULL - vy * GREEN_DRAG
            }
        }
        vx += ax * DT
        vy += ay * DT
        val px = x
        val py = y
        x += vx * DT
        y += vy * DT
        t += DT
        flightTime += DT
        if (portalCooldown > 0) portalCooldown -= DT

        if (hypot(level.holeX - x, level.holeY - y) < HOLE_R) return EventType.SINK

        for (i in bodies.indices) {
            val b = bodies[i]
            val bxNow = b.x(t)
            val byNow = b.y(t)
            val d = hypot(x - bxNow, y - byNow)
            if (d < b.r + BALL_R * 0.6) {
                eventBody = i
                return EventType.CRASH
            }
            if (b.kind == Kind.ORBIT) {
                if (i == origin && !leftOrigin) {
                    if (d > b.ring + 18) leftOrigin = true
                } else if (d <= b.ring) {
                    capture(i, bxNow, byNow)
                    return EventType.CAPTURE
                }
            }
        }

        var ev = EventType.NONE
        for (w in level.walls) {
            if (collideWall(w, px, py)) ev = EventType.BOUNCE
        }

        if (portalCooldown <= 0) {
            for (p in level.portals) {
                if (hypot(x - p.ax, y - p.ay) < PORTAL_R) {
                    x += p.bx - p.ax; y += p.by - p.ay
                    portalCooldown = 0.35
                    return EventType.PORTAL
                } else if (hypot(x - p.bx, y - p.by) < PORTAL_R) {
                    x += p.ax - p.bx; y += p.ay - p.by
                    portalCooldown = 0.35
                    return EventType.PORTAL
                }
            }
        }

        if (x < -OOB_MARGIN || x > WORLD_W + OOB_MARGIN || y < -OOB_MARGIN || y > level.height + OOB_MARGIN ||
            flightTime > MAX_FLIGHT
        ) return EventType.LOST
        return ev
    }

    private fun capture(i: Int, bx: Double, by: Double) {
        val b = level.bodies[i]
        val rx = x - bx
        val ry = y - by
        val rvx = vx - b.vx(t)
        val rvy = vy - b.vy(t)
        dir = if (rx * rvy - ry * rvx >= 0) 1 else -1
        angle = atan2(ry, rx)
        body = i
        mode = Mode.ORBIT
        eventBody = i
        placeOnOrbit()
    }

    private fun collideWall(w: Wall, px: Double, py: Double): Boolean {
        val ex = w.x2 - w.x1
        val ey = w.y2 - w.y1
        val len2 = ex * ex + ey * ey
        var u = ((x - w.x1) * ex + (y - w.y1) * ey) / len2
        if (u < 0) u = 0.0 else if (u > 1) u = 1.0
        val cx = w.x1 + ex * u
        val cy = w.y1 + ey * u
        var nx = x - cx
        var ny = y - cy
        var d = hypot(nx, ny)
        if (d >= BALL_R) {
            // Tunnelling guard: did we cross the infinite line between the two segment ends?
            val s0 = (px - w.x1) * ey - (py - w.y1) * ex
            val s1 = (x - w.x1) * ey - (y - w.y1) * ex
            if (s0 * s1 >= 0 || u <= 0.0 || u >= 1.0) return false
            nx = px - cx; ny = py - cy
            d = hypot(nx, ny)
        }
        if (d < 1e-6) return false
        nx /= d; ny /= d
        val vn = vx * nx + vy * ny
        if (vn >= 0) {
            // Moving away already; just push out.
            x = cx + nx * BALL_R; y = cy + ny * BALL_R
            return false
        }
        val restitution = 0.85
        vx -= (1 + restitution) * vn * nx
        vy -= (1 + restitution) * vn * ny
        x = cx + nx * (BALL_R + 0.5)
        y = cy + ny * (BALL_R + 0.5)
        return true
    }

    /** Orbit period of the body the ball currently sits on, in steps. */
    fun orbitSteps(): Int {
        val b = level.bodies[body]
        return (2 * PI * b.ring / b.spin / DT).toInt() + 1
    }
}
