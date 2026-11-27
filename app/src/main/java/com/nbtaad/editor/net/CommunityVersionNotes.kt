package com.nbtaad.editor.net

/**
 * Plain reference notes from another, independent community project
 * (github.com/Xayanix/nbt-evo-ssh-unlocker) about which NBT EVO builds its own starter.cfg patch is
 * reported to work on. This is a lead for prioritizing which PSdZData files are worth pulling first -
 * nothing more. It is never wired into [StarterCfgStore.hasStock]/[StarterCfgStore.readStock]'s exact-
 * tag matching, and never loosens it: that project's own version numbering ("007_001_NNN") hasn't
 * been confirmed to be the same axis as [FlashWrite.readCoreSwflVersion] reads here (our own car
 * tonight reported "007.051.002" - a different sub-version entirely), so a number appearing on their
 * list is a reason to go pull that exact build and add it to the library, never a reason to treat an
 * untested unit as compatible. Their own README underlines the stakes of getting this wrong: "a wrong
 * or version-mismatched file can leave the head unit unable to boot."
 */
object CommunityVersionNotes {
    /** Normalized to this app's own dotted tag form (see [StarterCfgStore.swflVersionFromFilename]) -
     * their README's own wording is "007_001_016 / 030 / 039 / 049 / 051". */
    val reportedWorking = listOf("007.001.016", "007.001.030", "007.001.039", "007.001.049", "007.001.051")

    private val reportedIncompatible = Regex("""^007\.001\.05\d$""")

    const val SUMMARY = "Another tool (Xayanix/nbt-evo-ssh-unlocker, unverified here) reports 007.001.016/.030/.039/.049/.051 " +
        "working and 007.001.05x using a different starter.cfg - a lead for which build to pull next, never a substitute " +
        "for this library's own exact match."

    /** A short, build-specific note for [tag], or null if this external source says nothing about it.
     * Never authoritative on its own - see the class doc. */
    fun note(tag: String): String? = when {
        tag in reportedWorking ->
            "Xayanix/nbt-evo-ssh-unlocker reports this exact build works (unverified here - still only a lead)."
        reportedIncompatible.matches(tag) ->
            "Xayanix/nbt-evo-ssh-unlocker reports 007.001.05x builds use a different starter.cfg - extra caution warranted."
        else -> null
    }
}
