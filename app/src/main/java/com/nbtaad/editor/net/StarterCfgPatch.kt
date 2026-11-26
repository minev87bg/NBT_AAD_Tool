package com.nbtaad.editor.net

/**
 * Flips the `OPT_DEBUG` package's `RequestState` in a real `starter.cfg` between STOP (SSH/telnet/
 * qconn closed) and RUN (open) - the one format-specific piece this feature needed a real sample to
 * write, rather than guess at.
 *
 * Confirmed against an actual car's file (not assumed): `starter.cfg` is an EB "DynamicConfig" XML
 * document (`<DynamicConfig version="30">`), not a flat QNX key-value file. Inside it,
 * `<PackageTable>` has one `<Package>` named `OPT_DEBUG` whose `<RequestState>` gates
 * `<HostsProcess>` entries 91 (`/bin/devc-pty`), 92 (`/bin/inetd`), 95 (`/bin/qconn`),
 * 96 (`/opt/sys/bin/qnxcommd`) and 72 (`/opt/sys/bin/srm`) - exactly the daemon set an unlocked car's
 * process list shows running, plus `srm`, which wasn't previously confirmed. `OPT_DEBUG` is this one
 * package; it isn't referenced by any other package in the sample this was verified against.
 *
 * This patches with a scoped TEXT substitution, not an XML parse/rewrite: the file's own formatting
 * (CRLF line endings, tab-indented `*Action` blocks, self-closing empty tags) carries through
 * byte-for-byte everywhere except the one value that changes, rather than trusting a round-trip
 * reserialization not to reformat something the car's own parser is stricter about than ours.
 */
object StarterCfgPatch {
    private const val MARKER = "<Name>OPT_DEBUG</Name>"
    private const val STOP_TAG = "<RequestState>STOP</RequestState>"
    private const val RUN_TAG = "<RequestState>RUN</RequestState>"

    // How far past the marker the RequestState tag is allowed to appear - generous for the verified
    // real layout (one short line below the Name), without being so wide it could wander into a
    // neighbouring Package's tag if a future build ever reorders fields.
    private const val SEARCH_WINDOW = 200

    enum class Target { UNLOCK, LOCK }

    sealed class Result {
        data class Patched(val bytes: ByteArray, val from: String, val to: String) : Result()
        /** The file doesn't look like a `starter.cfg` at all - no `OPT_DEBUG` package in it. Refuse
         * rather than write something that was never actually read off a car. */
        object MarkerNotFound : Result()
        /** The marker is there, but no `RequestState` tag follows it within [SEARCH_WINDOW] - the
         * format moved more than expected since this was verified; refuse rather than guess. */
        object RequestStateNotFound : Result()
        /** Already in the requested state - nothing to flash. */
        object AlreadyInTargetState : Result()
    }

    /** Pure function: never touches the car. [stock] is the file as read off it (or imported); the
     * caller flashes [Result.Patched.bytes] back via [FlashContainer]/[FlashWrite]. */
    fun patch(stock: ByteArray, target: Target): Result {
        val text = String(stock, Charsets.UTF_8)
        val markerAt = text.indexOf(MARKER)
        if (markerAt < 0) return Result.MarkerNotFound

        val windowEnd = (markerAt + MARKER.length + SEARCH_WINDOW).coerceAtMost(text.length)
        val window = text.substring(markerAt, windowEnd)

        val (from, to) = if (target == Target.UNLOCK) STOP_TAG to RUN_TAG else RUN_TAG to STOP_TAG
        val already = if (target == Target.UNLOCK) RUN_TAG else STOP_TAG

        val relIdx = window.indexOf(from)
        if (relIdx < 0) {
            return if (window.contains(already)) Result.AlreadyInTargetState else Result.RequestStateNotFound
        }

        val absIdx = markerAt + relIdx
        val patchedText = text.substring(0, absIdx) + to + text.substring(absIdx + from.length)
        // ASCII-only in every real sample seen so far (verified, not assumed) - UTF-8 round-trips
        // such content byte-for-byte, so this can't silently reformat anything outside the one
        // substitution above.
        return Result.Patched(patchedText.toByteArray(Charsets.UTF_8), from, to)
    }
}
