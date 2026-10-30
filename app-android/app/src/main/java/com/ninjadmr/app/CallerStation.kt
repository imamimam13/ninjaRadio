package com.ninjadmr.app

/**
 * Caller ID and station telemetry for active RX and Last Heard history.
 */
data class CallerStation(
    val callsign: String = "",
    val name: String = "",
    val dmrId: String = "",
    val talkgroup: String = "",
    val slot: String = "2",
    val timestamp: Long = 0L,
    val isReceiving: Boolean = false,
)
