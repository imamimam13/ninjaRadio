package com.ninjaradio.svx.data

import java.util.UUID

data class TalkgroupConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val tg: Long,
)

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 5300,
    val callsign: String,
    val password: String = "",
    val defaultTg: Long = 1025L,
    val talkgroups: List<TalkgroupConfig> = listOf(
        TalkgroupConfig(label = "bridi", tg = 1025L),
        TalkgroupConfig(label = "ninja", tg = 510990L),
    ),
    val followQsy: Boolean = true,
)
