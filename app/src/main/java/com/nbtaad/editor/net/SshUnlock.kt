package com.nbtaad.editor.net

/**
 * Turns a stored stock `starter.cfg` into something ready to flash, for either direction - UNLOCK
 * (open SSH/telnet/qconn) or LOCK (back to stock). Composes [StarterCfgPatch] (the one
 * format-specific edit this feature needed a real car's file to write) with [FlashContainer] (the
 * generic no-signature file-copy wrapper); the actual write still goes through [FlashWrite.flash],
 * since that's the part that touches the car.
 */
object SshUnlock {
    sealed class PrepareResult {
        data class Ready(val container: ByteArray) : PrepareResult()
        data class AlreadyDone(val target: StarterCfgPatch.Target) : PrepareResult()
        data class PatchFailed(val reason: String) : PrepareResult()
    }

    fun prepare(stock: ByteArray, target: StarterCfgPatch.Target): PrepareResult {
        return when (val r = StarterCfgPatch.patch(stock, target)) {
            is StarterCfgPatch.Result.Patched -> PrepareResult.Ready(
                FlashContainer.buildFileCopyContainer(
                    fileBytes = r.bytes,
                    targetDir = FlashContainer.LIVE_PREFIX + "sys/etc/",
                    name = "starter.cfg",
                ),
            )
            StarterCfgPatch.Result.AlreadyInTargetState -> PrepareResult.AlreadyDone(target)
            StarterCfgPatch.Result.MarkerNotFound ->
                PrepareResult.PatchFailed("This file doesn't look like starter.cfg (no OPT_DEBUG package found).")
            StarterCfgPatch.Result.RequestStateNotFound ->
                PrepareResult.PatchFailed("Found the OPT_DEBUG package but not its RequestState - the format may differ from what this was verified against.")
        }
    }
}
