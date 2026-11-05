package com.ninjadmr.lite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ninjadmr.lite.model.DmrProfile
import com.ninjadmr.lite.model.ProfileStore

class ProfilesActivity : AppCompatActivity() {

    private lateinit var profileStore: ProfileStore
    private lateinit var lvProfiles: ListView
    private lateinit var tvProfileCount: TextView
    private lateinit var btnAddProfile: Button
    private lateinit var btnEditProfile: Button
    private lateinit var btnDeleteProfile: Button
    private lateinit var btnBack: Button

    private var profilesList = mutableListOf<DmrProfile>()
    private lateinit var adapter: ProfileAdapter
    private var selectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_profiles)

        profileStore = ProfileStore(this)

        lvProfiles = findViewById(R.id.lvProfiles)
        tvProfileCount = findViewById(R.id.tvProfileCount)
        btnAddProfile = findViewById(R.id.btnAddProfile)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)
        btnBack = findViewById(R.id.btnBack)

        adapter = ProfileAdapter()
        lvProfiles.adapter = adapter

        // Clicking item opens quick action dialog (Pilih, Edit, Hapus)
        lvProfiles.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            adapter.notifyDataSetChanged()
            val p = profilesList.getOrNull(position) ?: return@setOnItemClickListener
            showProfileOptionsDialog(p, position + 1)
        }

        // Long clicking directly opens edit
        lvProfiles.setOnItemLongClickListener { _, _, position, _ ->
            selectedIndex = position
            adapter.notifyDataSetChanged()
            val p = profilesList.getOrNull(position) ?: return@setOnItemLongClickListener false
            openEditProfile(p.id)
            true
        }

        lvProfiles.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedIndex = position
                adapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        btnAddProfile.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            startActivityForResult(intent, REQ_ADD_PROFILE)
        }

        btnEditProfile.setOnClickListener {
            val p = getCurrentlySelectedProfile()
            if (p != null) {
                openEditProfile(p.id)
            } else {
                Toast.makeText(this, "Pilih channel untuk diedit", Toast.LENGTH_SHORT).show()
            }
        }

        btnDeleteProfile.setOnClickListener {
            val p = getCurrentlySelectedProfile()
            if (p != null) {
                deleteProfileConfirm(p)
            } else {
                Toast.makeText(this, "Pilih channel untuk dihapus", Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener {
            finish()
        }

        refreshList()
        lvProfiles.requestFocus()
    }

    private fun showProfileOptionsDialog(p: DmrProfile, chNum: Int) {
        val options = arrayOf(
            "▶ Aktifkan & Hubungkan Channel Ini",
            "✎ Edit Channel",
            "🗑 Hapus Channel"
        )
        AlertDialog.Builder(this)
            .setTitle(String.format("CH %02d · %s", chNum, p.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        profileStore.setSelectedProfileId(p.id)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    1 -> openEditProfile(p.id)
                    2 -> deleteProfileConfirm(p)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openEditProfile(profileId: String) {
        val intent = Intent(this, EditProfileActivity::class.java).apply {
            putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profileId)
        }
        startActivityForResult(intent, REQ_EDIT_PROFILE)
    }

    private fun deleteProfileConfirm(p: DmrProfile) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Channel?")
            .setMessage("Hapus channel '${p.name}'?")
            .setPositiveButton("Hapus") { _, _ ->
                profileStore.deleteProfile(p.id)
                refreshList()
                Toast.makeText(this, "Channel dihapus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun getCurrentlySelectedProfile(): DmrProfile? {
        if (selectedIndex in profilesList.indices) {
            return profilesList[selectedIndex]
        }
        val activeId = profileStore.getSelectedProfileId()
        return profilesList.find { it.id == activeId } ?: profilesList.firstOrNull()
    }

    private fun refreshList() {
        profilesList.clear()
        profilesList.addAll(profileStore.getAllProfiles())
        tvProfileCount.text = "${profilesList.size} CH"

        val activeId = profileStore.getSelectedProfileId()
        val activeIdx = profilesList.indexOfFirst { it.id == activeId }
        if (selectedIndex !in profilesList.indices) {
            selectedIndex = if (activeIdx >= 0) activeIdx else 0
        }
        adapter.notifyDataSetChanged()
        if (selectedIndex in profilesList.indices) {
            lvProfiles.setSelection(selectedIndex)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            refreshList()
            setResult(Activity.RESULT_OK)
        }
    }

    private inner class ProfileAdapter : BaseAdapter() {
        override fun getCount(): Int = profilesList.size
        override fun getItem(position: Int): Any = profilesList[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@ProfilesActivity)
                .inflate(R.layout.item_profile, parent, false)

            val p = profilesList[position]
            val tvActiveMark = view.findViewById<TextView>(R.id.tvActiveMark)
            val tvItemName = view.findViewById<TextView>(R.id.tvItemName)
            val tvItemDetails = view.findViewById<TextView>(R.id.tvItemDetails)
            val btnItemEdit = view.findViewById<Button>(R.id.btnItemEdit)

            val isActive = (p.id == profileStore.getSelectedProfileId())
            val isHighlighted = (position == selectedIndex)

            tvActiveMark.visibility = View.VISIBLE
            tvActiveMark.text = String.format("CH%02d", position + 1)
            if (isActive) {
                tvActiveMark.setTextColor(Color.parseColor("#34D399"))
                tvActiveMark.setBackgroundResource(R.drawable.badge_status_online)
            } else {
                tvActiveMark.setTextColor(Color.parseColor("#38BDF8"))
                tvActiveMark.setBackgroundResource(R.drawable.badge_pill)
            }

            val call = p.callsign.ifBlank { "NOCALL" }
            tvItemName.text = if (isActive) "${p.name} ● ON" else p.name
            tvItemName.setTextColor(if (isActive) Color.parseColor("#34D399") else Color.parseColor("#F8FAFC"))
            tvItemDetails.text = "TG ${p.talkgroup} · TS ${p.slot} · $call (${p.dmrId})"

            btnItemEdit.setOnClickListener {
                selectedIndex = position
                notifyDataSetChanged()
                openEditProfile(p.id)
            }

            if (isActive) {
                view.setBackgroundResource(R.drawable.item_channel_active)
            } else if (isHighlighted) {
                view.setBackgroundResource(R.drawable.item_focus)
            } else {
                view.setBackgroundResource(R.drawable.item_channel_normal)
            }

            return view
        }
    }

    companion object {
        private const val REQ_ADD_PROFILE = 101
        private const val REQ_EDIT_PROFILE = 102
    }
}
