package com.ninjadmr.lite.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class ProfileStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ninjadmr_lite_profiles", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_SELECTED_ID = "selected_profile_id"
    }

    @Synchronized
    fun getAllProfiles(): MutableList<DmrProfile> {
        val list = mutableListOf<DmrProfile>()
        val raw = prefs.getString(KEY_PROFILES, null) ?: return list
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(DmrProfile.fromJson(obj))
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun saveProfiles(profiles: List<DmrProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            array.put(p.toJson())
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    @Synchronized
    fun getProfile(id: String): DmrProfile? {
        return getAllProfiles().find { it.id == id }
    }

    @Synchronized
    fun saveOrUpdateProfile(profile: DmrProfile) {
        val list = getAllProfiles()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
        } else {
            list.add(profile)
        }
        saveProfiles(list)
        if (getSelectedProfileId() == null || list.size == 1) {
            setSelectedProfileId(profile.id)
        }
    }

    @Synchronized
    fun deleteProfile(id: String) {
        val list = getAllProfiles()
        list.removeAll { it.id == id }
        saveProfiles(list)
        if (getSelectedProfileId() == id) {
            val nextId = list.firstOrNull()?.id
            setSelectedProfileId(nextId)
        }
    }

    @Synchronized
    fun getSelectedProfileId(): String? {
        return prefs.getString(KEY_SELECTED_ID, null)
    }

    @Synchronized
    fun setSelectedProfileId(id: String?) {
        if (id == null) {
            prefs.edit().remove(KEY_SELECTED_ID).apply()
        } else {
            prefs.edit().putString(KEY_SELECTED_ID, id).apply()
        }
    }

    @Synchronized
    fun getActiveProfile(): DmrProfile? {
        val id = getSelectedProfileId()
        val list = getAllProfiles()
        if (id != null) {
            val found = list.find { it.id == id }
            if (found != null) return found
        }
        return list.firstOrNull()
    }

    /**
     * Returns (1-based channel number, total channel count).
     */
    @Synchronized
    fun getActiveChannelIndex(): Pair<Int, Int> {
        val list = getAllProfiles()
        if (list.isEmpty()) return Pair(0, 0)
        val id = getSelectedProfileId()
        val idx = if (id != null) list.indexOfFirst { it.id == id } else 0
        val validIdx = if (idx >= 0) idx else 0
        return Pair(validIdx + 1, list.size)
    }

    /**
     * Cycles to the next (+1) or previous (-1) profile/channel with wrap-around.
     * Returns (newProfile, 1-based channel number).
     */
    @Synchronized
    fun cycleProfile(delta: Int): Pair<DmrProfile?, Int> {
        val list = getAllProfiles()
        if (list.isEmpty()) return Pair(null, 0)
        val curId = getSelectedProfileId()
        val curIdx = list.indexOfFirst { it.id == curId }.let { if (it >= 0) it else 0 }
        val nextIdx = ((curIdx + delta) % list.size + list.size) % list.size
        val nextProfile = list[nextIdx]
        setSelectedProfileId(nextProfile.id)
        return Pair(nextProfile, nextIdx + 1)
    }
}
