package com.copokbl.orbitgolf.sim

import kotlin.math.PI

class World(val name: String, val levels: List<Level>)

/** Small builder DSL so a level reads like a sketch of the screen (1000 wide, y down). */
class LevelBuilder(private val name: String, private val par: Int) {
    private val bodies = ArrayList<Body>()
    private val walls = ArrayList<Wall>()
    private val portals = ArrayList<Portal>()
    private var hx = 500.0
    private var hy = 200.0
    var height = 1600.0
    var hint: String? = null
    /** Start position on the first planet, degrees (0 = right, 90 = below). */
    var startDeg = 180.0
    /** true = clockwise. */
    var clockwise = true

    fun orbit(
        x: Number, y: Number, r: Number = 50, ring: Number? = null,
        spin: Number = DEFAULT_SPIN, launch: Number = DEFAULT_LAUNCH, move: Motion? = null,
        density: Number = DEFAULT_DENSITY,
    ) {
        val rr = r.toDouble()
        bodies += Body(
            Kind.ORBIT, x.toDouble(), y.toDouble(), rr, density.toDouble() * rr * rr,
            ring?.toDouble() ?: (rr + 60), spin.toDouble(), launch.toDouble(), move,
        )
    }

    fun planet(x: Number, y: Number, r: Number, density: Number = DEFAULT_DENSITY, move: Motion? = null) {
        val rr = r.toDouble()
        bodies += Body(Kind.SOLID, x.toDouble(), y.toDouble(), rr, density.toDouble() * rr * rr, motion = move)
    }

    fun rock(x: Number, y: Number, r: Number = 16, move: Motion? = null) {
        val rr = r.toDouble()
        bodies += Body(Kind.ROCK, x.toDouble(), y.toDouble(), rr, 600.0 * rr * rr, motion = move)
    }

    /** Repels instead of attracts. */
    fun pulsar(x: Number, y: Number, strength: Number = 1.0, move: Motion? = null) {
        bodies += Body(Kind.PULSAR, x.toDouble(), y.toDouble(), 20.0, -5.0e6 * strength.toDouble(), motion = move)
    }

    fun hole(x: Number, y: Number) {
        hx = x.toDouble(); hy = y.toDouble()
    }

    fun wall(x1: Number, y1: Number, x2: Number, y2: Number) {
        walls += Wall(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble())
    }

    fun portal(ax: Number, ay: Number, bx: Number, by: Number) {
        portals += Portal(ax.toDouble(), ay.toDouble(), bx.toDouble(), by.toDouble())
    }

    fun build() = Level(
        name, par, bodies.toList(), hx, hy, height, walls.toList(), portals.toList(),
        startBody = 0, startAngle = startDeg * PI / 180, startDir = if (clockwise) 1 else -1, hint = hint,
    )
}

fun level(name: String, par: Int, block: LevelBuilder.() -> Unit) = LevelBuilder(name, par).apply(block).build()

/** Side-to-side (or up-down) sway. */
fun sway(ax: Number, ay: Number, period: Number, phase: Number = 0) =
    Motion(ax.toDouble(), ay.toDouble(), period.toDouble(), phase.toDouble(), false)

/** Circular motion around the body's base position. */
fun circle(radius: Number, period: Number, phase: Number = 0, reverse: Boolean = false) =
    Motion(radius.toDouble(), radius.toDouble() * (if (reverse) -1 else 1), period.toDouble(), phase.toDouble(), true)

