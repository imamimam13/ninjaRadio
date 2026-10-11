"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { BookOpen, Download, Search, Plus, Filter, Radio, Award, Shield, FileSpreadsheet, Activity, Lock, X } from "lucide-react";
import { beepPlayer } from "@/components/BeepPlayer";

interface QsoItem {
  id: number;
  timestamp: string;
  callsign: string;
  source_id: number;
  source_name: string;
  target_tg: number;
  target_name: string;
  slot: number;
  mode: string;
  category: string;
  duration_sec: number;
  rssi: string;
  ber: string;
  loss: string;
  remarks: string;
}

const DEFAULT_CATEGORIES = [
  { id: "Amatir", label: "Radio Amatir (ORARI)" },
  { id: "RAPI", label: "RAPI (10-28)" },
  { id: "CB", label: "11M Citizens Band" },
  { id: "Komunitas", label: "Komunitas / SAR" },
];

export default function LogbookPage() {
  const { isConnected } = useLiveMonitor();
  const [logs, setLogs] = useState<QsoItem[]>([]);
  const [selectedCategory, setSelectedCategory] = useState("ALL");
  const [selectedMode, setSelectedMode] = useState("ALL");
  const [searchTerm, setSearchTerm] = useState("");
  const [showAddModal, setShowAddModal] = useState(false);

  // Custom Categories & Admin Lock State
  const [categories, setCategories] = useState(DEFAULT_CATEGORIES);
  const [showAddCatModal, setShowAddCatModal] = useState(false);
  const [newCatLabel, setNewCatLabel] = useState("");
  const [newCatPin, setNewCatPin] = useState("");
  const [newCatError, setNewCatError] = useState("");
  const [isSavingCat, setIsSavingCat] = useState(false);

  // New QSO state
  const [newCallsign, setNewCallsign] = useState("");
  const [newName, setNewName] = useState("");
  const [newTg, setNewTg] = useState(510);
  const [newMode, setNewMode] = useState("DMR");
  const [newCategory, setNewCategory] = useState("Amatir");
  const [newRemarks, setNewRemarks] = useState("");

  // Load custom categories from localStorage
  useEffect(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("ninjaradio_custom_categories");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed) && parsed.length > 0) {
            setCategories([...DEFAULT_CATEGORIES, ...parsed]);
          }
        } catch {}
      }
    }
  }, []);

  const handleAddCategory = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCatLabel.trim() || !newCatPin.trim()) return;
    setIsSavingCat(true);
    setNewCatError("");
    try {
      const res = await fetch("/api/auth/verify-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin: newCatPin.trim() }),
      });
      const data = await res.json();
      if (data.valid || newCatPin.trim() === "1234") {
        const catId = newCatLabel.trim().replace(/\s+/g, "_");
        const newCat = { id: catId, label: newCatLabel.trim() };
        
        let stored: any[] = [];
        if (typeof window !== "undefined") {
          const saved = localStorage.getItem("ninjaradio_custom_categories");
          if (saved) {
            try { stored = JSON.parse(saved); } catch {}
          }
          stored.push(newCat);
          localStorage.setItem("ninjaradio_custom_categories", JSON.stringify(stored));
        }
        setCategories([...categories, newCat]);
        setNewCategory(catId);
        setShowAddCatModal(false);
        setNewCatLabel("");
        setNewCatPin("");
      } else {
        setNewCatError("PIN Admin Salah! Hanya Administrator yang dapat menambah kategori.");
      }
    } catch {
      if (newCatPin.trim() === "1234") {
        const catId = newCatLabel.trim().replace(/\s+/g, "_");
        const newCat = { id: catId, label: newCatLabel.trim() };
        setCategories([...categories, newCat]);
        setNewCategory(catId);
        setShowAddCatModal(false);
        setNewCatLabel("");
        setNewCatPin("");
      } else {
        setNewCatError("PIN Admin Salah!");
      }
    } finally {
      setIsSavingCat(false);
    }
  };

  const fetchLogs = () => {
    let url = `/api/logbook?`;
    if (selectedCategory !== "ALL") url += `category=${selectedCategory}&`;
    if (selectedMode !== "ALL") url += `mode=${selectedMode}&`;
    if (searchTerm) url += `query=${encodeURIComponent(searchTerm)}&`;

    fetch(url)
      .then((res) => res.json())
      .then((data) => setLogs(data))
      .catch(() => {});
  };

  useEffect(() => {
    fetchLogs();
  }, [selectedCategory, selectedMode, searchTerm]);

  const handleDownloadADIF = () => {
    const url = `/api/logbook/export/adif?category=${selectedCategory}`;
    window.open(url, "_blank");
  };

  const handleAddQso = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCallsign) return;
    try {
      const res = await fetch("/api/logbook", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: newCallsign.toUpperCase(),
          source_id: 5100000 + Math.floor(Math.random() * 9000),
          source_name: newName,
          target_tg: Number(newTg),
          target_name: `TG ${newTg}`,
          slot: 1,
          mode: newMode,
          category: newCategory,
          duration_sec: 15.0,
          rssi: "-72 dBm",
          ber: "0.0%",
          loss: "0.0%",
          remarks: newRemarks || "QSO Logged via NinjaRadio Hub",
        }),
      });
      if (res.ok) {
        setShowAddModal(false);
        setNewCallsign("");
        setNewName("");
        setNewRemarks("");
        fetchLogs();
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
              <BookOpen className="w-5 h-5 text-cyan-400" />
              UNIFIED QSO LOGBOOK & ADIF EXPORT
            </h2>
            <p className="text-xs font-mono text-slate-400 mt-1">
              Catatan kontak Radio Amatir (ORARI), RAPI (10-28), CB 11-Meter, dan Radio Relawan/Komunitas
            </p>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto">
            <button
              onClick={() => setShowAddModal(true)}
              className="flex-1 sm:flex-none flex items-center justify-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-cyan-900/60 hover:border-cyan-400 text-cyan-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
            >
              <Plus className="w-4 h-4 text-cyan-400" />
              <span>Tambah QSO Manual</span>
            </button>

            <button
              onClick={handleDownloadADIF}
              className="flex-1 sm:flex-none flex items-center justify-center gap-1.5 px-4 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg shadow-cyan-500/20 transition-all"
            >
              <Download className="w-4 h-4" />
              <span>Export ADIF (eQSL/LoTW)</span>
            </button>
          </div>
        </div>

        {/* Categories Bar */}
        <div className="flex flex-wrap items-center gap-2">
          {[{ id: "ALL", label: "Semua Kategori" }, ...categories].map((cat) => (
            <button
              key={cat.id}
              onClick={() => setSelectedCategory(cat.id)}
              className={`px-3.5 py-1.5 rounded-xl text-xs font-mono font-bold transition-all ${
                selectedCategory === cat.id
                  ? "bg-cyan-400 text-slate-950 shadow-[0_0_12px_rgba(6,182,212,0.3)]"
                  : "bg-[#0b121e] border border-cyan-900/40 text-slate-400 hover:text-white"
              }`}
            >
              {cat.label}
            </button>
          ))}
          <button
            onClick={() => setShowAddCatModal(true)}
            className="px-3 py-1.5 rounded-xl text-xs font-mono font-bold bg-[#0b121e] border border-amber-500/50 text-amber-300 hover:bg-amber-500/15 transition-all flex items-center gap-1.5 shadow-sm"
            title="Tambah Kategori Baru (Perlu PIN Admin)"
          >
            <Lock className="w-3.5 h-3.5 text-amber-400" />
            <span>+ Kategori Baru (Admin)</span>
          </button>
        </div>

        {/* Filters and Search */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-xl p-3 flex flex-col sm:flex-row items-center justify-between gap-3 shadow-lg">
          <div className="relative w-full sm:w-80">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Cari Callsign, Nama, Keterangan..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-9 pr-3 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white placeholder-slate-500 font-mono focus:outline-none focus:border-cyan-400"
            />
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto text-xs font-mono text-slate-400">
            <span>Filter Mode:</span>
            <select
              value={selectedMode}
              onChange={(e) => setSelectedMode(e.target.value)}
              className="px-2.5 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-cyan-300 font-mono"
            >
              <option value="ALL">Semua Mode</option>
              <option value="DMR">DMR</option>
              <option value="YSF">YSF</option>
              <option value="P25">P25</option>
              <option value="ANALOG">Analog / ASL</option>
              <option value="CB">CB Digital</option>
            </select>
          </div>
        </div>

        {/* Table of QSOs */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl overflow-hidden shadow-2xl">
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-cyan-900/40 bg-[#070c16] text-slate-400 font-mono text-[10px] uppercase tracking-wider">
                  <th className="py-3 px-4">TANGGAL / WAKTU</th>
                  <th className="py-3 px-4">CALLSIGN</th>
                  <th className="py-3 px-4">NAMA OPERATOR</th>
                  <th className="py-3 px-4">KATEGORI</th>
                  <th className="py-3 px-4">TALKGROUP</th>
                  <th className="py-3 px-4">MODE</th>
                  <th className="py-3 px-4">DURASI</th>
                  <th className="py-3 px-4">CATATAN QSO</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-cyan-950/60 font-mono">
                {logs.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="text-center py-12 text-slate-500 font-mono text-xs">
                      Belum ada catatan log kontak QSO yang cocok.
                    </td>
                  </tr>
                ) : (
                  logs.map((log) => (
                    <tr key={log.id} className="hover:bg-cyan-950/20 transition-colors group">
                      <td className="py-2.5 px-4 text-slate-400 text-[11px]">
                        {new Date(log.timestamp).toLocaleString("id-ID")}
                      </td>
                      <td className="py-2.5 px-4 font-black text-cyan-400 text-sm">
                        {log.callsign}
                      </td>
                      <td className="py-2.5 px-4 text-slate-200 font-sans font-medium">
                        {log.source_name || "-"}
                      </td>
                      <td className="py-2.5 px-4">
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-slate-900 text-slate-300 border border-slate-700">
                          {log.category}
                        </span>
                      </td>
                      <td className="py-2.5 px-4 font-bold text-white">
                        <span className="px-1.5 py-0.5 rounded bg-slate-900 border border-slate-700 text-cyan-300">
                          TG {log.target_tg}
                        </span>
                      </td>
                      <td className="py-2.5 px-4 font-bold text-emerald-400">{log.mode}</td>
                      <td className="py-2.5 px-4 text-slate-300 font-bold">{log.duration_sec}s</td>
                      <td className="py-2.5 px-4 text-slate-400 font-sans">{log.remarks || "-"}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

      {/* Manual QSO Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-cyan-500/60 rounded-3xl max-w-md w-full p-5 space-y-4 shadow-2xl">
            <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
              <Plus className="w-4 h-4 text-cyan-400" />
              Catat QSO Baru Manual
            </h3>
            <form onSubmit={handleAddQso} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-400 mb-1">Callsign / 10-28 Call</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: YB0AZ atau JZ10XYZ"
                  value={newCallsign}
                  onChange={(e) => setNewCallsign(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Nama Operator</label>
                <input
                  type="text"
                  placeholder="Nama Lengkap"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 mb-1">Kategori</label>
                  <select
                    value={newCategory}
                    onChange={(e) => {
                      if (e.target.value === "__add_new__") {
                        setShowAddCatModal(true);
                      } else {
                        setNewCategory(e.target.value);
                      }
                    }}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    {categories.map((c) => (
                      <option key={c.id} value={c.id}>{c.label}</option>
                    ))}
                    <option value="__add_new__">➕ Tambah Kategori Baru (PIN Admin)...</option>
                  </select>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1">Mode</label>
                  <select
                    value={newMode}
                    onChange={(e) => setNewMode(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    <option value="DMR">DMR</option>
                    <option value="YSF">YSF</option>
                    <option value="P25">P25</option>
                    <option value="ANALOG">Analog FM / ASL</option>
                    <option value="CB">CB 11M</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Target Talkgroup (TG)</label>
                <input
                  type="number"
                  value={newTg}
                  onChange={(e) => setNewTg(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Catatan / Remarks</label>
                <input
                  type="text"
                  placeholder="Contoh: 5/9 Clear Audio, QTH Jakarta"
                  value={newRemarks}
                  onChange={(e) => setNewRemarks(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white outline-none"
                />
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
                  Simpan QSO
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Add Custom Category Admin PIN Modal */}
      {showAddCatModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-amber-500/80 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 rounded-lg bg-amber-500/20 border border-amber-500 flex items-center justify-center text-amber-400">
                  <Lock className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="font-heading font-black text-sm text-white">Tambah Kategori (Admin)</h3>
                  <p className="text-[10px] font-mono text-slate-400">Perlu PIN Administrator untuk menambah</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setShowAddCatModal(false); setNewCatError(""); }}
                className="p-1 rounded-lg text-slate-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleAddCategory} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-300 mb-1 font-semibold">Nama Kategori Baru:</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: Pramuka Radio / Satgas SAR / Polisi"
                  value={newCatLabel}
                  onChange={(e) => setNewCatLabel(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 rounded-xl text-white focus:border-amber-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-semibold">PIN Administrator (Default: 1234):</label>
                <input
                  type="password"
                  required
                  maxLength={12}
                  placeholder="PIN 4 Digit"
                  value={newCatPin}
                  onChange={(e) => { setNewCatPin(e.target.value); setNewCatError(""); }}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 rounded-xl text-center text-base tracking-widest text-amber-300 font-bold focus:border-amber-400 outline-none"
                />
                {newCatError && (
                  <p className="text-red-400 text-[11px] font-bold mt-1 text-center">{newCatError}</p>
                )}
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddCatModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSavingCat}
                  className="flex-1 py-2 bg-amber-400 hover:bg-amber-300 text-slate-950 font-black rounded-xl shadow-md"
                >
                  {isSavingCat ? "Memverifikasi..." : "Simpan Kategori"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
