package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Protocol
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Autostart (HKCU Run key) and firewall rules. Everything goes through PowerShell -EncodedCommand to avoid quoting issues. */
object WindowsIntegration {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Path of SyncMusic.exe when running from the installed app, null when running from the IDE. */
    fun appExePath(): String? {
        val fromJpackage = System.getProperty("jpackage.app-path")
        if (!fromJpackage.isNullOrBlank() && fromJpackage.endsWith(".exe", ignoreCase = true)) return fromJpackage
        val cmd = try { ProcessHandle.current().info().command().orElse(null) } catch (_: Throwable) { null }
        if (cmd != null && cmd.endsWith(".exe", ignoreCase = true) &&
            !cmd.endsWith("java.exe", ignoreCase = true) && !cmd.endsWith("javaw.exe", ignoreCase = true)
        ) return cmd
        return null
    }

    val canIntegrate: Boolean get() = isWindows && appExePath() != null

    private fun psQuote(value: String) = "'" + value.replace("'", "''") + "'"

    private fun encode(script: String): String =
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    private fun runPowerShell(script: String, timeoutSec: Long = 30): Boolean = try {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-EncodedCommand", encode(script),
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(timeoutSec, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (t: Throwable) {
        AppLog.w("PowerShell call failed", t)
        false
    }

    private const val RUN_KEY = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    fun setAutostart(enabled: Boolean): Boolean {
        if (!isWindows) return false
        val exe = appExePath() ?: return false
        val script = if (enabled) {
            val value = "\"$exe\" --minimized"
            "Set-ItemProperty -Path ${psQuote(RUN_KEY)} -Name 'SyncMusic' -Value ${psQuote(value)}"
        } else {
            "Remove-ItemProperty -Path ${psQuote(RUN_KEY)} -Name 'SyncMusic' -ErrorAction SilentlyContinue; exit 0"
        }
        val ok = runPowerShell(script)
        AppLog.i("Autostart set to $enabled: $ok")
        return ok
    }

    /**
     * Opens the firewall for the app (asks for administrator rights via UAC).
     * Also removes "Block" rules Windows creates when its own prompt was dismissed.
     */
    fun openFirewall(tcpPort: Int): Boolean {
        if (!isWindows) return false
        val exe = appExePath()
        val inner = buildString {
            appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
            if (exe != null) {
                appendLine("\$exe = ${psQuote(exe)}")
                appendLine("Get-NetFirewallApplicationFilter -Program \$exe | Get-NetFirewallRule | Where-Object { \$_.Action -eq 'Block' } | Remove-NetFirewallRule")
            }
            appendLine("Get-NetFirewallRule -DisplayName 'Sync Music*' | Remove-NetFirewallRule")
            if (exe != null) {
                appendLine("New-NetFirewallRule -DisplayName 'Sync Music (app)' -Direction Inbound -Action Allow -Program \$exe -Profile Any | Out-Null")
            }
            appendLine("New-NetFirewallRule -DisplayName 'Sync Music (TCP)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $tcpPort -Profile Any | Out-Null")
            appendLine("New-NetFirewallRule -DisplayName 'Sync Music (UDP)' -Direction Inbound -Action Allow -Protocol UDP -LocalPort ${Protocol.DISCOVERY_PORT} -Profile Any | Out-Null")
        }
        val outer = "Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden " +
            "-ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand ${encode(inner)}'"
        val ok = runPowerShell(outer, timeoutSec = 120)
        AppLog.i("Firewall rules requested: $ok")
        return ok
    }
}
