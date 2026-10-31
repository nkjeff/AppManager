package com.nkjeff.appmanager

import android.os.RemoteException
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

@Keep
class ShellUserService : IAppManagerService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    @Throws(RemoteException::class)
    override fun runCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
