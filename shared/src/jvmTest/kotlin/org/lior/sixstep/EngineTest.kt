package org.lior.sixstep

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The engine on the data the app ships (read from the Android app's assets). Always: everything shipped can be danced.
 *  With SIXSTEP_FIXTURE (written by tools/check_engine.py from the Python model): the same steps, at the same times and
 *  places, as the Python model makes. */
class EngineTest {
    private val assets = File("../app/src/main/assets/dance")
    private val data = DanceData.parse(File(assets, "skeletons.json").readText())
    private val store = CompositionStore(File(assets, "compositions.json").readText())
    private val swing = 0.67

    @Test fun everyShippedCompositionCanBeDanced() {
        assertTrue(store.compositions.isNotEmpty(), "no compositions were read")
        for (c in store.compositions) {
            val r = Composer.compose(data, store.sequence(c), c.startState, swing)
            assertTrue(r.errors.isEmpty(), "${c.name}: ${r.errors}")
            assertTrue(r.length > 0, "${c.name} is empty")
            val t = Timeline(r, swing)
            val stops = stopsOf(t)
            assertTrue(stops.size > 2, "${c.name} has no stops")
            assertEquals(stops.size, cellsOf(t, stops, "start").size)
        }
    }

    @Test fun theDailyExerciseAndTheQuickRecapAreThere() {
        val d = store.daily() ?: fail("no daily exercise")
        assertTrue(d.name.contains("exercise", ignoreCase = true), "the daily exercise is '${d.name}'")
        assertTrue(store.find(CompositionStore.QUICK_RECAP) != null, "no quick recap")
    }

    @Test fun everyElementAndBuildingBlockCanBeDanced() {
        for (name in data.names) for ((i, v) in data.variants(name).withIndex()) {
            val p = Piece.find(data, store, Piece.ELEMENT, name, i) ?: fail("no piece for $name #$i")
            val r = p.compose(data, swing)
            assertTrue(r.errors.isEmpty(), "$name from ${v.startState}: ${r.errors}")
        }
        for (b in store.subs) {
            val p = Piece.find(data, store, Piece.BLOCK, b.name) ?: fail("no piece for the block ${b.name}")
            val r = p.compose(data, swing)
            // a building block that cannot be danced only loses its row in Explore (tools/make_assets.py warns): not a failure
            if (r.errors.isNotEmpty()) println("warning: the building block '${b.name}' cannot be danced: ${r.errors}")
        }
    }

    @Test fun theTracksAndTheirGridsRead() {
        val tracks = Track.parseAll(File(assets, "tracks.json").readText())
        assertTrue(tracks.isNotEmpty(), "no tracks")
        for (t in tracks) {
            val g = BeatGrid.parse(File(assets.parentFile, t.gridPath).readText())
            assertTrue(g.beats.size > 16 && g.bpm > 60, "${t.key}: a grid of ${g.beats.size} beats at ${g.bpm} BPM")
            assertTrue(t.variants.any { it.offset == 0 }, "${t.key} has no original tempo")
            for (v in t.variants) assertTrue(File(assets.parentFile, v.file).exists(), "${t.key}: ${v.file} is missing")
        }
    }

    // ------------------------------------------------------------------ against the Python model

    private fun close(a: Double, b: Double, what: String) { if (abs(a - b) > 1e-6) fail("$what: Kotlin $a, Python $b") }

    private fun compare(what: String, r: Composed, py: JsonObj) {
        assertEquals(py.optArr("errors").strings(), r.errors, "$what: errors")
        close(r.length, py.num("length"), "$what: length")
        assertEquals(py.str("end_state"), r.endState, "$what: end state")
        assertEquals(py.str("end_free"), r.endFree.toString(), "$what: end free foot")
        val pe = py.arr("elements")
        assertEquals(pe.size, r.elements.size, "$what: number of elements")
        for (i in 0 until pe.size) {
            val a = r.elements[i]; val b = pe.obj(i)
            assertEquals(b.str("name"), a.name, "$what: element $i")
            close(a.start, b.num("start"), "$what: element $i start")
            assertEquals(b.str("start_state"), a.startState, "$what: element $i (${a.name}) start state")
            assertEquals(b.str("end_state"), a.endState, "$what: element $i (${a.name}) end state")
        }
        val ps = py.arr("steps")
        assertEquals(ps.size, r.steps.size, "$what: number of steps")
        // the same steps; two that land at the same instant may be listed in either order
        fun key(t: Double, dancer: String, foot: String) = "${(t * 1e6).toLong()} $dancer $foot"
        val mine = r.steps.associateBy { key(it.t, it.dancer.toString(), it.foot.toString()) }
        for (i in 0 until ps.size) {
            val b = ps.obj(i)
            val a = mine[key(b.num("t"), b.str("dancer"), b.str("foot"))] ?: fail("$what: no step at beat ${b.num("t")} for ${b.str("dancer")} ${b.str("foot")}")
            val at = "$what: step at beat ${a.t} ${a.dancer}.${a.foot}"
            close(a.x, b.num("x"), "$at x"); close(a.y, b.num("y"), "$at y"); close(a.heading, b.num("heading"), "$at heading")
            assertEquals(b.int("element"), a.element, "$at element")
            val via = b.optArr("via")
            assertEquals(via?.size ?: 0, a.via.size, "$at anchors of the path")
            if (via != null) for (q in 0 until via.size) { close(a.via[q].first, via.arr(q).num(0), "$at anchor $q x"); close(a.via[q].second, via.arr(q).num(1), "$at anchor $q y") }
        }
    }

    @Test fun theSameDanceAsThePythonModel() {
        val path = System.getenv("SIXSTEP_FIXTURE")
        if (path.isNullOrEmpty() || !File(path).exists()) { println("no SIXSTEP_FIXTURE: the comparison with the Python model is skipped (run tools/check_engine.py)"); return }
        val fx = Json.obj(File(path).readText())
        val sw = fx.num("swing")
        val comps = fx.arr("compositions")
        var n = 0
        for (i in 0 until comps.size) {
            val o = comps.obj(i)
            val c = store.find(o.str("name")) ?: fail("no composition ${o.str("name")}")
            compare("composition ${c.name}", Composer.compose(data, store.sequence(c), c.startState, sw), o.obj("composed")); n++
        }
        val blocks = fx.arr("blocks")
        for (i in 0 until blocks.size) {
            val o = blocks.obj(i)
            val p = Piece.find(data, store, Piece.BLOCK, o.str("name")) ?: fail("no block ${o.str("name")}")
            compare("block ${p.name}", p.compose(data, sw), o.obj("composed")); n++
        }
        val els = fx.arr("elements")
        for (i in 0 until els.size) {
            val o = els.obj(i)
            val vs = data.variants(o.str("name"))
            val k = vs.indexOfFirst { it.startState == o.str("start_state") && it.leaderFreeStart.toString() == o.str("free") && it.endState == o.str("end_state") }
            if (k < 0) fail("no element ${o.str("name")} from ${o.str("start_state")} (${o.str("free")}) to ${o.str("end_state")}")
            val p = Piece.find(data, store, Piece.ELEMENT, o.str("name"), k)!!
            compare("element ${p.name} from ${o.str("start_state")} ${o.str("free")} to ${o.str("end_state")}", p.compose(data, sw), o.obj("composed")); n++
        }
        println("the same dance as the Python model: $n pieces compared")
    }
}
