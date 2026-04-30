package com.vigilview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.vigilview.R
import com.vigilview.databinding.ActivityMainBinding
import com.vigilview.state.ConnectionState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var rendererInitialized = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            initiateConnection()
        } else {
            Snackbar.make(binding.root, getString(R.string.permission_required), Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRenderer()
        setupClickListeners()
        observeConnectionState()
    }

    private fun setupRenderer() {
        if (!rendererInitialized) {
            binding.surfaceViewRenderer.init(viewModel.eglBase.eglBaseContext, null)
            binding.surfaceViewRenderer.setEnableHardwareScaler(true)
            binding.surfaceViewRenderer.setMirror(false)
            rendererInitialized = true
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            if (hasRequiredPermissions()) {
                initiateConnection()
            } else {
                requestPermissions()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnMute.setOnClickListener {
            val muted = viewModel.toggleMute()
            binding.btnMute.text = if (muted) getString(R.string.unmute) else getString(R.string.mute)
        }
    }

    private fun initiateConnection() {
        val ip = binding.etDeviceIp.text?.toString()?.trim()
        if (ip.isNullOrEmpty()) {
            Snackbar.make(binding.root, getString(R.string.enter_ip), Snackbar.LENGTH_SHORT).show()
            return
        }
        setupAudioForSpeaker()
        viewModel.connect(ip, binding.surfaceViewRenderer)
    }

    private fun setupAudioForSpeaker() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Idle -> {
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.visibility = View.GONE
                binding.btnMute.visibility = View.GONE
                binding.etDeviceIp.isEnabled = true
            }
            is ConnectionState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.visibility = View.VISIBLE
                binding.etDeviceIp.isEnabled = false
            }
            is ConnectionState.Connected -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.btnConnect.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
                binding.btnMute.visibility = View.VISIBLE
            }
            is ConnectionState.Failed -> {
                binding.tvStatus.text = getString(R.string.status_failed, state.reason)
                binding.btnConnect.isEnabled = true
                binding.btnConnect.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.GONE
                binding.btnMute.visibility = View.GONE
                binding.etDeviceIp.isEnabled = true
            }
            is ConnectionState.Reconnecting -> {
                binding.tvStatus.text = getString(R.string.status_reconnecting)
            }
            is ConnectionState.Disconnected -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.btnConnect.isEnabled = true
                binding.btnConnect.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.GONE
                binding.btnMute.visibility = View.GONE
                binding.etDeviceIp.isEnabled = true
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (rendererInitialized) {
            binding.surfaceViewRenderer.release()
            rendererInitialized = false
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }
}
