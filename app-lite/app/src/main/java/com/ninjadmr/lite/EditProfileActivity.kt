package com.ninjadmr.lite

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ninjadmr.lite.model.DmrProfile
import com.ninjadmr.lite.model.ProfileStore

class EditProfileActivity : AppCompatActivity() {

    private lateinit var profileStore: ProfileStore
    private var editingProfileId: String? = null

    private lateinit var tvEditTitle: TextView
    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etPassword: EditText
    private lateinit var etCallsign: EditText
    private lateinit var etDmrId: EditText
    private lateinit var etEssid: EditText
    private lateinit var etTalkgroup: EditText
    private lateinit var etSlot: EditText
    private lateinit var etRxGroup: EditText
    private lateinit var etLocation: EditText
    private lateinit var etSoftware: EditText
    private lateinit var etHardware: EditText
    private lateinit var etDescription: EditText
    private lateinit var btnModeRepeater: Button
    private lateinit var btnModeRadio: Button
    private lateinit var btnModeIp: Button
    private lateinit var etRxFreq: EditText
    private lateinit var etTxFreq: EditText
    private lateinit var etColorCode: EditText
    private lateinit var btnPresetVhf: Button
    private lateinit var btnPresetUhf: Button
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    private var currentSlotMode = "4" // "4" = Radio/Hotspot Simplex, "3" = Repeater Duplex, "2" = IP Network

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_edit_profile)

        profileStore = ProfileStore(this)
        editingProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        tvEditTitle = findViewById(R.id.tvEditTitle)
        etName = findViewById(R.id.etName)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etPassword = findViewById(R.id.etPassword)
        etCallsign = findViewById(R.id.etCallsign)
        etDmrId = findViewById(R.id.etDmrId)
        etEssid = findViewById(R.id.etEssid)
        etTalkgroup = findViewById(R.id.etTalkgroup)
        etSlot = findViewById(R.id.etSlot)
        etRxGroup = findViewById(R.id.etRxGroup)
        etLocation = findViewById(R.id.etLocation)
        etSoftware = findViewById(R.id.etSoftware)
        etHardware = findViewById(R.id.etHardware)
        etDescription = findViewById(R.id.etDescription)
        btnModeRepeater = findViewById(R.id.btnModeRepeater)
        btnModeRadio = findViewById(R.id.btnModeRadio)
        btnModeIp = findViewById(R.id.btnModeIp)
        etRxFreq = findViewById(R.id.etRxFreq)
        etTxFreq = findViewById(R.id.etTxFreq)
        etColorCode = findViewById(R.id.etColorCode)
        btnPresetVhf = findViewById(R.id.btnPresetVhf)
        btnPresetUhf = findViewById(R.id.btnPresetUhf)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

        btnModeRepeater.setOnClickListener { updateSlotModeUi("3") }
        btnModeRadio.setOnClickListener { updateSlotModeUi("4") }
        btnModeIp.setOnClickListener { updateSlotModeUi("2") }

        btnPresetVhf.setOnClickListener {
            etRxFreq.setText("145.5000")
            etTxFreq.setText("145.5000")
            Toast.makeText(this, "Frekuensi VHF diset: 145.5000 MHz", Toast.LENGTH_SHORT).show()
        }

        btnPresetUhf.setOnClickListener {
            etRxFreq.setText("438.8000")
            etTxFreq.setText("438.8000")
            Toast.makeText(this, "Frekuensi UHF diset: 438.8000 MHz", Toast.LENGTH_SHORT).show()
        }

        if (editingProfileId != null) {
            tvEditTitle.text = "EDIT PROFILE"
            loadProfile(editingProfileId!!)
        } else {
            tvEditTitle.text = "NEW PROFILE"
            etEssid.setText("1")
            etLocation.setText("Mobile, ID")
            etSoftware.setText("20260911_WPSD")
            etHardware.setText("MMDVM_MMDVM_HS_Hat")
            etDescription.setText("NinjaRadio")
            updateSlotModeUi("4")
            etRxFreq.setText("145.5000")
            etTxFreq.setText("145.5000")
            etColorCode.setText("1")
        }

        btnSave.setOnClickListener { saveProfile() }
        btnCancel.setOnClickListener { finish() }

        etName.requestFocus()
    }

    private fun updateSlotModeUi(mode: String) {
        currentSlotMode = mode
        when (mode) {
            "4" -> {
                btnModeRepeater.setTextColor(resources.getColor(R.color.ninja_text_dim))
                btnModeRadio.setTextColor(resources.getColor(R.color.ninja_cyan))
                btnModeIp.setTextColor(resources.getColor(R.color.ninja_text_dim))
            }
            "2" -> {
                btnModeRepeater.setTextColor(resources.getColor(R.color.ninja_text_dim))
                btnModeRadio.setTextColor(resources.getColor(R.color.ninja_text_dim))
                btnModeIp.setTextColor(resources.getColor(R.color.ninja_amber))
            }
            else -> { // "3" Repeater Duplex
                btnModeRepeater.setTextColor(resources.getColor(R.color.ninja_green))
                btnModeRadio.setTextColor(resources.getColor(R.color.ninja_text_dim))
                btnModeIp.setTextColor(resources.getColor(R.color.ninja_text_dim))
            }
        }
    }

    private fun loadProfile(id: String) {
        val p = profileStore.getProfile(id) ?: return
        etName.setText(p.name)
        etHost.setText(p.host)
        etPort.setText(p.port.toString())
        etPassword.setText(p.password)
        etCallsign.setText(p.callsign)
        etDmrId.setText(if (p.dmrId > 0) p.dmrId.toString() else "")
        etEssid.setText(p.essid.toString())
        etTalkgroup.setText(if (p.talkgroup > 0) p.talkgroup.toString() else "")
        etSlot.setText(p.slot.toString())
        etRxGroup.setText(p.rxGroup)
        etLocation.setText(p.location.ifBlank { "Mobile, ID" })
        val softVal = if (p.software.isBlank() || p.software == "NinjaRadio Lite") "20260911_WPSD" else p.software
        val hardVal = if (p.hardware.isBlank() || p.hardware == "Android POC") "MMDVM_MMDVM_HS_Hat" else p.hardware
        val descVal = if (p.description.isBlank() || p.description == "NinjaRadio Lite") "NinjaRadio" else p.description
        etSoftware.setText(softVal)
        etHardware.setText(hardVal)
        etDescription.setText(descVal)
        updateSlotModeUi(if (p.slotMode.isNotBlank()) p.slotMode else "4")
        etRxFreq.setText(if (p.rxFreq.isNotBlank()) p.rxFreq else "145.5000")
        etTxFreq.setText(if (p.txFreq.isNotBlank()) p.txFreq else "145.5000")
        etColorCode.setText(p.colorCode.coerceIn(1, 15).toString())
    }

    private fun saveProfile() {
        val name = etName.text.toString().trim()
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 62031
        val password = etPassword.text.toString().trim()
        val callsign = etCallsign.text.toString().trim().uppercase()
        val dmrId = etDmrId.text.toString().trim().toIntOrNull() ?: 0
        val essid = etEssid.text.toString().trim().toIntOrNull()?.coerceIn(0, 99) ?: 1
        val tg = etTalkgroup.text.toString().trim().toIntOrNull() ?: 0
        val slot = etSlot.text.toString().trim().toIntOrNull() ?: 2
        val rxGroup = etRxGroup.text.toString().trim()
        val location = etLocation.text.toString().trim().ifBlank { "Mobile, ID" }
        val software = etSoftware.text.toString().trim().ifBlank { "20260911_WPSD" }
        val hardware = etHardware.text.toString().trim().ifBlank { "MMDVM_MMDVM_HS_Hat" }
        val description = etDescription.text.toString().trim().ifBlank { "NinjaRadio" }
        val slotMode = currentSlotMode
        val rxFreq = etRxFreq.text.toString().trim().ifBlank { "145.5000" }
        val txFreq = etTxFreq.text.toString().trim().ifBlank { "145.5000" }
        val colorCode = etColorCode.text.toString().trim().toIntOrNull()?.coerceIn(1, 15) ?: 1

        if (name.isEmpty() || host.isEmpty() || dmrId == 0 || tg == 0) {
            Toast.makeText(this, "Nama, Host, DMR ID & TG wajib diisi", Toast.LENGTH_SHORT).show()
            return
        }

        val existing = editingProfileId?.let { profileStore.getProfile(it) }
        val profile = if (existing != null) {
            existing.apply {
                this.name = name
                this.host = host
                this.port = port
                this.password = password
                this.callsign = callsign
                this.dmrId = dmrId
                this.essid = essid
                this.talkgroup = tg
                this.slot = slot
                this.rxGroup = rxGroup
                this.location = location
                this.software = software
                this.hardware = hardware
                this.description = description
                this.slotMode = slotMode
                this.rxFreq = rxFreq
                this.txFreq = txFreq
                this.colorCode = colorCode
            }
        } else {
            DmrProfile(
                name = name,
                host = host,
                port = port,
                password = password,
                callsign = callsign,
                dmrId = dmrId,
                essid = essid,
                talkgroup = tg,
                slot = slot,
                rxGroup = rxGroup,
                location = location,
                software = software,
                hardware = hardware,
                description = description,
                slotMode = slotMode,
                rxFreq = rxFreq,
                txFreq = txFreq,
                colorCode = colorCode
            )
        }

        profileStore.saveOrUpdateProfile(profile)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
