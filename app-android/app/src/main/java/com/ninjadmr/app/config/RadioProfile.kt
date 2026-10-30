package com.ninjadmr.app.config

/**
 * A saved radio connection profile.
 */
data class RadioProfile(
    val name: String = "default",
    val channelName: String = "LOCAL TACTICAL",
    val protocol: String = "homebrew",
    val server: String = "43.173.13.117",
    val port: String = "62301",
    val dmrId: String = "1022057",
    val callsign: String = "BD62BOT",
    val essid: String = "",
    val talkgroup: String = "1025",
    val slot: String = "2",
    val colorCode: String = "1",
    val zone: String = "NUSANTARA LINK A",
    val frequency: String = "438.50000",
    val quickTgs: String = "9990,1,515,1025",
) {
    /** Effective id used on-air: DMR ID + optional two-digit ESSID suffix. */
    fun effectiveDmrId(): String {
        if (dmrId.isBlank()) return ""
        val suffix = essid.filter { it.isDigit() }.take(2)
        return if (suffix.isBlank()) dmrId else "$dmrId$suffix"
    }
}
