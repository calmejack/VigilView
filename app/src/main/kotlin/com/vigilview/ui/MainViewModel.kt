package com.vigilview.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigilview.signaling.SignalingClient
import com.vigilview.state.ConnectionState
import com.vigilview.webrtc.WebRTCClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val eglBase: EglBase = EglBase.create()

    private var signalingClient: SignalingClient? = null
    private var webRTCClient: WebRTCClient? = null
    private var isMuted = false

    fun connect(ip: String, renderer: SurfaceViewRenderer) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) return

        _connectionState.value = ConnectionState.Connecting

        val sigClient = SignalingClient(
            serverIp = ip,
            onConnected = {
                viewModelScope.launch {
                    webRTCClient?.createOffer()
                }
            },
            onDisconnected = {
                viewModelScope.launch {
                    _connectionState.value = ConnectionState.Disconnected
                }
            },
            onOfferReceived = { _ -> },
            onAnswerReceived = { sdpJson ->
                viewModelScope.launch {
                    webRTCClient?.handleAnswer(sdpJson)
                }
            },
            onCandidateReceived = { candidateJson ->
                viewModelScope.launch {
                    webRTCClient?.handleRemoteIceCandidate(candidateJson)
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    _connectionState.value = ConnectionState.Failed(error)
                }
            }
        )
        signalingClient = sigClient

        val rtcClient = WebRTCClient(
            context = application,
            eglBase = eglBase,
            signalingClient = sigClient,
            connectionStateFlow = _connectionState
        )
        rtcClient.setupLocalAudio()
        rtcClient.initializePeerConnection()
        rtcClient.setRemoteRenderer(renderer)
        webRTCClient = rtcClient

        sigClient.connect()
    }

    fun disconnect() {
        signalingClient?.close()
        signalingClient = null
        webRTCClient?.close()
        webRTCClient = null
        _connectionState.value = ConnectionState.Idle
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        webRTCClient?.setMicrophoneMuted(isMuted)
        return isMuted
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        eglBase.release()
    }
}
