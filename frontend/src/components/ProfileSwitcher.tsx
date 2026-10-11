"use client";

import React, { useState, useEffect } from "react";
import { Shield, Radio, Check, Plus, User, Layers, Trash2, Edit3, X } from "lucide-react";

export interface UserProfile {
  id: string;
  category: "Amatir" | "RAPI" | "CB" | "Komunitas";
  callsign: string;
  displayName: string;
  dmrId: number;
  allowedTalkgroups: number[];
  badgeColor: string;
  networkTag: string;
}

const DEFAULT_PROFILES: UserProfile[] = [
  {
    id: "prof_default",
    category: "Amatir",
    callsign: "N1NJA",
    displayName: "Radio Operator",
    dmrId: 5109999,
    allowedTalkgroups: [510, 5101, 5102, 5103, 5109, 5108, 5100, 9990],
    badgeColor: "bg-cyan-500 text-slate-950",
    networkTag: "NinjaRadio Master"
  }
];

interface ProfileSwitcherProps {
  activeProfile: UserProfile;
  onProfileChange: (profile: UserProfile) => void;
}

export const ProfileSwitcher: React.FC<ProfileSwitcherProps> = ({
  activeProfile,
  onProfileChange,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [showAddModal, setShowAddModal] = useState(false);
  const [profiles, setProfiles] = useState<UserProfile[]>(DEFAULT_PROFILES);

  // New Profile Form States
  const [newCategory, setNewCategory] = useState<"Amatir" | "RAPI" | "CB" | "Komunitas">("Amatir");
  const [newCallsign, setNewCallsign] = useState("");
  const [newName, setNewName] = useState("");
  const [newDmrId, setNewDmrId] = useState<number>(5104001);

  // Load from local storage
  useEffect(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("ninjaradio_user_profiles") || localStorage.getItem("ninjadmr_user_profiles");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed) && parsed.length > 0) {
            setProfiles(parsed);
          }
        } catch {}
      }
    }
  }, []);

  const saveProfilesToStorage = (updated: UserProfile[]) => {
    setProfiles(updated);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_user_profiles", JSON.stringify(updated));
    }
  };

  const handleSelect = (p: UserProfile) => {
    onProfileChange(p);
    setIsOpen(false);
  };

  const handleCreateProfile = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCallsign || !newName) return;

    let badgeColor = "bg-emerald-500 text-slate-950";
    let networkTag = "ORARI / BrandMeister";
    let allowedTalkgroups = [510, 5101, 5102, 5103, 9990];

    if (newCategory === "RAPI") {
      badgeColor = "bg-blue-500 text-white";
      networkTag = "RAPI KRAP Gateway";
      allowedTalkgroups = [5109, 9990];
    } else if (newCategory === "CB") {
      badgeColor = "bg-amber-500 text-slate-950";
      networkTag = "11 Meter Citizens Band";
      allowedTalkgroups = [5108, 9990];
    } else if (newCategory === "Komunitas") {
      badgeColor = "bg-purple-500 text-white";
      networkTag = "Posko Relawan & SAR";
      allowedTalkgroups = [5100, 9990];
    }

    const created: UserProfile = {
      id: `prof_${Date.now()}`,
      category: newCategory,
      callsign: newCallsign.toUpperCase().trim(),
      displayName: newName.trim(),
      dmrId: Number(newDmrId) || (5100000 + Math.floor(Math.random() * 9000)),
      allowedTalkgroups,
      badgeColor,
      networkTag,
    };

    const updated = [...profiles, created];
    saveProfilesToStorage(updated);
    onProfileChange(created);
    setShowAddModal(false);
    setIsOpen(false);
    setNewCallsign("");
    setNewName("");
  };

  const handleDeleteProfile = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    if (profiles.length <= 1) return;
    const updated = profiles.filter((p) => p.id !== id);
    saveProfilesToStorage(updated);
    if (activeProfile.id === id) {
      onProfileChange(updated[0]);
    }
  };

  return (
    <div className="relative">
      {/* Trigger Button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2 px-3 py-1.5 bg-slate-900 border border-slate-700 hover:border-ninja-cyan rounded-xl text-xs font-bold transition-all shadow-md active:scale-95"
      >
        <span className={`px-1.5 py-0.5 rounded text-[10px] font-black uppercase ${activeProfile.badgeColor}`}>
          {activeProfile.category}
        </span>
        <span className="text-white font-mono">{activeProfile.callsign}</span>
        <span className="text-gray-400 text-[10px]">▼</span>
      </button>

      {/* Dropdown Menu */}
      {isOpen && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
          <div className="absolute right-0 top-full mt-2 z-50 w-72 sm:w-84 bg-ninja-card border-2 border-slate-700 rounded-2xl p-3 shadow-2xl space-y-3">
            
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-1.5">
                <User className="w-4 h-4 text-ninja-cyan" />
                <span className="font-bold text-xs text-white">Profil Pengguna Anda</span>
              </div>
              <button
                onClick={() => setShowAddModal(true)}
                className="flex items-center gap-1 px-2 py-1 rounded-lg bg-ninja-cyan text-ninja-dark font-black text-[10px] hover:bg-cyan-300 transition-colors shadow-sm"
              >
                <Plus className="w-3 h-3" />
                <span>+ Buat Profil Baru</span>
              </button>
            </div>

            {/* List of User's Saved Profiles */}
            <div className="space-y-1.5 max-h-56 overflow-y-auto pr-1">
              {profiles.map((p) => {
                const isCurrent = p.id === activeProfile.id;
                return (
                  <div
                    key={p.id}
                    onClick={() => handleSelect(p)}
                    className={`w-full text-left p-2.5 rounded-xl border transition-all flex items-center justify-between cursor-pointer ${
                      isCurrent
                        ? "bg-slate-800/80 border-ninja-cyan shadow-sm ring-1 ring-ninja-cyan/40"
                        : "bg-ninja-dark/60 border-slate-800 hover:border-slate-700"
                    }`}
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <span className={`px-1.5 py-0.2 rounded text-[9px] font-black uppercase ${p.badgeColor}`}>
                          {p.category}
                        </span>
                        <span className="font-bold font-mono text-white text-xs">{p.callsign}</span>
                      </div>
                      <div className="text-[11px] text-gray-300 font-medium mt-0.5">{p.displayName}</div>
                      <div className="text-[9px] text-gray-500 font-mono mt-0.5">
                        Jaringan: <span className="text-gray-400">{p.networkTag}</span>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      {isCurrent ? (
                        <Check className="w-4 h-4 text-ninja-cyan flex-shrink-0" />
                      ) : (
                        profiles.length > 1 && (
                          <button
                            onClick={(e) => handleDeleteProfile(e, p.id)}
                            className="p-1 text-gray-500 hover:text-red-400 transition-colors"
                            title="Hapus Profil"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        )
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="pt-2 border-t border-slate-800 flex items-center gap-1.5 text-[10px] text-emerald-400 font-medium">
              <Shield className="w-3.5 h-3.5 flex-shrink-0" />
              <span>Dinding Isolasi Aktif: Profil CB/RAPI tidak akan bocor ke BrandMeister.</span>
            </div>

          </div>
        </>
      )}

      {/* MODAL: INPUT / BUAT PROFIL BARU */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-ninja-card border-2 border-ninja-cyan/60 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-2">
                <Plus className="w-5 h-5 text-ninja-cyan" />
                <h3 className="font-bold text-sm text-white">Buat Profil Identitas Baru</h3>
              </div>
              <button
                onClick={() => setShowAddModal(false)}
                className="p-1 rounded-lg text-gray-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleCreateProfile} className="space-y-3 text-xs">
              
              {/* Step 1: Kategori */}
              <div>
                <label className="block text-gray-400 mb-1 font-semibold">1. Pilih Kategori / Organisasi</label>
                <select
                  value={newCategory}
                  onChange={(e) => {
                    const cat = e.target.value as any;
                    setNewCategory(cat);
                    if (cat === "Amatir") setNewCallsign("YB");
                    else if (cat === "RAPI") setNewCallsign("JZ");
                    else if (cat === "CB") setNewCallsign("91-DX-");
                    else setNewCallsign("RESCUE-");
                  }}
                  className="w-full px-3 py-2.5 bg-ninja-dark border border-slate-700 rounded-xl text-white font-bold focus:border-ninja-cyan"
                >
                  <option value="Amatir">🟢 Radio Amatir (ORARI / IARU)</option>
                  <option value="RAPI">🔵 RAPI (10-28 / KRAP)</option>
                  <option value="CB">🟡 CB 11-Meter Citizens Band</option>
                  <option value="Komunitas">🔴 Relawan SAR / Komunitas</option>
                </select>
              </div>

              {/* Step 2: Callsign */}
              <div>
                <label className="block text-gray-400 mb-1 font-semibold">
                  2. Callsign / Identitas Panggil ({newCategory})
                </label>
                <input
                  type="text"
                  required
                  placeholder={
                    newCategory === "Amatir"
                      ? "Contoh: YB1ABC / YC2BBS"
                      : newCategory === "RAPI"
                      ? "Contoh: JZ10XYZ"
                      : newCategory === "CB"
                      ? "Contoh: 91-DX-001"
                      : "Contoh: RESCUE-01"
                  }
                  value={newCallsign}
                  onChange={(e) => setNewCallsign(e.target.value.toUpperCase())}
                  className="w-full px-3 py-2.5 bg-ninja-dark border border-slate-700 rounded-xl text-white font-mono uppercase font-bold focus:border-ninja-cyan"
                />
              </div>

              {/* Step 3: Nama Lengkap */}
              <div>
                <label className="block text-gray-400 mb-1 font-semibold">3. Nama Lengkap Operator</label>
                <input
                  type="text"
                  required
                  placeholder="Nama Anda"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full px-3 py-2.5 bg-ninja-dark border border-slate-700 rounded-xl text-white focus:border-ninja-cyan"
                />
              </div>

              {/* Step 4: DMR ID (Opsional jika bukan amatir) */}
              <div>
                <label className="block text-gray-400 mb-1 font-semibold">
                  4. DMR-ID / Kode Radio {newCategory === "Amatir" ? "(7 Digit RadioID)" : "(Opsional)"}
                </label>
                <input
                  type="number"
                  value={newDmrId}
                  onChange={(e) => setNewDmrId(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-ninja-dark border border-slate-700 rounded-xl text-white font-mono focus:border-ninja-cyan"
                />
              </div>

              <div className="pt-2 flex gap-2">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="flex-1 py-2.5 bg-slate-800 text-gray-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2.5 bg-ninja-cyan text-ninja-dark rounded-xl font-black hover:bg-cyan-300 shadow-lg shadow-cyan-500/20"
                >
                  Simpan & Pakai Profil
                </button>
              </div>

            </form>

          </div>
        </div>
      )}

    </div>
  );
};
