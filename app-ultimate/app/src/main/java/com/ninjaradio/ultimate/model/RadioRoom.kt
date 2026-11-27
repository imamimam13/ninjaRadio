package com.ninjaradio.ultimate.model

data class RadioRoom(
    val id: String,
    val name: String,
    val targetTg: Long = 0L,
    val totalClients: Int = 0,
    val isPasswordProtected: Boolean = false,
    val description: String = ""
)
