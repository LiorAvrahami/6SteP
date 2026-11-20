package org.lior.sixstep

/** What the explore page lists (the user's layout draft, 2026-10-28): three groups. Elements: the user's own list, in
 *  their order, whether each is an element or a building block underneath (2026-10-29); Compositions, with the quick
 *  recap; Building Blocks: everything else. Each row has its versions (the starting conditions of an element; a
 *  building block or a composition has one). */
class ExploreModel(data: DanceData, store: CompositionStore) {
    /** a row of the Elements group: a name, and when only some of its versions are elements, their start and end states
     *  (its other versions are building blocks) */
    class Pick(val name: String, val only: Set<Pair<String, String>>? = null)

    /** one way to dance a row's piece: an element from one starting condition, or the one way of a block or composition */
    class Version(val piece: Piece, val facing: String, val foot: String, val footShort: String, val cells: List<StepCell>,
                  val startState: String, val endState: String)

    /** `key`: the name under which the version chosen is remembered */
    class Row(val name: String, val versions: List<Version>, val key: String) {
        val title: String get() = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    companion object {
        /** the Elements group, in the order the user learned them (2026-11-16). User 2026-11-15: the rock step and the
         *  triple step are building blocks, and so are the follower underarm pass from side by side (to face to face, to
         *  reversed side); the one from face to face to face to face stays an element. Everything else is under
         *  Building Blocks. */
        val ELEMENTS = listOf(Pick("rock triple triple"), Pick("side to side"), Pick("follower underarm and back"),
            Pick("follower underarm pass", setOf("facing" to "facing")), Pick("rock back cutoff"), Pick("lead underarm pass"),
            Pick("circle"), Pick("follower underarm to arrow"), Pick("underarm circle"), Pick("send out and bring back"),
            Pick("yoyo"), Pick("tak turn"))
    }

    val elements: List<Row>
    val compositions: List<Row>
    val blocks: List<Row>
    /** the poses of every version of every element: what usual looks like (the start positions aside) */
    val corpus: List<DoubleArray>

    init {
        fun version(p: Piece): Version? {
            val c = p.compose(data, Piece.SWING)
            if (c.errors.isNotEmpty()) return null
            val t = Timeline(c, Piece.SWING)
            val left = c.startFree == 'L'
            return Version(p, c.startState.replace('_', ' ') + " → " + c.endState.replace('_', ' '),
                if (left) Texts.LEFT_FREE else Texts.RIGHT_FREE, if (left) Texts.LEFT_FREE_SHORT else Texts.RIGHT_FREE_SHORT,
                cellsOf(t, stopsOf(t), Texts.START), c.startState, c.endState)
        }
        val elementRows = LinkedHashMap<String, List<Version>>()
        for (name in data.names) {
            val vs = data.variants(name).indices.mapNotNull { i -> Piece.find(data, store, Piece.ELEMENT, name, i)?.let { version(it) } }
            if (vs.isNotEmpty()) elementRows[name] = vs
        }
        val blockRows = LinkedHashMap<String, List<Version>>()
        for (c in store.subs) {
            val vs = listOfNotNull(Piece.find(data, store, Piece.BLOCK, c.name)?.let { version(it) })
            if (vs.isNotEmpty()) blockRows[c.name] = vs
        }
        corpus = elementRows.values.flatMap { vs -> vs.flatMap { v -> v.cells.drop(1).mapNotNull { Rarity.descriptor(it.feet) } } }
        fun rowFor(name: String): Pair<String, List<Version>>? =
            (elementRows.entries.firstOrNull { it.key.equals(name, true) } ?: blockRows.entries.firstOrNull { it.key.equals(name, true) })?.toPair()
        // a name can have rows in two groups (some versions elements, the others building blocks): each remembers its own
        fun key(vs: List<Version>, name: String, group: String) =
            "explore_version_" + vs[0].piece.kind + "_" + name + (if (group == "blocks" && vs[0].piece.kind == Piece.ELEMENT) "_block" else "")
        val picked = ELEMENTS.mapNotNull { pick ->
            rowFor(pick.name)?.let { (name, vs) ->
                val only = pick.only
                val keep = if (only == null) vs else vs.filter { (it.startState to it.endState) in only }
                if (keep.isEmpty()) null else Pair(name, keep)
            }
        }
        elements = picked.map { (name, vs) -> Row(name, vs, key(vs, name, "elements")) }
        // every version not in the Elements group is a building block, under the same name
        val shown = picked.flatMap { it.second }.toSet()
        blocks = (elementRows + blockRows).mapNotNull { (name, vs) -> vs.filter { it !in shown }.takeIf { it.isNotEmpty() }?.let { Row(name, it, key(it, name, "blocks")) } }
        compositions = store.compositions.mapNotNull { c ->
            Piece.find(data, store, Piece.COMPOSITION, c.name)?.let { version(it) }?.let { Row(c.name, listOf(it), key(listOf(it), c.name, "compositions")) }
        }
    }

    /** the picture a row opens on: the step of this version that stands out most */
    fun centre(v: Version): Int = Rarity.centre(v.cells, corpus)
}
