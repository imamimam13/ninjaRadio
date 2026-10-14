package com.ninjadmr.app.config

/**
 * A saved radio connection profile. Credentials are stored separately (encrypted)
 * by [com.ninjadmr.app.security.CredentialsStore]; this model carries the
 * non-secret settings plus a reference key for the password.
 */
data class RadioProfile(
    val name: String = "default",
    val protocol: String = "homebrew",
    val server: String = "brandmeister.network",
    val port: String = "62031",
    val dmrId: String = "",
    val callsign: String = "",
    val essid: String = "",
    val talkgroup: String = "9990",
    val slot: String = "2",
    val colorCode: String = "1",
) {
    /** Effective id used on-air: DMR ID + optional two-digit ESSID suffix. */
    fun effectiveDmrId(): String {
        if (dmrId.isBlank()) return ""
        val suffix = essid.filter { it.isDigit() }.take(2)
        return if (suffix.isBlank()) dmrId else "$dmrId$suffix"
    }
}
