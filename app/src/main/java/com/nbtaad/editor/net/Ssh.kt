package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.log.LogText
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Properties

/**
 * Credentials / method as documented in the NBT-AAD project (Sq00ky/Supra-iDrive6-Android-Auto, install.md):
 *   ssh root@<head unit> -o HostKeyAlgorithms=+ssh-dss -m hmac-sha1     (older guides: +ssh-rsa)
 *   password: ts&SK412
 */
object SshConfig {
    const val USER = "root"
    const val PASSWORD = "ts&SK412"
    const val PORT = 22
}

/**
 * Splits a hand-typed address into (host, port). Plain "host" gets [defaultPort]; "host:port" carries its own -
 * for someone relaying ENET/SSH over a VPN through a tool that remaps the port (a custom ZeroTier/Tailscale setup,
 * an ENET-over-network relay such as FlashXCode or Turbocode), where the head unit's own fixed port rarely survives
 * the relay unchanged. A malformed or out-of-range port after the colon is dropped rather than carried into the
 * host (the unterminated "host:garbage" string that caused a stray UnknownHostException before this existed) -
 * [defaultPort] is used instead and only the part before the colon is kept as the host.
 */
fun parseHostPort(input: String, defaultPort: Int = SshConfig.PORT): Pair<String, Int> {
    val trimmed = input.trim()
    val i = trimmed.lastIndexOf(':')
    if (i <= 0) return trimmed to defaultPort
    val host = trimmed.substring(0, i)
    val port = trimmed.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    return host to (port ?: defaultPort)
}

/** The person stopped a long read. */
class SshCancelled : IOException("Stopped")

