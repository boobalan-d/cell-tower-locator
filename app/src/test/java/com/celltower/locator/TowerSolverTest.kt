package com.celltower.locator

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Simulated field tests: a hidden tower, realistic signal (path-loss n=3.3, 6 dB shadow
 * fading) and the solver must find it. Run with `gradle testDebugUnitTest`.
 */
class TowerSolverTest {

    private val tower = doubleArrayOf(13.0827, 80.2707)   // Chennai

    private fun offset(lat: Double, lon: Double, dx: Double, dy: Double) =
        doubleArrayOf(lat + dy / 110_540.0, lon + dx / (111_320.0 * cos(Math.toRadians(lat))))

    private fun err(s: Solution) = GeoMath.distance(s.lat, s.lon, tower[0], tower[1])

    private fun rssAt(p: DoubleArray, rnd: Random): Int {
        val d = max(GeoMath.distance(p[0], p[1], tower[0], tower[1]), 10.0)
        // Realistic LTE: about -100 dBm at 1 km, path-loss exponent 3.3, 6 dB fading
        return (-100 - 33 * (log10(d) - 3) + rnd.nextGaussian() * 6).toInt()
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    @Test
    fun loopAroundTower_within80m() {
        val errors = (1..7).map { seed ->
            val rnd = Random(seed.toLong())
            val samples = (0 until 60).map { k ->
                val a = 2 * PI * k / 60
                val p = offset(tower[0], tower[1], 350 * cos(a) + rnd.nextGaussian() * 30, 350 * sin(a))
                Sample(p[0], p[1], rssAt(p, rnd), null, 0)
            }
            err(TowerSolver.solve(samples, null)!!)
        }
        println("loop-around median error = ${median(errors)} m")
        assertTrue(median(errors) < 80)
    }

    @Test
    fun walkBesideTower_beatsCentroid() {
        val errors = (1..7).map { seed ->
            val rnd = Random(seed.toLong())
            val c = offset(tower[0], tower[1], 400.0, 100.0)
            val samples = (0 until 60).map { k ->
                val a = 2 * PI * k / 60
                val p = offset(c[0], c[1], 250 * cos(a), 250 * sin(a))
                Sample(p[0], p[1], rssAt(p, rnd), null, 0)
            }
            err(TowerSolver.solve(samples, null)!!)
        }
        // A plain centroid is stuck inside the walked circle: ~280 m off here
        println("walk-beside median error = ${median(errors)} m")
        assertTrue(median(errors) < 150)
    }

    @Test
    fun timingAdvanceFromThreeSpots_within100m() {
        val spots = listOf(600.0 to 0.0, -200.0 to 500.0, 100.0 to -700.0)
        val samples = spots.map { (dx, dy) ->
            val p = offset(tower[0], tower[1], dx, dy)
            val d = GeoMath.distance(p[0], p[1], tower[0], tower[1])
            val ta = (d / CellReader.LTE_TA_M).toInt()
            Sample(p[0], p[1], -95, ((ta + 0.5) * CellReader.LTE_TA_M).toInt(), 0)
        }
        val e = err(TowerSolver.solve(samples, null)!!)
        println("TA 3-spot error = $e m")
        assertTrue(e < 100)
    }

    @Test
    fun fusionWithDatabasePrior_improvesOneSidedDrive() {
        val rnd = Random(3)
        // Straight drive past one side: signal alone can't tell which side the tower is
        val samples = (0 until 60).map { k ->
            val p = offset(tower[0], tower[1], -1200.0 + 40 * k, 300.0)
            Sample(p[0], p[1], rssAt(p, rnd), null, 0)
        }
        val dbGuess = offset(tower[0], tower[1], 250.0, -350.0)  // database ~430 m off
        val fused = TowerSolver.solve(samples, Prior(dbGuess[0], dbGuess[1], 500.0))
        assertNotNull(fused)
        val dbErr = GeoMath.distance(dbGuess[0], dbGuess[1], tower[0], tower[1])
        println("fused error = ${err(fused!!)} m vs database alone = $dbErr m")
        assertTrue(err(fused) < dbErr)
    }

    @Test
    fun tooFewPoints_returnsNull() {
        val p = offset(tower[0], tower[1], 100.0, 100.0)
        assertTrue(TowerSolver.solve(listOf(Sample(p[0], p[1], -80, null, 0)), null) == null)
    }
}
