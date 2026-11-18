package com.ninjaradio.svx

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninjaradio.svx.data.AppSettings
import com.ninjaradio.svx.data.AppSettingsData
import com.ninjaradio.svx.data.ServerConfig
import com.ninjaradio.svx.data.ServerManager
import com.ninjaradio.svx.data.TalkgroupConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

data class MainUiState(
    val activeTab: Int = 0,
    val connectionState: SvxReflectorClient.State = SvxReflectorClient.State.DISCONNECTED,
    val servers: List<ServerConfig> = emptyList(),
    val activeServer: ServerConfig = ServerManager.DEFAULT_SERVERS.first(),
    val nodes: List<String> = emptyList(),
    val activeTalker: String? = null,
    val activeTalkerTg: Long? = null,
    val currentTg: Long = 1025L,
    val isTransmitting: Boolean = false,
    val txElapsedSeconds: Int = 0,
    val totRemainingSeconds: Int = 180,
    val rxLevelPct: Int = 0,
    val rxDb: Double = -96.0,
    val txLevelPct: Int = 0,
    val txDb: Double = -96.0,
    val settings: AppSettingsData = AppSettingsData(),
    val showAddServerSheet: Boolean = false,
    val editingServer: ServerConfig? = null,
    val showAddTalkgroupSheet: Boolean = false,
    val showChangeTgDialog: Boolean = false,
    val showLogsDialog: Boolean = false,
    val isCarMode: Boolean = false,
    val logs: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application), SvxReflectorClient.Listener {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val serverManager = ServerManager(application)
    private val appSettings = AppSettings(application)
    private val client = SvxReflectorClient(this)
    private val audio = AudioEngine { log(it) }

    private var txTimerJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        val loadedServers = serverManager.getServers()
        val activeServer = serverManager.getActiveServer()
        val loadedSettings = appSettings.load()

        _uiState.update {
            it.copy(
                servers = loadedServers,
                activeServer = activeServer,
                currentTg = activeServer.defaultTg,
                settings = loadedSettings,
                totRemainingSeconds = loadedSettings.maxTxTimeSeconds,
            )
        }

        audio.rxVolume = loadedSettings.rxVolume
        audio.micVolume = loadedSettings.micVolume
        audio.onRxLevelListener = { pct, db ->
            _uiState.update { it.copy(rxLevelPct = pct, rxDb = db) }
        }
        audio.onTxLevelListener = { pct, db ->
            _uiState.update { it.copy(txLevelPct = pct, txDb = db) }
        }

        audio.setAudioOutput(application, loadedSettings.audioOutput == "Loudspeaker")

        if (loadedSettings.autoConnect) {
            connectToServer(activeServer)
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(activeTab = tabIndex) }
    }

    fun toggleCarMode() {
        _uiState.update { it.copy(isCarMode = !it.isCarMode) }
    }

    fun toggleConnection() {
        if (client.state == SvxReflectorClient.State.DISCONNECTED) {
            connectToServer(_uiState.value.activeServer)
        } else {
            stopTx()
            client.disconnect()
        }
    }

    fun connectToServer(server: ServerConfig) {
        serverManager.setActiveServerId(server.id)
        stopTx()
        if (client.state != SvxReflectorClient.State.DISCONNECTED) {
            client.disconnect()
        }
        _uiState.update {
            it.copy(
                activeServer = server,
                currentTg = server.defaultTg,
                totRemainingSeconds = it.settings.maxTxTimeSeconds,
            )
        }
        audio.start()
        client.connect(
            host = server.host,
            port = server.port,
            callsign = server.callsign,
            password = server.password,
            tg = server.defaultTg,
        )
    }

    fun selectTg(tg: Long) {
        val currentServer = _uiState.value.activeServer
        _uiState.update { it.copy(currentTg = tg, showChangeTgDialog = false) }
        client.selectTg(tg)

        val updatedServer = currentServer.copy(defaultTg = tg)
        serverManager.addOrUpdateServer(updatedServer)
        _uiState.update {
            it.copy(
                activeServer = updatedServer,
                servers = serverManager.getServers(),
            )
        }
    }

    fun onPttPressed() {
        if (_uiState.value.settings.pttMode == "Latch") {
            if (_uiState.value.isTransmitting) stopTx() else startTx()
        } else {
            startTx()
        }
    }

    fun onPttReleased() {
        if (_uiState.value.settings.pttMode == "Push-to-Talk") {
            stopTx()
        }
    }

    fun startTx() {
        val currentState = _uiState.value
        if (currentState.isTransmitting || (currentState.connectionState != SvxReflectorClient.State.CONNECTED)) return
        if (currentState.currentTg == 0L) {
            log("Pilih Talkgroup terlebih dahulu")
            return
        }

        // 1. Immediately update UI state
        _uiState.update {
            it.copy(
                isTransmitting = true,
                txElapsedSeconds = 0,
                totRemainingSeconds = currentState.settings.maxTxTimeSeconds,
            )
        }

        // 2. Instantly send UDP heartbeat to open NAT pinhole on router & reflector
        client.sendUdpHeartbeat()

        // 3. Start audio recording and Opus encoding
        audio.startTx { client.sendAudio(it) }

        // 4. Start TX timer
        txTimerJob?.cancel()
        txTimerJob = viewModelScope.launch {
            val maxSeconds = currentState.settings.maxTxTimeSeconds
            while (_uiState.value.isTransmitting) {
                delay(1.seconds)
                val elapsed = _uiState.value.txElapsedSeconds + 1
                val remaining = if (maxSeconds > 0) (maxSeconds - elapsed).coerceAtLeast(0) else 999

                _uiState.update {
                    it.copy(
                        txElapsedSeconds = elapsed,
                        totRemainingSeconds = remaining,
                    )
                }

                if (currentState.settings.timeoutWarning && maxSeconds > 0 && (remaining == currentState.settings.warningTimeSeconds)) {
                    vibrateDevice()
                }

                if (maxSeconds > 0 && remaining <= 0) {
                    log("TX Timeout reached ($maxSeconds s)")
                    vibrateDevice()
                    stopTx()
                    break
                }
            }
        }
    }

    fun stopTx() {
        if (!_uiState.value.isTransmitting) return

        // 1. Cancel TX timer
        txTimerJob?.cancel()
        txTimerJob = null

        // 2. Immediately update UI state (0ms UI latency)
        _uiState.update { it.copy(isTransmitting = false) }

        // 3. IMMEDIATELY send UDP FLUSH to reflector so server stops talker instantly on all nodes
        client.sendFlush()

        // 4. Stop audio recording asynchronously
        audio.stopTx()
    }

    fun openAddServerSheet(serverToEdit: ServerConfig? = null) {
        val target = serverToEdit ?: ServerConfig(
            name = "",
            host = "",
            callsign = "",
            defaultTg = 1025L,
            talkgroups = emptyList(),
        )
        _uiState.update { it.copy(showAddServerSheet = true, editingServer = target) }
    }

    fun closeAddServerSheet() {
        _uiState.update { it.copy(showAddServerSheet = false, editingServer = null) }
    }

    fun openAddTalkgroupSheet() {
        _uiState.update { it.copy(showAddTalkgroupSheet = true) }
    }

    fun closeAddTalkgroupSheet() {
        _uiState.update { it.copy(showAddTalkgroupSheet = false) }
    }

    fun addTalkgroupToEditingServer(tgConfig: TalkgroupConfig) {
        val editing = _uiState.value.editingServer ?: _uiState.value.activeServer
        val updatedTgs = editing.talkgroups + tgConfig
        val defaultTgVal = if (editing.defaultTg == 0L || editing.talkgroups.isEmpty()) tgConfig.tg else editing.defaultTg
        val updatedServer = editing.copy(
            talkgroups = updatedTgs,
            defaultTg = defaultTgVal,
        )
        _uiState.update {
            it.copy(
                editingServer = updatedServer,
                showAddTalkgroupSheet = false,
            )
        }
    }

    fun saveServer(server: ServerConfig) {
        val serverToSave = if (server.defaultTg == 0L && server.talkgroups.isNotEmpty()) {
            server.copy(defaultTg = server.talkgroups.first().tg)
        } else server

        serverManager.addOrUpdateServer(serverToSave)
        val servers = serverManager.getServers()

        var activeServer = _uiState.value.activeServer
        if (activeServer.id == serverToSave.id) {
            activeServer = serverToSave
        }

        _uiState.update {
            it.copy(
                servers = servers,
                activeServer = activeServer,
                currentTg = if (activeServer.id == serverToSave.id) serverToSave.defaultTg else it.currentTg,
                showAddServerSheet = false,
                editingServer = null,
            )
        }
    }

    fun deleteServer(serverId: String) {
        serverManager.deleteServer(serverId)
        val servers = serverManager.getServers()
        val activeServer = serverManager.getActiveServer()
        _uiState.update { it.copy(servers = servers, activeServer = activeServer) }
    }

    fun openChangeTgDialog() {
        _uiState.update { it.copy(showChangeTgDialog = true) }
    }

    fun closeChangeTgDialog() {
        _uiState.update { it.copy(showChangeTgDialog = false) }
    }

    fun openLogsDialog() {
        _uiState.update { it.copy(showLogsDialog = true) }
    }

    fun closeLogsDialog() {
        _uiState.update { it.copy(showLogsDialog = false) }
    }

    fun updateSettings(newSettings: AppSettingsData) {
        appSettings.save(newSettings)
        audio.rxVolume = newSettings.rxVolume
        audio.micVolume = newSettings.micVolume
        audio.setAudioOutput(getApplication(), newSettings.audioOutput == "Loudspeaker")
        _uiState.update { it.copy(settings = newSettings) }
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice() {
        try {
            val ctx = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(300)
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------- Listener callbacks

    override fun onLog(msg: String) = log(msg)

    override fun onState(state: SvxReflectorClient.State) {
        _uiState.update { it.copy(connectionState = state) }
        if (state == SvxReflectorClient.State.DISCONNECTED) {
            stopTx()
            _uiState.update { it.copy(activeTalker = null, activeTalkerTg = null) }
        }
    }

    override fun onNodes(nodes: List<String>) {
        _uiState.update { it.copy(nodes = nodes) }
    }

    override fun onTalkerStart(tg: Long, callsign: String) {
        log("▶ $callsign @ TG #$tg")
        _uiState.update { it.copy(activeTalker = callsign, activeTalkerTg = tg) }
    }

    override fun onTalkerStop(tg: Long, callsign: String) {
        log("■ $callsign @ TG #$tg")
        if (_uiState.value.activeTalker == callsign) {
            _uiState.update { it.copy(activeTalker = null, activeTalkerTg = null) }
        }
    }

    override fun onAudio(opus: ByteArray) = audio.onOpusPacket(opus)

    override fun onFlush() = audio.flush()

    private fun log(msg: String) {
        val entry = "${timeFmt.format(Date())} $msg"
        _uiState.update {
            val currentLogs = it.logs
            val updated = currentLogs + entry
            it.copy(logs = if (updated.size > 200) updated.takeLast(100) else updated)
        }
    }

    override fun onCleared() {
        stopTx()
        client.disconnect()
        audio.stop()
        super.onCleared()
    }
}