class SshSession(
    host: String,
    password: String = SshConfig.PASSWORD,
    /** Overrides the login name; MGU units log in as root too, but a future unit might not. */
    user: String = SshConfig.USER,
    port: Int = SshConfig.PORT,
    /** A PEM private key to log in with instead of [password] (MGU's root SSH is key-only - see Mgu.kt). */
    privateKey: ByteArray? = null,
) : AutoCloseable {
    private val session: Session

    init {
        val jsch = JSch()
        if (privateKey != null) jsch.addIdentity("car", privateKey, null, null)
        session = jsch.getSession(user, host, port)
        if (privateKey == null) session.setPassword(password)
        val cfg = Properties()
        cfg["StrictHostKeyChecking"] = "no"
        cfg["PreferredAuthentications"] = if (privateKey != null) "publickey" else "password,keyboard-interactive"
        // The head unit runs a very old OpenSSH (QNX): allow the legacy algorithms JSch disables by default.
        cfg["server_host_key"] = "ssh-dss,ssh-rsa,rsa-sha2-256,rsa-sha2-512,ecdsa-sha2-nistp256,ssh-ed25519"
        cfg["kex"] = "diffie-hellman-group-exchange-sha256,diffie-hellman-group-exchange-sha1," +
            "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1," +
            "ecdh-sha2-nistp256"
        val ciphers = "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc,blowfish-cbc"
        cfg["cipher.s2c"] = ciphers
        cfg["cipher.c2s"] = ciphers
        val macs = "hmac-sha1,hmac-sha2-256,hmac-sha2-512,hmac-md5"
        cfg["mac.s2c"] = macs
        cfg["mac.c2s"] = macs
        session.setConfig(cfg)
        session.timeout = 15000
        val t0 = System.currentTimeMillis()
        try {
            session.connect(8000)
            AppLog.d("SSH", "Connected to $host (${System.currentTimeMillis() - t0} ms)")
        } catch (e: com.jcraft.jsch.JSchException) {
            AppLog.w("SSH", "Connecting to $host failed after ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName}: ${e.message}")
            if (Reach.looksUnreachable(e)) throw SshUnreachable(e.message ?: e.toString(), e)
            throw e
        }
    }

    class Result(val out: ByteArray, val err: String, val exit: Int) {
        val text: String get() = String(out, Charsets.UTF_8)
    }

    /** Counts the bytes the channel takes from [inner], so a long upload can show how far it is. */
    private class CountingStream(private val inner: ByteArrayInputStream, private val onRead: (Long) -> Unit) :
        java.io.InputStream() {
        private var total = 0L
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) onRead(++total)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                total += n
                onRead(total)
            }
            return n
        }
    }

    /**
     * Runs one command (the head unit has no sftp/scp, so everything goes through exec channels). [onSent] is told
     * how many bytes of [stdin] have been handed to the connection so far.
     */
    fun exec(
        cmd: String,
        stdin: ByteArray? = null,
        timeoutMs: Long = 20000,
        /** Told how many bytes of output have come so far (a long read, such as a log, shows how far it is). */
        onOut: ((Long) -> Unit)? = null,
        /** Asked often; true stops the command and throws [SshCancelled]. */
        cancelled: (() -> Boolean)? = null,
        /** When positive: stop (and close the channel) once this many bytes of output have come; the exit status is then 0. */
        maxBytes: Long = -1,
        /** Last, so that it can be given as the trailing lambda. */
        onSent: ((Long) -> Unit)? = null,
    ): Result {
        val ch = session.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        val errBuf = ByteArrayOutputStream()
        ch.setErrStream(errBuf)
        val inner = stdin?.let { ByteArrayInputStream(it) }
        ch.setInputStream(
            when {
                inner == null -> null
                onSent != null -> CountingStream(inner, onSent)
                else -> inner
            }
        )
        val input = ch.inputStream
        val t0 = System.currentTimeMillis()
        ch.connect(5000)

        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val end = System.currentTimeMillis() + timeoutMs
        var early = false
        try {
            while (true) {
                if (cancelled?.invoke() == true) throw SshCancelled()
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                onOut?.invoke(out.size().toLong())
                if (maxBytes > 0 && out.size() >= maxBytes) {
                    early = true
                    break
                }
                if (ch.isClosed) {
                    while (input.available() > 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    break
                }
                if (System.currentTimeMillis() > end) {
                    AppLog.w("SSH", "exec timed out after $timeoutMs ms [${LogText.brief(cmd)}]")
                    throw IOException("Timed out running a command on the head unit")
                }
                Thread.sleep(40)
            }
            val res = Result(out.toByteArray(), errBuf.toString("UTF-8"), if (early) 0 else ch.exitStatus)
            AppLog.d(
                "SSH",
                "exec [${LogText.brief(cmd)}]" + (stdin?.let { " +${it.size} B in" } ?: "") +
                    " -> exit ${res.exit}, ${res.out.size} B out, ${res.err.length} B err, ${System.currentTimeMillis() - t0} ms" +
                    (if (res.out.size in 1..4000) "\n  out: " + res.text.trim() else "") +
                    (if (res.err.isNotBlank()) "\n  err: " + res.err.trim().take(600) else "")
            )
            return res
        } finally {
            ch.disconnect()
        }
    }

    override fun close() {
        session.disconnect()
    }
}

fun friendlyError(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return when {
        m.contains("Auth fail", true) -> "The head unit rejected the login (Auth fail)."
        m.contains("Algorithm negotiation fail", true) -> "SSH algorithm negotiation failed: $m"
        m.contains("refused", true) -> "Connection refused: SSH (port 22) is closed on the head unit."
        m.contains("timed out", true) || m.contains("timeout", true) -> "Timed out talking to the head unit ($m)."
        else -> m
    }
}

object EnvRemote {
    // The SSH login lands on one processor of the head unit, but the daemon lives on the other one ("hu-omap"); from
    // the login shell its folders are reachable through the QNX network path. Where nbt-aad.env is depends on who
    // installed Android Auto, which HeadUnit.envFolder works out.
    const val FILE = "nbt-aad.env"

    sealed class ReadResult {
        data class Found(val text: String, val dir: String) : ReadResult()
        data class NoFile(val dir: String) : ReadResult()
        data class NoFolder(val detail: String) : ReadResult()
    }

