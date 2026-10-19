"use client";

import React, { useState, useEffect } from "react";
import { Shield, Radio, Check, Plus, User, Layers, Trash2, Edit3, X } from "lucide-react";

export interface UserProfile {
  id: string;
  category: "Amatir" | "RAPI" | "CB" | "Komunitas" | "Monitor";
  callsign: string;
  displayName: string;
  dmrId: number;
  allowedTalkgroups: number[];
  badgeColor: string;
  networkTag: string;
  isMonitorOnly?: boolean;
  defaultTg?: number;
}

const ALL_STANDARD_TGS = [510, 5101, 5102, 5103, 5109, 5108, 5100, 1025, 510990, 9990, 91];

export const DEFAULT_MONITOR_PROFILE: UserProfile = {
  id: "prof_monitor",
  category: "Monitor",
  callsign: "SWL-MONITOR",
  displayName: "Mode Monitor (Listen Only)",
  dmrId: 0,
  allowedTalkgroups: ALL_STANDARD_TGS,
  badgeColor: "bg-slate-700 text-cyan-300",
  networkTag: "Hanya Monitor (TX Terkunci)",
  isMonitorOnly: true,
  defaultTg: 510,
};

const DEFAULT_PROFILES: UserProfile[] = [DEFAULT_MONITOR_PROFILE];

interface ProfileSwitcherProps {
  activeProfile: UserProfile;
  onProfileChange: (profile: UserProfile) => void;
  onOpenPinModal?: () => void;
}

