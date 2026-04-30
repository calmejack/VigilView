package com.vigilview.webrtc

import android.content.Context
import com.vigilview.signaling.SignalingClient
import com.vigilview.state.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class WebRTCClient(
    private val context: Context,
    val eglBase: EglBase,
    private val signalingClient: SignalingClient,
    private val connectionStateFlow: MutableStateFlow<ConnectionState>
) {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun buildPeerConnection(): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return peerConnectionFactory.createPeerConnection(config, object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val json = JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }
                signalingClient.sendCandidate(json)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> connectionStateFlow.value = ConnectionState.Connected
                    PeerConnection.PeerConnectionState.DISCONNECTED -> connectionStateFlow.value = ConnectionState.Disconnected
                    PeerConnection.PeerConnectionState.FAILED -> connectionStateFlow.value = ConnectionState.Failed("Peer connection failed")
                    PeerConnection.PeerConnectionState.CONNECTING -> connectionStateFlow.value = ConnectionState.Connecting
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> {
                            remoteVideoTrack = track
                            remoteRenderer?.let { track.addSink(it) }
                        }
                        is AudioTrack -> {}
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.FAILED -> connectionStateFlow.value = ConnectionState.Failed("ICE connection failed")
                    PeerConnection.IceConnectionState.DISCONNECTED -> connectionStateFlow.value = ConnectionState.Disconnected
                    PeerConnection.IceConnectionState.CONNECTED -> {}
                    else -> {}
                }
            }
        })
    }

    fun setupLocalAudio() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    fun initializePeerConnection() {
        peerConnection = buildPeerConnection()
        val streamIds = listOf("ARDAMS")
        localAudioTrack?.let {
            peerConnection?.addTrack(it, streamIds)
        }
    }

    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        val json = JSONObject().apply {
                            put("type", sdp.type.canonicalForm())
                            put("sdp", sdp.description)
                        }
                        signalingClient.sendOffer(json)
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {
                        connectionStateFlow.value = ConnectionState.Failed("SetLocalDescription failed: $error")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                connectionStateFlow.value = ConnectionState.Failed("CreateOffer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun handleAnswer(sdpJson: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpJson.getString("type")),
            sdpJson.getString("sdp")
        )
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                connectionStateFlow.value = ConnectionState.Failed("SetRemoteDescription failed: $error")
            }
        }, sdp)
    }

    fun handleRemoteIceCandidate(candidateJson: JSONObject) {
        val candidate = IceCandidate(
            candidateJson.getString("sdpMid"),
            candidateJson.getInt("sdpMLineIndex"),
            candidateJson.getString("candidate")
        )
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMicrophoneMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun close() {
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory.dispose()
    }
}
