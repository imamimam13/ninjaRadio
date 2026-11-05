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
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

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
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

        if (editingProfileId != null) {
            tvEditTitle.text = "EDIT PROFILE"
            loadProfile(editingProfileId!!)
        } else {
            tvEditTitle.text = "NEW PROFILE"
            etEssid.setText("1")
            etLocation.setText("Mobile, ID")
        }

        btnSave.setOnClickListener { saveProfile() }
        btnCancel.setOnClickListener { finish() }

        etName.requestFocus()
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
        etSoftware.setText(p.software.ifBlank { "NinjaRadio Lite" })
        etHardware.setText(p.hardware.ifBlank { "Android POC" })
        etDescription.setText(p.description.ifBlank { "NinjaRadio Lite" })
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
        val software = etSoftware.text.toString().trim().ifBlank { "NinjaRadio Lite" }
        val hardware = etHardware.text.toString().trim().ifBlank { "Android POC" }
        val description = etDescription.text.toString().trim().ifBlank { "NinjaRadio Lite" }

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
                description = description
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
