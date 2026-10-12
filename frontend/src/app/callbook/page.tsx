"use client";

import React, { useState, useEffect, useRef } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { Users, Search, Plus, MapPin, Building, ShieldCheck, Radio, Upload, Download, Check, AlertCircle, Lock } from "lucide-react";
import { beepPlayer } from "@/components/BeepPlayer";

interface CallbookItem {
  id: number;
  dmr_id: number;
  callsign: string;
  name: string;
  city: string;
  province_state: string;
  country: string;
  organization: string;
  organization_id: string;
  remarks: string;
}

interface OrgItem {
  id?: number;
  code: string;
  name: string;
  description?: string;
}

export default function CallbookPage() {
  const { isConnected } = useLiveMonitor();
  const [entries, setEntries] = useState<CallbookItem[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const [orgFilter, setOrgFilter] = useState("ALL");
  const [showAddModal, setShowAddModal] = useState(false);
  const [importStatus, setImportStatus] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Organizations registry state
  const [organizations, setOrganizations] = useState<OrgItem[]>([
    { code: "ORARI", name: "ORARI" },
    { code: "RAPI", name: "RAPI" },
    { code: "CB-11M", name: "CB 11M" },
    { code: "KOMUNITAS", name: "Komunitas / SAR" },
  ]);
  const [showOrgPinModal, setShowOrgPinModal] = useState(false);
  const [adminPinInput, setAdminPinInput] = useState("");
  const [newOrgName, setNewOrgName] = useState("");
  const [newOrgCode, setNewOrgCode] = useState("");
  const [newOrgDesc, setNewOrgDesc] = useState("");
  const [orgPinError, setOrgPinError] = useState<string | null>(null);
  const [isSubmittingOrg, setIsSubmittingOrg] = useState(false);

  // New Entry
  const [newDmrId, setNewDmrId] = useState(5104001);
  const [newCallsign, setNewCallsign] = useState("");
  const [newName, setNewName] = useState("");
  const [newCity, setNewCity] = useState("");
  const [newOrg, setNewOrg] = useState("ORARI");
  const [newOrgId, setNewOrgId] = useState("");

  const fetchOrganizations = () => {
    fetch("/api/organizations")
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setOrganizations(data);
        }
      })
      .catch(() => {});
  };

  const fetchCallbook = () => {
    let url = `/api/callbook?`;
    if (orgFilter !== "ALL") url += `org=${orgFilter}&`;
    if (searchTerm) url += `q=${encodeURIComponent(searchTerm)}&`;

    fetch(url)
      .then((res) => res.json())
      .then((data) => setEntries(data))
      .catch(() => {});
  };

  useEffect(() => {
    fetchOrganizations();
  }, []);

  useEffect(() => {
    fetchCallbook();
  }, [searchTerm, orgFilter]);

  const handleCreateOrgWithPin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adminPinInput || !newOrgName) return;
    setIsSubmittingOrg(true);
    setOrgPinError(null);

    try {
      const res = await fetch("/api/organizations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          pin: adminPinInput,
          name: newOrgName,
          code: newOrgCode || newOrgName.toUpperCase().replace(/\s+/g, "_"),
          description: newOrgDesc,
        }),
      });

      const data = await res.json();
      if (res.ok) {
        beepPlayer?.playTalkGrant();
        setShowOrgPinModal(false);
        const createdOrg = data.organization;
        const newCode = createdOrg.code;
        setNewOrg(newCode);
        fetchOrganizations();
        setImportStatus(`✓ Organisasi "${createdOrg.name}" berhasil ditambahkan oleh Admin!`);
        setTimeout(() => setImportStatus(null), 4000);
      } else {
        beepPlayer?.playRogerBeep();
        setOrgPinError(data.detail || "Gagal menambahkan organisasi. Cek PIN Admin.");
      }
    } catch {
      setOrgPinError("Terjadi kesalahan jaringan.");
    } finally {
      setIsSubmittingOrg(false);
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const formData = new FormData();
    formData.append("file", file);

    setImportStatus("Mengimpor data callbook...");
    try {
      const res = await fetch("/api/callbook/import", {
        method: "POST",
        body: formData,
      });
      const data = await res.json();
      if (res.ok) {
        setImportStatus(`✓ Berhasil mengimpor ${data.imported_count || data.count || "semua"} data pengguna!`);
        beepPlayer?.playTalkGrant();
        fetchCallbook();
      } else {
        setImportStatus(`❌ Gagal impor: ${data.detail || "Format tidak valid"}`);
      }
    } catch {
      setImportStatus("❌ Terjadi kesalahan jaringan saat mengunggah.");
    }

    setTimeout(() => setImportStatus(null), 4000);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const handleAddCallbook = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCallsign || !newDmrId) return;
    try {
      const res = await fetch("/api/callbook", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dmr_id: Number(newDmrId),
          callsign: newCallsign.toUpperCase(),
          name: newName,
          city: newCity,
          province_state: "Indonesia",
          country: "Indonesia",
          organization: newOrg,
          organization_id: newOrgId || newCallsign,
          remarks: "Registered via NinjaRadio Core",
        }),
      });
      if (res.ok) {
        setShowAddModal(false);
        setNewCallsign("");
        setNewName("");
        setNewCity("");
        fetchCallbook();
        beepPlayer?.playTalkGrant();
      }
    } catch {
      // Error
    }
  };

  return (
    <div className="min-h-screen pb-20 md:pb-8 flex flex-col bg-[#07090e] bg-grid-pattern">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-7xl w-full mx-auto px-3 sm:px-4 py-4 space-y-4">
        
        {/* Header */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 sm:p-5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-2xl">
          <div>
            <h2 className="text-xl font-heading font-black text-white flex items-center gap-2">
              <Users className="w-5 h-5 text-cyan-400" />
              CALLBOOK DIRECTORY & USER REGISTRY
            </h2>
            <p className="text-xs font-mono text-slate-400 mt-1">
              Direktori multi-identitas Radio Amatir (RadioID.net), RAPI (10-28 ID), CB 11M, dan Radio Relawan/Komunitas
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">
            {/* Hidden File Input */}
            <input
              type="file"
              ref={fileInputRef}
              accept=".csv,.json"
              onChange={handleFileUpload}
              className="hidden"
            />
            
            <button
              onClick={() => fileInputRef.current?.click()}
              className="flex items-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-cyan-900/60 hover:border-cyan-400 text-cyan-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
            >
              <Upload className="w-4 h-4 text-cyan-400" />
              <span>Import CSV / JSON</span>
            </button>

            <button
              onClick={() => setShowAddModal(true)}
              className="flex items-center gap-1.5 px-4 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg shadow-cyan-500/20 transition-all"
            >
              <Plus className="w-4 h-4" />
              <span>Daftar User Baru</span>
            </button>
          </div>
        </div>

        {/* Import Status Notification */}
        {importStatus && (
          <div className="bg-[#0b121e] border border-cyan-500/50 p-3 rounded-xl text-xs font-mono font-bold text-cyan-300 flex items-center gap-2 animate-pulse">
            <Check className="w-4 h-4 text-emerald-400" />
            <span>{importStatus}</span>
          </div>
        )}

        {/* Filters */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-xl p-3 flex flex-col sm:flex-row items-center justify-between gap-3 shadow-lg">
          <div className="relative w-full sm:w-80">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Cari DMR ID, Callsign, Nama, Kota..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-9 pr-3 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white placeholder-slate-500 font-mono focus:outline-none focus:border-cyan-400"
            />
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto text-xs font-mono text-slate-400">
            <span>Filter Organisasi:</span>
            <select
              value={orgFilter}
              onChange={(e) => setOrgFilter(e.target.value)}
              className="px-2.5 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-cyan-300 font-mono"
            >
              <option value="ALL">Semua Organisasi</option>
              {organizations.map((org) => (
                <option key={org.code} value={org.code}>
                  {org.name || org.code}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Directory Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
          {entries.map((entry) => (
            <div
              key={entry.id}
              className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 hover:border-cyan-500/50 hover:shadow-[0_0_15px_rgba(6,182,212,0.1)] transition-all space-y-3"
            >
              <div className="flex items-start justify-between">
                <div>
                  <span className="text-[11px] font-mono text-slate-400">DMR ID: <strong className="text-cyan-400">{entry.dmr_id}</strong></span>
                  <h3 className="text-2xl font-black text-white font-heading tracking-wide mt-0.5">
                    {entry.callsign}
                  </h3>
                </div>
                <span
                  className={`px-2 py-0.5 rounded-full text-[10px] font-mono font-bold ${
                    entry.organization === "ORARI"
                      ? "bg-cyan-950 text-cyan-300 border border-cyan-800"
                      : entry.organization === "RAPI"
                      ? "bg-blue-950 text-blue-300 border border-blue-800"
                      : entry.organization === "CB-11M"
                      ? "bg-amber-950 text-amber-300 border border-amber-800"
                      : "bg-emerald-950 text-emerald-300 border border-emerald-800"
                  }`}
                >
                  {entry.organization}
                </span>
              </div>

              <div className="space-y-1 text-xs">
                <div className="text-slate-200 font-semibold">{entry.name}</div>
                <div className="flex items-center gap-1 text-slate-400 text-[11px] font-mono">
                  <MapPin className="w-3.5 h-3.5 text-cyan-500" />
                  <span>{entry.city || "Indonesia"}, {entry.province_state}</span>
                </div>
                {entry.organization_id && (
                  <div className="text-[11px] text-slate-500 font-mono">
                    ID Org: {entry.organization_id}
                  </div>
                )}
              </div>

              {entry.remarks && (
                <div className="pt-2 border-t border-cyan-900/40 text-[11px] text-slate-400 italic">
                  "{entry.remarks}"
                </div>
              )}
            </div>
          ))}
        </div>

      </main>

      {/* Add Callbook Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-cyan-500/60 rounded-3xl max-w-md w-full p-5 space-y-4 shadow-2xl">
            <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
              <Plus className="w-4 h-4 text-cyan-400" />
              Daftarkan Callsign / DMR ID Baru
            </h3>
            <form onSubmit={handleAddCallbook} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-400 mb-1">DMR ID (7 Digit)</label>
                <input
                  type="number"
                  required
                  placeholder="Contoh: 5104001"
                  value={newDmrId}
                  onChange={(e) => setNewDmrId(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Callsign / 10-28 Call</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: YB1AAA atau JZ10XYZ"
                  value={newCallsign}
                  onChange={(e) => setNewCallsign(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Nama Operator</label>
                <input
                  type="text"
                  required
                  placeholder="Nama Lengkap"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="text-slate-400">Organisasi</label>
                    <button
                      type="button"
                      onClick={() => {
                        setAdminPinInput("");
                        setNewOrgName("");
                        setNewOrgCode("");
                        setNewOrgDesc("");
                        setOrgPinError(null);
                        setShowOrgPinModal(true);
                      }}
                      className="text-[11px] text-cyan-400 hover:text-cyan-300 underline font-mono flex items-center gap-0.5"
                    >
                      + Tambah (PIN)
                    </button>
                  </div>
                  <select
                    value={newOrg}
                    onChange={(e) => setNewOrg(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    {organizations.map((org) => (
                      <option key={org.code} value={org.code}>
                        {org.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1">Kota / QTH</label>
                  <input
                    type="text"
                    placeholder="Kota"
                    value={newCity}
                    onChange={(e) => setNewCity(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white"
                  />
                </div>
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 rounded-xl font-heading font-black shadow-md"
                >
                  Simpan ID
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal Tambah Organisasi Admin PIN */}
      {showOrgPinModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0b101b] border border-cyan-500/40 rounded-2xl p-6 w-full max-w-md shadow-2xl relative">
            <h3 className="text-lg font-heading font-black text-white mb-1 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse" />
              Tambah Organisasi Baru
            </h3>
            <p className="text-xs text-slate-400 mb-4">
              Khusus Admin. Masukkan PIN Admin untuk mendaftarkan organisasi radio baru ke sistem.
            </p>

            {orgPinError && (
              <div className="mb-4 p-2.5 rounded-xl bg-red-950/60 border border-red-500/40 text-red-300 text-xs">
                ⚠️ {orgPinError}
              </div>
            )}

            <form onSubmit={handleCreateOrgWithPin} className="space-y-3.5 text-sm">
              <div>
                <label className="block text-slate-400 text-xs mb-1 font-mono">
                  PIN Admin <span className="text-rose-400">*</span>
                </label>
                <input
                  type="password"
                  required
                  autoFocus
                  placeholder="Masukkan PIN Admin (default: 1234)"
                  value={adminPinInput}
                  onChange={(e) => setAdminPinInput(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono tracking-widest focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 text-xs mb-1">
                  Nama Organisasi <span className="text-rose-400">*</span>
                </label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: SENKOM, POLRI, TNI, PMR"
                  value={newOrgName}
                  onChange={(e) => {
                    const val = e.target.value;
                    setNewOrgName(val);
                    setNewOrgCode(val.toUpperCase().replace(/\s+/g, "_"));
                  }}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 text-xs mb-1 font-mono">Kode Singkat</label>
                  <input
                    type="text"
                    placeholder="Contoh: SENKOM"
                    value={newOrgCode}
                    onChange={(e) => setNewOrgCode(e.target.value.toUpperCase())}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                  />
                </div>
                <div>
                  <label className="block text-slate-400 text-xs mb-1">Keterangan (Opsional)</label>
                  <input
                    type="text"
                    placeholder="Mitra Kamtibmas dll"
                    value={newOrgDesc}
                    onChange={(e) => setNewOrgDesc(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                  />
                </div>
              </div>

              <div className="flex gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setShowOrgPinModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700 transition"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingOrg}
                  className="flex-1 py-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-slate-950 font-heading font-black rounded-xl shadow-lg transition disabled:opacity-50"
                >
                  {isSubmittingOrg ? "Memverifikasi..." : "Simpan Organisasi"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
