package com.agentdroid.core.ai.transport

import com.agentdroid.core.ai.ErrorMapper
import com.agentdroid.core.model.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    suspend fun execute(request: Request): Response {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    job?.invokeOnCompletion { response.close() }
                    continuation.resume(response)
                }
            })
        }
    }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): Request {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (name, value) -> if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value) }
        if (body != null) builder.method(method, body.toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
}

fun Throwable.toAppError(): AppError {
    if (this is CancellationException) throw this
    return when (this) {
        is java.net.SocketTimeoutException -> AppError.Timeout(message ?: "timeout")
        is javax.net.ssl.SSLException -> AppError.Ssl(message ?: "ssl")
        is IOException -> AppError.Network(message ?: "network")
        else -> AppError.Unknown(message ?: javaClass.simpleName)
    }
}

fun Response.requireSuccess(): Response = takeIf { isSuccessful } ?: run {
    val bodyText = body?.string().orEmpty()
    close()
    throw ProviderHttpException(ErrorMapper.http(code, bodyText))
}

class ProviderHttpException(val error: AppError) : IOException(error.technicalMessage)
