package com.vigilview.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SignalingClient(
    private val serverIp: String,
    private val port: Int = 9000,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onOfferReceived: (JSONObject) -> Unit,
    private val onAnswerReceived: (JSONObject) -> Unit,
    private val onCandidateReceived: (JSONObject) -> Unit,
    private val onError: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val retryCount = AtomicInteger(0)
    private val maxRetries = 5
    private val isClosed = AtomicBoolean(false)

    fun connect() {
        if (isClosed.get()) return
        scope.launch {
            connectInternal()
        }
    }

    private suspend fun connectInternal() {
        val url = "ws://$serverIp:$port"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                retryCount.set(0)
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                onDisconnected()
                attemptReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                onError(t.message ?: "WebSocket failure")
                attemptReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                "offer" -> onOfferReceived(json.getJSONObject("data"))
                "answer" -> onAnswerReceived(json.getJSONObject("data"))
                "candidate" -> onCandidateReceived(json.getJSONObject("data"))
                else -> onError("Unknown message type: $type")
            }
        } catch (e: Exception) {
            onError("Failed to parse message: ${e.message}")
        }
    }

    private fun attemptReconnect() {
        if (isClosed.get()) return
        val current = retryCount.incrementAndGet()
        if (current > maxRetries) {
            onError("Max reconnection attempts reached")
            return
        }
        scope.launch {
            val backoffMs = minOf(1000L * (1L shl (current - 1)), 30000L)
            delay(backoffMs)
            if (!isClosed.get()) {
                connectInternal()
            }
        }
    }

    fun sendOffer(sdpJson: JSONObject) {
        sendMessage("offer", sdpJson)
    }

    fun sendAnswer(sdpJson: JSONObject) {
        sendMessage("answer", sdpJson)
    }

    fun sendCandidate(candidateJson: JSONObject) {
        sendMessage("candidate", candidateJson)
    }

    private fun sendMessage(type: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", type)
            put("data", data)
        }
        webSocket?.send(message.toString())
    }

    fun close() {
        isClosed.set(true)
        webSocket?.close(1000, "Client closing")
        scope.cancel()
        client.dispatcher.cancelAll()
    }
}
