package com.kmapper.app

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Thin wrapper around a persistent `su` shell.
 *
 * We keep ONE su process alive for the lifetime of the capture service and
 * pipe commands into it, rather than spawning `su -c "..."` per call, because
 * per-call su invocations are too slow for real-time input (each spawn costs
 * 30-80ms on most devices, which is unacceptable for mouse-look).
 */
class RootShell {

    private var process: Process? = null
    private var stdin: DataOutputStream? = null

    /** Returns true if root was granted and the shell is alive. */
    fun open(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            process = p
            stdin = DataOutputStream(p.outputStream)
            // Sanity check root actually works.
            val ok = runQuick("id")
            ok.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /** Run a one-off command and block for its full output. Use sparingly (device scan only). */
    fun runQuick(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        p.waitFor()
        return out
    }

    /** Fire-and-forget write into the persistent su shell. Non-blocking. */
    fun exec(cmd: String) {
        try {
            stdin?.writeBytes("$cmd\n")
            stdin?.flush()
        } catch (e: Exception) {
            // Shell died; caller should re-open().
        }
    }

    /** Opens a raw process for streaming a long-lived command (e.g. `getevent -l`). */
    fun openStream(cmd: String): Process {
        return Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    }

    fun close() {
        try {
            stdin?.writeBytes("exit\n")
            stdin?.flush()
        } catch (_: Exception) {}
        process?.destroy()
        process = null
        stdin = null
    }
}
