package com.agentdroid.integration

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

data class PrivilegedExecutionResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean, val durationMs: Long)
data class ShizukuStatus(val binderAvailable: Boolean, val permissionGranted: Boolean, val serverVersion: Int? = null, val serverUid: Int? = null, val preV11: Boolean = false)

@Keep
class ShizukuCommandUserService : Binder {
    constructor()
    @Keep constructor(context: Context)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code == TRANSACTION_DESTROY || code == TRANSACTION_DESTROY_AIDL) {
            reply?.writeNoException(); reply?.writeInt(1); Thread { exitProcess(0) }.start(); return true
        }
        if (code != TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val size = data.readInt().coerceIn(0, 128)
        val argv = List(size) { data.readString().orEmpty() }
        val cwd = data.readString()
        val timeoutMs = data.readLong().coerceIn(100, 120_000)
        val result = executeCommand(argv, cwd, timeoutMs)
        reply?.writeNoException(); reply?.writeString(result)
        return true
    }

    private fun executeCommand(argv: List<String>, cwd: String?, timeoutMs: Long): String {
        require(argv.isNotEmpty() && argv.none { it.indexOf('\u0000') >= 0 }) { "Invalid argv" }
        val started = System.nanoTime()
        val process = ProcessBuilder(argv).apply { if (!cwd.isNullOrBlank()) directory(File(cwd)) }.start()
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val stdout = executor.submit<String> { process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) } }
            val stderr = executor.submit<String> { process.errorStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) } }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            buildJsonObject {
                put("exitCode", if (finished) process.exitValue() else -1)
                put("stdout", runCatching { stdout.get(2, TimeUnit.SECONDS) }.getOrDefault(""))
                put("stderr", runCatching { stderr.get(2, TimeUnit.SECONDS) }.getOrDefault(""))
                put("timedOut", !finished)
                put("durationMs", (System.nanoTime() - started) / 1_000_000)
            }.toString()
        } finally { if (process.isAlive) process.destroyForcibly(); executor.shutdownNow() }
    }

    companion object {
        const val DESCRIPTOR = "com.agentdroid.integration.ShizukuCommandService"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DESTROY = 16777115
        const val TRANSACTION_DESTROY_AIDL = 16777114
        const val MAX_OUTPUT = 1_000_000
    }
}

class ShizukuCapability(private val context: Context) {
    private val appContext = context.applicationContext

    fun status(): ShizukuStatus = runCatching {
        val binder = Shizuku.pingBinder()
        if (!binder) return@runCatching ShizukuStatus(false, false)
        val pre = Shizuku.isPreV11()
        ShizukuStatus(true, !pre && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED, Shizuku.getVersion(), Shizuku.getUid(), pre)
    }.getOrDefault(ShizukuStatus(false, false))

    suspend fun requestPermission(): Boolean {
        val current = status(); if (!current.binderAvailable || current.preV11) return false
        if (current.permissionGranted) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)) return false
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE && !result.isCompleted) result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try { Shizuku.requestPermission(REQUEST_CODE); withTimeout(30_000) { result.await() } }
        finally { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    suspend fun execute(argv: List<String>, cwd: File? = null, timeoutMs: Long = 20_000): PrivilegedExecutionResult {
        require(status().permissionGranted) { "Shizuku permission is not granted" }
        require(argv.isNotEmpty() && argv.size <= 128 && argv.none { '\u0000' in it }) { "Invalid Shizuku argv" }
        val bound = bind()
        return try {
            withContext(Dispatchers.IO) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(ShizukuCommandUserService.DESCRIPTOR); data.writeInt(argv.size); argv.forEach(data::writeString)
                    data.writeString(cwd?.canonicalPath); data.writeLong(timeoutMs.coerceIn(100, 120_000))
                    check(bound.binder.transact(ShizukuCommandUserService.TRANSACTION_EXECUTE, data, reply, 0)) { "Shizuku UserService transaction failed" }
                    reply.readException(); parseResult(reply.readString().orEmpty())
                } finally { data.recycle(); reply.recycle() }
            }
        } finally { runCatching { Shizuku.unbindUserService(bound.args, bound.connection, true) } }
    }

    private suspend fun bind(): BoundService {
        val args = Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ShizukuCommandUserService::class.java.name))
            .daemon(false).processNameSuffix("agentdroid_priv").tag("agentdroid-privileged-v1").version(1)
        val deferred = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { if (service != null && !deferred.isCompleted) deferred.complete(service) }
            override fun onServiceDisconnected(name: ComponentName?) { if (!deferred.isCompleted) deferred.completeExceptionally(IllegalStateException("Shizuku UserService disconnected")) }
        }
        Shizuku.bindUserService(args, connection)
        return BoundService(withTimeout(8_000) { deferred.await() }, connection, args)
    }

    private data class BoundService(val binder: IBinder, val connection: ServiceConnection, val args: Shizuku.UserServiceArgs)

    companion object {
        private const val REQUEST_CODE = 7319

        fun parseResult(raw: String): PrivilegedExecutionResult {
            val json = Json.parseToJsonElement(raw).jsonObject
            return PrivilegedExecutionResult(
                exitCode = json.getValue("exitCode").jsonPrimitive.int,
                stdout = json["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stderr = json["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                timedOut = json.getValue("timedOut").jsonPrimitive.boolean,
                durationMs = json.getValue("durationMs").jsonPrimitive.long
            )
        }
    }
}

class RootCapability {
    private val candidates = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")
    fun executable(): File? = candidates.map(::File).firstOrNull { it.isFile && it.canExecute() }
    fun available(): Boolean = executable() != null

    suspend fun execute(argv: List<String>, timeoutMs: Long = 20_000): PrivilegedExecutionResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty() && argv.size <= 128 && argv.none { '\u0000' in it }) { "Invalid root argv" }
        val su = executable() ?: error("Root executable not detected")
        val command = argv.joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
        val started = System.nanoTime(); val process = ProcessBuilder(su.absolutePath, "-c", "exec $command").start()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val stdout = executor.submit<String> { process.inputStream.bufferedReader().use { it.readText().take(1_000_000) } }
            val stderr = executor.submit<String> { process.errorStream.bufferedReader().use { it.readText().take(1_000_000) } }
            val finished = process.waitFor(timeoutMs.coerceIn(100, 120_000), TimeUnit.MILLISECONDS); if (!finished) process.destroyForcibly()
            PrivilegedExecutionResult(if (finished) process.exitValue() else -1, runCatching { stdout.get(2, TimeUnit.SECONDS) }.getOrDefault(""), runCatching { stderr.get(2, TimeUnit.SECONDS) }.getOrDefault(""), !finished, (System.nanoTime() - started) / 1_000_000)
        } finally { if (process.isAlive) process.destroyForcibly(); executor.shutdownNow() }
    }
}
