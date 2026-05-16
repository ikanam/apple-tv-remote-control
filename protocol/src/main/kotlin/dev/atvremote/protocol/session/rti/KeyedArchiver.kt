package dev.atvremote.protocol.session.rti

import dev.atvremote.protocol.session.Plist

/**
 * NSKeyedArchiver / RTIKeyedArchiver `$objects`/`$top`/`CF$UID` graph reader.
 *
 * Faithful port of pyatv `pyatv/protocols/companion/keyed_archiver.py`
 * (`read_archive_properties`), the authoritative reference this codebase
 * mirrors (CLAUDE.md pyatv-wins rule). pyatv source (verbatim):
 *
 * ```python
 * def read_archive_properties(archive, *paths):
 *     data = plistlib.loads(archive)
 *     results = []
 *     objects = data["$objects"]
 *     for path in paths:
 *         element = data["$top"]
 *         try:
 *             for key in path:
 *                 element = element[key]
 *                 if isinstance(element, plistlib.UID):
 *                     element = objects[element]
 *             results.append(element)
 *         except (IndexError, KeyError):
 *             results.append(None)
 *     return tuple(results)
 * ```
 *
 * Semantics mirrored exactly:
 *  - Start at `$top`; for each path key do `element = element[key]`, and **if**
 *    the result is a `CF$UID` (`Plist.Uid`) dereference it **once** via
 *    `$objects[idx]`, then continue with the next key.
 *  - It is a *lazy path-follower*: it does **NOT** collapse
 *    `NS.keys`/`NS.objects`/`NS.string`/`NS.uuidbytes` containers and does
 *    **NOT** strip `$class`. Resolved nodes are returned as-is (a dict still
 *    carries `$class`, an `NSUUID` still appears as `{NS.uuidbytes,$class}`).
 *    The plan's draft eager NS.*-resolver / `$class`-stripper diverges from
 *    pyatv and is intentionally NOT ported (pyatv/captured-bytes win).
 *  - A missing dict key or an out-of-range `$objects` index yields `null` for
 *    that path (pyatv catches `KeyError`/`IndexError`). One intentional
 *    divergence: indexing a non-dict leaf also yields `null` here, whereas
 *    pyatv raises an *uncaught* `TypeError` there (only `IndexError`/`KeyError`
 *    are caught) — we are deliberately safer than pyatv for that single case
 *    (a documented pyatv-wins exception, see [follow]).
 *
 * Builds on [Plist].`read` (which already yields Map/List/scalars with
 * [Plist.Uid] for `CF$UID`). `:protocol` is pure Kotlin/JVM; this is an
 * additive `session/rti/` helper — the locked Api.kt surface is untouched.
 */
object KeyedArchiver {

    /**
     * Resolve a single `$top` path, mirroring one iteration of pyatv
     * `read_archive_properties`. Returns the resolved element, or `null` if any
     * key is absent / a non-container is indexed / a UID is out of range.
     *
     * Path keys index dicts only (pyatv `element[key]` with string keys); a
     * `CF$UID` encountered after a step is dereferenced once against
     * `$objects` before the next key is applied (and once more for the final
     * element, exactly like pyatv).
     */
    fun readProperty(blob: ByteArray, vararg path: String): Any? {
        val plist = readPlist(blob)
        val objects = plist["\$objects"] as? List<*>
            ?: error("KeyedArchiver: blob has no \$objects array")
        val top = plist["\$top"] as? Map<*, *>
            ?: error("KeyedArchiver: blob has no \$top dict")
        return follow(top, path.asList(), objects)
    }

    /**
     * Resolve several paths at once — port of pyatv's variadic
     * `read_archive_properties(archive, *paths) -> tuple`. The blob is parsed
     * once; each path resolves independently, `null` on miss (pyatv `None`).
     */
    fun readProperties(blob: ByteArray, vararg paths: List<String>): List<Any?> {
        val plist = readPlist(blob)
        val objects = plist["\$objects"] as? List<*>
            ?: error("KeyedArchiver: blob has no \$objects array")
        val top = plist["\$top"] as? Map<*, *>
            ?: error("KeyedArchiver: blob has no \$top dict")
        return paths.map { follow(top, it, objects) }
    }

    /**
     * Convenience entry the plan named: parse the blob and expose `$top` with
     * its first-level UIDs resolved one hop (so callers can [path] into it).
     * Kept pyatv-faithful — values are resolved lazily/one-hop, NOT the plan's
     * eager full-graph collapse.
     */
    fun decode(blob: ByteArray): Map<String, Any?> {
        val plist = readPlist(blob)
        val objects = plist["\$objects"] as? List<*>
            ?: error("KeyedArchiver: blob has no \$objects array")
        @Suppress("UNCHECKED_CAST")
        val top = plist["\$top"] as? Map<String, Any?>
            ?: error("KeyedArchiver: blob has no \$top dict")
        return top.mapValues { (_, v) -> deref(v, objects) }
    }

    /** Walk nested Map keys (no UID deref); returns the leaf or `null`. */
    fun path(root: Any?, vararg keys: String): Any? {
        var cur: Any? = root
        for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }

    // ── internals ──────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun readPlist(blob: ByteArray): Map<String, Any?> =
        Plist.read(blob) as? Map<String, Any?>
            ?: error("KeyedArchiver: top-level plist is not a dict")

    /**
     * One pyatv path iteration (`for key in path: element = element[key]; if
     * UID: element = objects[element]`). Returns the resolved element, or
     * `null` if any key is absent or a UID is out of `$objects` range — that
     * pair is exactly pyatv's `except (IndexError, KeyError):
     * results.append(None)`. Indexing a non-dict leaf also returns `null`
     * here, but that is a deliberate safer-than-pyatv divergence (NOT parity):
     * pyatv raises an *uncaught* `TypeError` for that case (only
     * `IndexError`/`KeyError` are caught in `read_archive_properties`), so it
     * would propagate/crash there — we treat it as a miss instead (a
     * documented pyatv-wins exception). (A resolved value is returned as-is;
     * the bplist `$null` placeholder at `$objects[0]` surfaces as the string
     * `"$null"`, never Kotlin `null`, so a real value is never confused with a
     * miss.)
     */
    private fun follow(top: Map<*, *>, path: List<String>, objects: List<*>): Any? {
        var element: Any? = top
        for (key in path) {
            // Deliberate safer-than-pyatv divergence: pyatv raises an uncaught
            // TypeError when a path key indexes a non-dict leaf (only
            // IndexError/KeyError are caught there); we treat it as a miss (null).
            val map = element as? Map<*, *> ?: return null
            if (!map.containsKey(key)) return null             // pyatv: KeyError
            element = map[key]
            if (element is Plist.Uid) {                         // pyatv: isinstance UID
                val idx = element.value.toInt()
                if (idx < 0 || idx >= objects.size) return null // pyatv: IndexError
                element = objects[idx]                          // objects[element]
            }
        }
        return element
    }

    /** pyatv `if isinstance(element, plistlib.UID): element = objects[element]`. */
    private fun deref(element: Any?, objects: List<*>): Any? {
        if (element !is Plist.Uid) return element
        val idx = element.value.toInt()
        if (idx < 0 || idx >= objects.size) return null
        return objects[idx]
    }
}