export const ProfileSwitcher: React.FC<ProfileSwitcherProps> = ({
  activeProfile,
  onProfileChange,
  onOpenPinModal,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [showAddModal, setShowAddModal] = useState(false);
  const [showPinModal, setShowPinModal] = useState(false);
  const [pinInput, setPinInput] = useState("");
  const [pinError, setPinError] = useState("");
  const [isVerifyingPin, setIsVerifyingPin] = useState(false);
  const [profiles, setProfiles] = useState<UserProfile[]>(DEFAULT_PROFILES);

  // New Profile Form States
  const [newCategory, setNewCategory] = useState<"Amatir" | "RAPI" | "CB" | "Komunitas">("Amatir");
  const [newCallsign, setNewCallsign] = useState("");
  const [newName, setNewName] = useState("");
  const [newDmrId, setNewDmrId] = useState<number>(5104001);
  const [newTargetTg, setNewTargetTg] = useState<number>(510);
  const [isCustomTg, setIsCustomTg] = useState(false);
  const [customTgInput, setCustomTgInput] = useState("");
  const [cbStatus, setCbStatus] = useState<{
    checked: boolean;
    found: boolean;
    name?: string;
    org?: string;
    dmrId?: number;
    allowedTgs?: string;
  } | null>(null);
  const [isCheckingCb, setIsCheckingCb] = useState(false);

  const checkCallbookMatch = async (query: string) => {
    const q = query.trim().toUpperCase();
    if (!q || q === "YB" || q === "JZ" || q === "91-DX-" || q === "RESCUE-") return;
    setIsCheckingCb(true);
    try {
      const res = await fetch(`/api/callbook?q=${encodeURIComponent(q)}`);
      if (res.ok) {
        const data = await res.json();
        const match = Array.isArray(data)
          ? data.find((e: any) => e.callsign?.toUpperCase() === q || String(e.dmr_id) === q)
          : null;
        if (match) {
          setCbStatus({
            checked: true,
            found: true,
            name: match.name,
            org: match.organization,
            dmrId: match.dmr_id,
            allowedTgs: match.allowed_tgs_override,
          });
          if (match.name) setNewName(match.name);
          if (match.dmr_id) setNewDmrId(match.dmr_id);
          if (match.organization) {
            const orgUp = match.organization.toUpperCase();
            if (orgUp.includes("RAPI")) setNewCategory("RAPI");
            else if (orgUp.includes("CB") || orgUp.includes("11M")) setNewCategory("CB");
            else if (orgUp.includes("KOMUNITAS") || orgUp.includes("SAR")) setNewCategory("Komunitas");
            else setNewCategory("Amatir");
          }
        } else {
          setCbStatus({ checked: true, found: false });
        }
      } else {
        setCbStatus({ checked: true, found: false });
      }
    } catch {
      setCbStatus({ checked: true, found: false });
    } finally {
      setIsCheckingCb(false);
    }
  };

  // Load from local storage, purging legacy test profiles and unlocking all TGs
  useEffect(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("ninjaradio_user_profiles") || localStorage.getItem("ninjadmr_user_profiles");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed)) {
            // Purge hardcoded legacy profiles (N1NJA, BD62AA, BD62BOT) and duplicate monitors
            const cleaned = parsed.filter(
              (p: UserProfile) =>
                p.callsign !== "N1NJA" &&
                p.id !== "prof_default" &&
                p.callsign !== "BD62AA" &&
                p.id !== "prof_bd62aa" &&
                p.callsign !== "BD62BOT" &&
                p.id !== "prof_bd62bot" &&
                p.id !== "prof_monitor"
            ).map((p: UserProfile) => ({
              ...p,
              allowedTalkgroups: Array.from(new Set([...ALL_STANDARD_TGS, ...(p.allowedTalkgroups || [])]))
            }));
            const fullList = [DEFAULT_MONITOR_PROFILE, ...cleaned];
            setProfiles(fullList);
            localStorage.setItem("ninjaradio_user_profiles", JSON.stringify(fullList));
            if (activeProfile.callsign === "N1NJA" || activeProfile.callsign === "BD62AA" || activeProfile.callsign === "BD62BOT") {
              onProfileChange(DEFAULT_MONITOR_PROFILE);
            }
            return;
          }
        } catch {}
      }
      setProfiles([DEFAULT_MONITOR_PROFILE]);
      if (activeProfile.callsign === "N1NJA" || activeProfile.callsign === "BD62AA" || activeProfile.callsign === "BD62BOT") {
        onProfileChange(DEFAULT_MONITOR_PROFILE);
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
    let allowedTalkgroups = [...ALL_STANDARD_TGS];

    if (newCategory === "RAPI") {
      badgeColor = "bg-blue-500 text-white";
      networkTag = "RAPI KRAP Gateway";
    } else if (newCategory === "CB") {
      badgeColor = "bg-amber-500 text-slate-950";
      networkTag = "11 Meter Citizens Band";
    } else if (newCategory === "Komunitas") {
      badgeColor = "bg-purple-500 text-white";
      networkTag = "Posko Relawan & SAR";
    }

    const isVerified = cbStatus?.found === true;
    const finalAllowedTgs = cbStatus?.allowedTgs && cbStatus.allowedTgs !== "*"
      ? cbStatus.allowedTgs.split(",").map((x: string) => Number(x.trim())).filter((n: number) => !isNaN(n))
      : Array.from(new Set([...allowedTalkgroups, chosenTg, 9990]));

    const created: UserProfile = {
      id: `prof_${Date.now()}`,
      category: newCategory,
      callsign: newCallsign.toUpperCase().trim(),
      displayName: newName.trim(),
      dmrId: Number(newDmrId) || (5100000 + Math.floor(Math.random() * 9000)),
      allowedTalkgroups: finalAllowedTgs,
      badgeColor,
      networkTag: isVerified ? `Callbook: ${cbStatus?.org || newCategory}` : networkTag,
      defaultTg: chosenTg,
      isMonitorOnly: !isVerified,
    };

    const updated = [...profiles.filter(p => p.id !== created.id), created];
    saveProfilesToStorage(updated);
    onProfileChange(created);
    setShowAddModal(false);
    setIsOpen(false);
    setNewCallsign("");
    setNewName("");
    setCbStatus(null);
    setIsCustomTg(false);
    setCustomTgInput("");
  };

  const handleDeleteProfile = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    if (id === "prof_monitor") return;
    const updated = profiles.filter((p) => p.id !== id);
    const finalProfiles = updated.length > 0 ? updated : [DEFAULT_MONITOR_PROFILE];
    saveProfilesToStorage(finalProfiles);
    if (activeProfile.id === id) {
      const fallback = finalProfiles[0] || DEFAULT_MONITOR_PROFILE;
      if (typeof window !== "undefined") {
        localStorage.setItem("ninjaradio_active_profile", JSON.stringify(fallback));
      }
      onProfileChange(fallback);
    }
  };

  const handleUnlockPin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pinInput.trim()) return;
    setIsVerifyingPin(true);
    setPinError("");
    try {
      const res = await fetch("/api/auth/verify-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin: pinInput.trim() }),
      });
      const data = await res.json();
      if (data && data.valid === true) {
        const testOp: UserProfile = {
          id: "prof_test_op",
          category: "Amatir",
          callsign: "TEST-OP",
          displayName: "Operator Test (PIN Verified)",
          dmrId: 5109999,
          allowedTalkgroups: [510, 5101, 5102, 5103, 5109, 5108, 5100, 9990, 91],
          badgeColor: "bg-amber-400 text-slate-950 font-black",
          networkTag: "Verified Test Operator",
          isMonitorOnly: false,
        };
        const updated = [...profiles.filter(p => p.id !== "prof_test_op"), testOp];
        saveProfilesToStorage(updated);
        onProfileChange(testOp);
        setShowPinModal(false);
        setIsOpen(false);
        setPinInput("");
      } else {
        setPinError("PIN Salah! Akses ditolak.");
      }
    } catch {
      setPinError("Gagal menghubungi server untuk verifikasi PIN.");
    } finally {
      setIsVerifyingPin(false);
    }
  };

  const handleSwitchToMonitor = () => {
    onProfileChange(DEFAULT_MONITOR_PROFILE);
    setIsOpen(false);
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
          <div className="absolute left-0 top-full mt-2 z-50 w-80 max-w-[calc(100vw-1.5rem)] bg-ninja-card border-2 border-slate-700 rounded-2xl p-3 shadow-2xl space-y-3">
            
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
                <span>+ Buat Profil</span>
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
                        {p.isMonitorOnly && (
                          <span className="text-[9px] px-1 bg-slate-800 text-slate-400 rounded">RX Only</span>
                        )}
                      </div>
                      <div className="text-[11px] text-gray-300 font-medium mt-0.5">{p.displayName}</div>
                      <div className="text-[9px] text-gray-500 font-mono mt-0.5">
                        Jaringan: <span className="text-gray-400">{p.networkTag}</span>
                      </div>
                    </div>

                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      {isCurrent && (
                        <span className="flex items-center gap-1 text-[10px] font-bold text-ninja-cyan bg-ninja-cyan/10 px-2 py-0.5 rounded-lg border border-ninja-cyan/30">
                          <Check className="w-3 h-3" />
                          <span>Aktif</span>
                        </span>
                      )}
                      {p.id !== "prof_monitor" && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            if (typeof window !== "undefined" && window.confirm(`Hapus profil ${p.callsign} (${p.displayName})?`)) {
                              handleDeleteProfile(e, p.id);
                            }
                          }}
                          className="p-1.5 rounded-lg text-slate-400 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                          title={`Hapus profil ${p.callsign}`}
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Anti-Abuse & PIN Actions */}
            <div className="pt-2 border-t border-slate-800 space-y-2">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setShowPinModal(true)}
                  className="flex-1 py-1.5 px-2 rounded-xl bg-amber-500/15 border border-amber-500/40 hover:bg-amber-500/25 text-amber-300 text-[11px] font-bold flex items-center justify-center gap-1.5 transition-colors"
                >
                  <span>🔑 Buka Kunci PIN Operator</span>
                </button>
                {activeProfile.id !== "prof_monitor" && (
                  <button
                    type="button"
                    onClick={handleSwitchToMonitor}
                    className="py-1.5 px-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-[11px] font-bold transition-colors"
                    title="Kunci PTT kembali ke mode dengar"
                  >
                    🔒 Kunci PTT
                  </button>
                )}
              </div>
              <div className="flex items-center gap-1.5 text-[10px] text-emerald-400 font-medium">
                <Shield className="w-3.5 h-3.5 flex-shrink-0" />
                <span>Anti-Abuse: PTT terkunci kecuali pakai Callsign pribadi atau PIN.</span>
              </div>
            </div>

          </div>
        </>
      )}

      {/* MODAL: INPUT PIN OPERATOR */}
      {showPinModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-ninja-card border-2 border-amber-500/60 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 rounded-lg bg-amber-500/20 border border-amber-500 flex items-center justify-center text-amber-400">
                  🔑
                </div>
                <div>
                  <h3 className="font-bold text-sm text-white">PIN Operator / Sysop</h3>
                  <p className="text-[10px] text-slate-400">Buka akses pengujian PTT transmisi</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setShowPinModal(false); setPinError(""); }}
                className="p-1 rounded-lg text-gray-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleUnlockPin} className="space-y-3">
              <div>
                <label className="block text-slate-300 text-xs mb-1 font-semibold">
                  Masukkan PIN Keamanan Operator:
                </label>
                <input
                  type="password"
                  required
                  autoFocus
                  maxLength={12}
                  placeholder="PIN 4 Digit (Default: 1234)"
                  value={pinInput}
                  onChange={(e) => { setPinInput(e.target.value); setPinError(""); }}
                  className="w-full px-3 py-2.5 bg-ninja-dark border border-slate-700 focus:border-amber-400 rounded-xl text-center text-lg tracking-widest font-mono text-white font-bold"
                />
                {pinError && (
                  <p className="text-red-400 text-[11px] font-bold mt-1 text-center">{pinError}</p>
                )}
              </div>

              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setShowPinModal(false)}
                  className="flex-1 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl text-xs font-bold"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isVerifyingPin}
                  className="flex-1 py-2 bg-amber-500 hover:bg-amber-400 text-slate-950 rounded-xl text-xs font-black shadow-lg shadow-amber-500/20"
                >
                  {isVerifyingPin ? "Memverifikasi..." : "Buka Kunci PTT"}
                </button>
              </div>
            </form>
          </div>
        </div>
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
                    setIsCustomTg(false);
                    if (cat === "Amatir") {
                      setNewCallsign("YB");
                      setNewTargetTg(510);
                    } else if (cat === "RAPI") {
                      setNewCallsign("JZ");
                      setNewTargetTg(5109);
                    } else if (cat === "CB") {
                      setNewCallsign("91-DX-");
                      setNewTargetTg(5108);
                    } else {
                      setNewCallsign("RESCUE-");
                      setNewTargetTg(5100);
                    }
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
                <div className="flex items-center justify-between mb-1">
                  <label className="text-gray-400 font-semibold">
                    2. Callsign / Identitas Panggil ({newCategory})
                  </label>
                  <button
                    type="button"
                    onClick={() => checkCallbookMatch(newCallsign)}
                    disabled={isCheckingCb || !newCallsign}
                    className="text-[10px] text-ninja-cyan hover:underline font-mono flex items-center gap-1 bg-cyan-950/40 px-2 py-0.5 rounded border border-cyan-800/50"
                  >
                    {isCheckingCb ? "Mencari..." : "🔍 Cocokkan Callbook"}
                  </button>
                </div>
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
                  onChange={(e) => {
                    setNewCallsign(e.target.value.toUpperCase());
                    setCbStatus(null);
                  }}
                  onBlur={() => {
                    if (newCallsign && !cbStatus) checkCallbookMatch(newCallsign);
                  }}
                  className="w-full px-3 py-2.5 bg-ninja-dark border border-slate-700 rounded-xl text-white font-mono uppercase font-bold focus:border-ninja-cyan"
                />

                {/* Callbook Match Telemetry Banner */}
                {cbStatus?.checked && (
                  <div className={`mt-1.5 p-2 rounded-xl text-[11px] font-mono flex items-start gap-1.5 ${
                    cbStatus.found
                      ? "bg-emerald-950/50 border border-emerald-500/50 text-emerald-300"
                      : "bg-amber-950/50 border border-amber-500/50 text-amber-300"
                  }`}>
                    {cbStatus.found ? (
                      <div>
                        <div className="font-bold flex items-center gap-1 text-emerald-400">
                          <Check className="w-3.5 h-3.5" />
                          <span>Terverifikasi di Callbook Resmi</span>
                        </div>
                        <div className="text-[10px] text-slate-300 mt-0.5">
                          Nama: <span className="text-white font-semibold">{cbStatus.name}</span> | DMR ID: <span className="text-white font-semibold">{cbStatus.dmrId}</span>
                          {cbStatus.allowedTgs && <span> | Izin TG: [{cbStatus.allowedTgs}]</span>}
                        </div>
                      </div>
                    ) : (
                      <div>
                        <div className="font-bold text-amber-400">⚠ Belum Terdaftar di Callbook</div>
                        <div className="text-[10px] text-slate-300 mt-0.5">
                          Stasiun ini belum ada di Callbook server. PTT akan diblokir kecuali stasiun didaftarkan oleh Sysop di menu Callbook.
                        </div>
                      </div>
                    )}
                  </div>
                )}
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

              {/* Step 5: Target TalkGroup Terkunci */}
              <div>
                <label className="block text-gray-400 mb-1 font-semibold">
                  5. Target TalkGroup (TG Pilihan / Terkunci)
                </label>
                <select
                  value={isCustomTg ? "custom" : String(newTargetTg)}
                  onChange={(e) => {
                    if (e.target.value === "custom") {
                      setIsCustomTg(true);
                    } else {
                      setIsCustomTg(false);
                      setNewTargetTg(Number(e.target.value));
                    }
                  }}
                  className="w-full px-3 py-2 bg-ninja-dark border border-slate-700 rounded-xl text-white font-bold focus:border-ninja-cyan"
                >
                  <option value="510">TG 510 - DMR Indonesia Nasional</option>
                  <option value="5101">TG 5101 - Jabar Regional (YSF)</option>
                  <option value="5102">TG 5102 - Jateng Regional</option>
                  <option value="5103">TG 5103 - Jatim Regional</option>
                  <option value="5109">TG 5109 - RAPI Nusantara Gateway</option>
                  <option value="5108">TG 5108 - 11M CB Digital Gateway</option>
                  <option value="5100">TG 5100 - Emergency SAR 911</option>
                  <option value="9990">TG 9990 - Audio Echo Parrot Test</option>
                  <option value="91">TG 91 - BrandMeister WorldWide</option>
                  <option value="custom">✏️ Masukkan TG Manual / Lainnya...</option>
                </select>
                {isCustomTg && (
                  <input
                    type="number"
                    required
                    placeholder="Masukkan Nomor TG Tujuan (misal: 5104)"
                    value={customTgInput}
                    onChange={(e) => setCustomTgInput(e.target.value)}
                    className="mt-1.5 w-full px-3 py-2 bg-ninja-dark border border-slate-700 rounded-xl text-white font-mono focus:border-ninja-cyan"
                  />
                )}
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