    /** The folder of nbt-aad.env, as the install is laid out (the same look as the Install page: /opt/aa, or NBTApps' data folder). */
    private fun findDir(ssh: SshSession): String? =
        com.nbtaad.editor.install.HeadUnit.envFolder(ssh)

    /**
     * What the login shell can see, shown to the user when the folder is not found. No `| head` here -
     * a Tools Check run against this exact QNX image (2026-11-23) confirmed it has no `head` binary at
     * all, so piping to it would silently lose this diagnostic's output right when it matters most.
     * `.take(600)` below is the only truncation, same overall cap as before.
     */
    private fun diagnose(ssh: SshSession): String {
        val r = ssh.exec(
            "echo '/opt:'; ls /opt 2>&1; echo '/net:'; ls /net 2>&1; " +
                "echo 'hu-omap opt:'; ls /net/hu-omap/fs/sda0/opt 2>&1; " +
                "echo 'nbtapp:'; ls /var/opt/nbtapp /var/opt/nbtapp/apps /var/opt/nbtapp/data 2>&1; " +
                "echo 'hu-omap nbtapp:'; ls /net/hu-omap/var/opt/nbtapp /net/hu-omap/var/opt/nbtapp/data 2>&1"
        )
        return r.text.trim().take(600)
    }

    fun read(host: String, progress: (String) -> Unit): ReadResult {
        progress("Logging in to $host…")
        return SshSession(host).use<SshSession, ReadResult> { ssh ->
            progress("Looking for the NBT-AAD folder…")
            val dir = findDir(ssh) ?: return@use ReadResult.NoFolder(diagnose(ssh))
            val path = "$dir/$FILE"
            progress("Reading $path…")
            val r = ssh.exec("if [ -f $path ]; then cat $path; else echo __NOFILE__; fi")
            val t = r.text
            if (t.trim() == "__NOFILE__") ReadResult.NoFile(dir) else ReadResult.Found(t, dir)
        }
    }

    /** Uploads [bytes] as nbt-aad.env (keeping nbt-aad.env.bak on the head unit) and verifies it by reading it back. */
    fun write(host: String, bytes: ByteArray, progress: (String) -> Unit) {
        progress("Logging in to $host…")
        SshSession(host).use { ssh ->
            val dir = findDir(ssh)
                ?: throw IOException("Could not find the NBT-AAD folder (/opt/aa, or NBTApps' folders in /var/opt/nbtapp) on the head unit. Is NBT-AAD installed?")
            val path = "$dir/$FILE"

            progress("Making the partition writable…")
            var r = ssh.exec("mount -o remount rw /net/hu-omap/fs/sda0")
            if (r.exit != 0) {
                r = ssh.exec("mount -o remount rw /fs/sda0")
                if (r.exit != 0) progress("Remount reported: ${r.err.trim().ifEmpty { "exit ${r.exit}" }} (continuing)")
            }

            progress("Uploading ${bytes.size} bytes to $path…")
            r = ssh.exec("cat > $path.new", stdin = bytes)
            if (r.exit != 0) throw IOException("Upload failed: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")

            progress("Replacing $FILE…")
            // The head unit's shell has no "sync" command, so none is used; the read-back below proves the write.
            // Backups next to the file: .orig is made once (the oldest copy), then .bak is the previous version,
            // .bak2 the one before and .bak3 the one before that, so one more save never destroys the last good copy.
            r = ssh.exec(
                "if [ -f $path ]; then [ -f $path.orig ] || cp $path $path.orig; " +
                    "[ -f $path.bak2 ] && cp $path.bak2 $path.bak3; " +
                    "[ -f $path.bak ] && cp $path.bak $path.bak2; " +
                    "cp $path $path.bak; fi; " +
                    "mv $path.new $path"
            )
            if (r.exit != 0) throw IOException("Could not replace the file: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")

            progress("Verifying…")
            val back = ssh.exec("cat $path")
            if (!back.out.contentEquals(bytes)) {
                throw IOException("Verification failed: the file on the head unit differs from what was sent.")
            }
        }
    }
}