object Levels {
    val worlds: List<World> = listOf(
        World(
            "Launch Pad", listOf(
                level("Tee Off", 1) {
                    hint = "Tap anywhere to break orbit"
                    orbit(500, 1250, 55)
                    hole(500, 380)
                },
                level("Stepping Stone", 2) {
                    hint = "Fly through a dotted ring to catch a new orbit"
                    orbit(280, 1320, 50)
                    orbit(700, 820, 50)
                    planet(290, 780, 55)
                    hole(300, 300)
                },
                level("Zig Zag", 2) {
                    orbit(240, 1360, 45)
                    orbit(760, 1010, 45)
                    orbit(240, 660, 45)
                    hole(760, 300)
                },
                level("Booster", 2) {
                    hint = "Orange rings launch you harder"
                    orbit(500, 1380, 45)
                    orbit(500, 900, 50, launch = 780)
                    hole(500, 140)
                },
                level("Spinner", 2) {
                    hint = "Fast spinners need a sharp tap"
                    orbit(250, 1300, 45)
                    orbit(650, 780, 40, ring = 105, spin = 330)
                    planet(250, 800, 55)
                    hole(250, 300)
                },
                level("Detour", 2) {
                    hint = "Bare planets pull you in - don't touch them"
                    orbit(500, 1380, 45)
                    planet(500, 800, 90)
                    orbit(170, 800, 40)
                    orbit(830, 800, 40)
                    hole(500, 240)
                },
                level("Keyhole", 1) {
                    orbit(500, 1350, 50)
                    planet(190, 760, 60)
                    planet(810, 760, 60)
                    hole(500, 300)
                },
                level("Round Trip", 1) {
                    hint = "Bend your shot around a planet"
                    orbit(500, 860, 50)
                    planet(500, 1170, 95)
                    orbit(170, 320, 45)
                    orbit(830, 320, 45)
                    hole(500, 1460)
                },
            )
        ),
        World(
            "Gravity Well", listOf(
                level("Slingshot", 2) {
                    hint = "Skim a planet's gravity to bend your path"
                    orbit(180, 1380, 45)
                    planet(560, 820, 100, density = 2000)
                    orbit(870, 1200, 40)
                    hole(760, 520)
                },
                level("Asteroid Belt", 1) {
                    orbit(500, 1350, 50)
                    for (i in 0..6) rock(80 + i * 140, 780 + (i % 2) * 40)
                    hole(500, 280)
                },
                level("Twin Suns", 1) {
                    orbit(500, 1380, 45)
                    planet(260, 760, 95, density = 2600)
                    planet(740, 760, 95, density = 2600)
                    hole(500, 760)
                },
                level("Moonshot", 2) {
                    orbit(500, 1400, 45)
                    planet(380, 780, 125)
                    orbit(820, 700, 40)
                    hole(160, 300)
                },
                level("Hairpin", 2) {
                    orbit(200, 1400, 45)
                    planet(480, 760, 120)
                    rock(480, 470, 20)
                    rock(480, 1050, 20)
                    orbit(850, 760, 40)
                    hole(200, 300)
                },
                level("Heavyweight", 2) {
                    orbit(140, 1380, 45)
                    planet(500, 800, 150, density = 2600)
                    orbit(860, 1250, 40)
                    hole(820, 330)
                },
                level("Gauntlet", 2) {
                    orbit(500, 1400, 45)
                    rock(250, 1100); rock(500, 1080); rock(750, 1100)
                    orbit(200, 820, 40)
                    rock(400, 640, 22); rock(600, 600, 22)
                    planet(830, 560, 70)
                    hole(560, 220)
                },
                level("Scatter", 2) {
                    orbit(140, 1420, 45)
                    planet(600, 1150, 70)
                    orbit(820, 800, 40)
                    planet(380, 700, 75)
                    rock(700, 450); rock(560, 360); rock(840, 330)
                    hole(220, 240)
                },
            )
        ),
        World(
            "Bank & Warp", listOf(
                level("Bank Shot", 2) {
                    hint = "Bounce off walls"
                    orbit(500, 1350, 50)
                    wall(250, 800, 750, 800)
                    wall(40, 100, 40, 1500)
                    wall(960, 100, 960, 1500)
                    hole(500, 400)
                },
                level("Corridor", 1) {
                    orbit(500, 1420, 45)
                    wall(340, 1200, 340, 480); wall(660, 1200, 660, 480)
                    wall(340, 1200, 170, 1300); wall(660, 1200, 830, 1300)
                    rock(500, 760, 18)
                    hole(500, 260)
                },
                level("Wormhole", 1) {
                    hint = "Portals keep your speed and direction"
                    orbit(500, 1350, 50)
                    wall(40, 800, 960, 800)
                    portal(390, 1060, 500, 620)
                    hole(500, 250)
                },
                level("Pulsar", 2) {
                    hint = "Pink stars push you away"
                    orbit(500, 1380, 45)
                    pulsar(500, 800, 0.6)
                    orbit(170, 760, 40)
                    orbit(830, 760, 40)
                    hole(500, 260)
                },
                level("Guarded", 2) {
                    orbit(160, 1380, 45)
                    pulsar(620, 320, 0.6)
                    pulsar(380, 320, 0.6)
                    orbit(820, 900, 40)
                    hole(500, 220)
                },
                level("Pinball", 1) {
                    orbit(500, 1420, 45)
                    wall(40, 120, 40, 1500); wall(960, 120, 960, 1500); wall(40, 120, 960, 120)
                    pulsar(300, 900, 0.8); pulsar(700, 900, 0.8); pulsar(500, 560, 0.8)
                    wall(380, 760, 620, 760)
                    hole(500, 300)
                },
                level("Warp Hop", 2) {
                    orbit(160, 1400, 45)
                    wall(40, 760, 960, 760)
                    portal(470, 1170, 170, 560)
                    orbit(500, 420, 40)
                    hole(840, 220)
                },
                level("Ricochet", 1) {
                    orbit(400, 1420, 45)
                    wall(40, 140, 960, 140); wall(40, 140, 40, 1550); wall(960, 140, 960, 1550)
                    wall(640, 420, 960, 420)
                    rock(500, 820, 24)
                    hole(830, 290)
                },
            )
        ),
        World(
            "Clockwork", listOf(
                level("Ferry", 2) {
                    hint = "Some planets move - time your jump"
                    orbit(500, 1380, 45)
                    wall(330, 1180, 670, 1180)
                    orbit(500, 820, 40, move = sway(300, 0, 6))
                    wall(330, 520, 670, 520)
                    hole(500, 240)
                },
                level("Carousel", 2) {
                    orbit(500, 1400, 45)
                    planet(500, 750, 60)
                    orbit(500, 750, 35, ring = 85, move = circle(260, 9))
                    hole(500, 220)
                },
                level("Traffic", 1) {
                    orbit(500, 1400, 45)
                    rock(500, 1050, 22, move = sway(380, 0, 3.2))
                    rock(500, 820, 22, move = sway(380, 0, 2.6, 1.5))
                    rock(500, 590, 22, move = sway(380, 0, 3.6, 3.0))
                    hole(500, 260)
                },
                level("Pendulum", 1) {
                    orbit(160, 1400, 45)
                    planet(500, 820, 80, move = sway(250, 0, 5))
                    orbit(840, 760, 40)
                    hole(500, 240)
                },
                level("Orrery", 2) {
                    orbit(500, 1450, 45)
                    planet(500, 780, 70, density = 2600)
                    orbit(500, 780, 32, ring = 82, move = circle(250, 10))
                    rock(500, 780, 18, move = circle(150, 4, reverse = true))
                    hole(500, 200)
                },
                level("Rush Hour", 2) {
                    orbit(140, 1420, 45)
                    orbit(500, 1080, 38, move = sway(260, 0, 5))
                    rock(500, 860, 20, move = sway(400, 0, 2.5))
                    orbit(500, 620, 38, move = sway(260, 0, 6, 2))
                    rock(500, 400, 20, move = sway(400, 0, 2.2, 1))
                    hole(500, 200)
                },
                level("Drift", 2) {
                    orbit(500, 1420, 45)
                    orbit(280, 1000, 38, move = circle(120, 7))
                    orbit(720, 620, 38, move = circle(120, 7, reverse = true))
                    planet(500, 800, 45)
                    hole(500, 220)
                },
                level("Grand Final", 2) {
                    orbit(160, 1440, 45)
                    planet(500, 1150, 80, move = sway(200, 0, 6))
                    orbit(830, 900, 40, launch = 760)
                    pulsar(500, 700, 0.8, move = sway(0, 120, 4))
                    orbit(180, 560, 38, move = sway(0, 150, 5))
                    wall(600, 380, 960, 380)
                    rock(500, 260, 20, move = sway(250, 0, 3))
                    hole(800, 220)
                },
            )
        ),
    )

    val all: List<Level> = worlds.flatMap { it.levels }
}
