package com.nbtaad.editor.net

/**
 * Reads the live `starter.cfg` off the head unit over the same SSH [EnvRemote] already uses for
 * `nbt-aad.env` - needs SSH already open (OPEN SSH / Bavarian); this doesn't unlock anything new.
 * It's purely a READ: the actual SSH unlock write goes over UDS ([FlashWrite]), never SSH. What this
 * returns becomes the stock reference [StarterCfgStore] keeps for this car's exact software build.
 */
object StarterCfgRemote {
    private const val REL = "fs/sda0/opt/sys/etc/starter.cfg"

    // Mirrors EnvRemote.write's own mount fallback: the HU's other processor ("hu-omap") is sometimes
    // reachable directly from the login shell, sometimes only through the QNX network path - try both,
    // in the same order the remount there does.
    private val CANDIDATES = listOf("/net/hu-omap/$REL", "/$REL")

    sealed class ReadResult {
        data class Found(val bytes: ByteArray, val path: String) : ReadResult()
        object NotFound : ReadResult()
    }

    fun readLive(host: String, progress: (String) -> Unit): ReadResult {
        progress("Logging in to $host…")
        return SshSession(host).use<SshSession, ReadResult> { ssh ->
            for (path in CANDIDATES) {
                progress("Reading $path…")
                val r = ssh.exec("if [ -f $path ]; then cat $path; else echo __NOFILE__; fi")
                if (r.text.trim() != "__NOFILE__") return@use ReadResult.Found(r.out, path)
            }
            ReadResult.NotFound
        }
    }
}
