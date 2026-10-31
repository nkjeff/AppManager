package com.nkjeff.appmanager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import rikka.shizuku.Shizuku

object ShizukuShell {
    @Volatile private var remote: IAppManagerService? = null
    @Volatile private var isBinding = false
    private var readyCallback: (() -> Unit)? = null
    private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.let { IAppManagerService.Stub.asInterface(it) }
            isBinding = false
            readyCallback?.invoke()
            readyCallback = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            isBinding = false
        }
    }

    @Synchronized
    fun bind(context: Context, onReady: (() -> Unit)? = null) {
        if (remote != null || isBinding) return
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val serviceArgs = args ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        ).daemon(false).processNameSuffix("shell").debuggable(false).version(1).also { args = it }
        isBinding = true
        readyCallback = onReady
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: Exception) {
            isBinding = false
            readyCallback = null
        }
    }

    fun runCommand(command: String): String {
        return try {
            remote?.runCommand(command) ?: ""
        } catch (e: RemoteException) {
            ""
        }
    }
}
