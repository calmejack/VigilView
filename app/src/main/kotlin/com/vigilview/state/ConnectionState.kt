package com.vigilview.state

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
    object Reconnecting : ConnectionState()
    object Disconnected : ConnectionState()
}
