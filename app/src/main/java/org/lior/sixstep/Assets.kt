package org.lior.sixstep

import android.content.Context
import java.io.File

// The dance engine (the data, the composer, the timeline, the beat grids) is shared code that knows nothing of Android
// (the shared module, also used by the web front). These read its files for it from the app's assets, under the names
// the app's screens have always called.

private fun asset(ctx: Context, path: String): String = ctx.assets.open(path).bufferedReader().readText()

fun DanceData.Companion.load(ctx: Context): DanceData = parse(asset(ctx, "dance/skeletons.json"))

/** the shipped compositions, and the copy a phone kept from the time it had an editor (before 2026-10-28), if any */
fun CompositionStore(ctx: Context): CompositionStore {
    val saved = File(ctx.filesDir, "compositions.json")
    return CompositionStore(asset(ctx, "dance/compositions.json"), if (saved.exists()) saved.readText() else null)
}

fun BeatGrid.Companion.load(ctx: Context, path: String): BeatGrid = parse(asset(ctx, path))
/** the grid of a track (the beats annotated by ear in the beat annotator when that was done) */
fun BeatGrid.Companion.loadFor(ctx: Context, track: Track): BeatGrid = load(ctx, track.gridPath)

fun Track.Companion.loadAll(ctx: Context): List<Track> = parseAll(asset(ctx, "dance/tracks.json"))
