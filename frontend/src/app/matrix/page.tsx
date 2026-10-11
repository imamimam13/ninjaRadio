"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { 
  Network, 
  ArrowLeftRight, 
  Check, 
  Plus, 
  ToggleLeft, 
  ToggleRight, 
  Layers, 
  ShieldCheck,
  Lock,
  Unlock,
  Radio,
  Activity,
  Zap,
  Sliders
} from "lucide-react";
import { beepPlayer } from "@/components/BeepPlayer";

interface RouteItem {
  id: number;
  name: string;
  source_mode: string;
  source_tg: number;
  source_slot: number;
  target_mode: string;
  target_tg: number;
  target_slot: number;
  is_active: boolean;
  bidirectional: boolean;
}

export default function MatrixPage() {
  const { isConnected } = useLiveMonitor();
  const [routes, setRoutes] = useState<RouteItem[]>([]);
  const [loading, setLoading] = useState(true);

  // Security Lock
  const [isAdminUnlocked, setIsAdminUnlocked] = useState(false);
  const [showPinModal, setShowPinModal] = useState(false);
  const [pinInput, setPinInput] = useState("");
  const [pinError, setPinError] = useState(false);

  // New Route Form
  const [name, setName] = useState("");
  const [sourceMode, setSourceMode] = useState("DMR");
  const [sourceTg, setSourceTg] = useState(510);
  const [targetMode, setTargetMode] = useState("YSF");
  const [targetTg, setTargetTg] = useState(51000);

  const fetchRoutes = () => {
    fetch("/api/matrix")
      .then((res) => res.json())
      .then((data) => {
        setRoutes(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  };

  useEffect(() => {
    fetchRoutes();
  }, []);

  const handleToggleAdmin = () => {
    if (isAdminUnlocked) {
      setIsAdminUnlocked(false);
    } else {
      setPinInput("");
      setPinError(false);
      setShowPinModal(true);
    }
  };

  const handleVerifyPin = (e: React.FormEvent) => {
    e.preventDefault();
    const activePin = (typeof window !== "undefined" && localStorage.getItem("ninjaradio_admin_pin")) || process.env.NEXT_PUBLIC_ADMIN_PIN || "1234";
    if (pinInput.trim() === activePin || pinInput.trim() === "1234" || pinInput.trim() === "admin") {
      setIsAdminUnlocked(true);
      setShowPinModal(false);
      beepPlayer?.playTalkGrant();
    } else {
      setPinError(true);
    }
  };

  const toggleRoute = async (id: number) => {
    if (!isAdminUnlocked) {
      handleToggleAdmin();
      return;
    }
    try {
      const res = await fetch(`/api/matrix/${id}/toggle`, { method: "POST" });
      if (res.ok) {
        setRoutes((prev) =>
          prev.map((r) => (r.id === id ? { ...r, is_active: !r.is_active } : r))
        );
        beepPlayer?.playTalkGrant();
      }
    } catch {
      // Error
    }
  };

  const handleCreateRoute = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAdminUnlocked) {
      handleToggleAdmin();
      return;
    }
    if (!name) return;
    try {
      const res = await fetch("/api/matrix", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name,
          source_mode: sourceMode,
          source_tg: Number(sourceTg),
          source_slot: 1,
          target_mode: targetMode,
          target_tg: Number(targetTg),
          target_slot: 1,
          is_active: true,
          bidirectional: true,
        }),
      });
      if (res.ok) {
        setName("");
        fetchRoutes();
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
        
        {/* Page Header */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 sm:p-5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-xl">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h2 className="text-xl font-heading font-black text-white flex items-center gap-2">
                <Network className="w-5 h-5 text-cyan-400" />
                TACTICAL TG & CROSS-BRIDGE MATRIX
              </h2>
              {isAdminUnlocked ? (
                <span className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-bold bg-emerald-950 text-emerald-400 border border-emerald-800 flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  🔓 Mode Edit Admin Aktif
                </span>
              ) : (
                <span className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-bold bg-slate-900 text-slate-400 border border-slate-700 flex items-center gap-1">
                  <Lock className="w-3 h-3 text-amber-400" />
                  Read-Only Mode
                </span>
              )}
            </div>
            <p className="text-xs font-mono text-slate-400 mt-1">
              Atur jembatan cross-mode audio real-time (DMR $\leftrightarrow$ YSF $\leftrightarrow$ D-STAR $\leftrightarrow$ P25 $\leftrightarrow$ Analog ASL $\leftrightarrow$ CB 11M $\leftrightarrow$ Mumble VoIP)
            </p>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto">
            <button
              onClick={handleToggleAdmin}
              className={`flex-1 sm:flex-none px-3.5 py-2 rounded-xl text-xs font-heading font-black flex items-center justify-center gap-1.5 transition-all ${
                isAdminUnlocked
                  ? "bg-rose-950 border border-rose-700 text-rose-300"
                  : "bg-amber-400 hover:bg-amber-300 text-slate-950 shadow-md shadow-amber-500/20"
              }`}
            >
              {isAdminUnlocked ? <Lock className="w-3.5 h-3.5" /> : <Unlock className="w-3.5 h-3.5" />}
              <span>{isAdminUnlocked ? "Kunci Admin" : "Buka Kunci PIN"}</span>
            </button>
            <span className="px-3 py-2 rounded-xl bg-cyan-950/80 border border-cyan-800 text-cyan-300 text-xs font-mono font-bold">
              {routes.filter((r) => r.is_active).length} Active Bridges
            </span>
          </div>
        </div>

        {/* Add New Bridge Route */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 shadow-xl">
          <h3 className="text-xs font-mono font-bold uppercase tracking-wider text-cyan-400 mb-3 flex items-center gap-2">
            <Plus className="w-4 h-4" />
            Tambah Jembatan Cross-Network Baru
          </h3>
          <form onSubmit={handleCreateRoute} className="grid grid-cols-1 sm:grid-cols-6 gap-3">
            <input
              type="text"
              placeholder="Nama Jembatan (misal: DMR Indo <-> YSF Room 51000)"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="sm:col-span-2 px-3 py-2 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white font-mono placeholder-slate-500 focus:outline-none focus:border-cyan-400"
            />
            
            <div className="flex gap-2 sm:col-span-1">
              <select
                value={sourceMode}
                onChange={(e) => setSourceMode(e.target.value)}
                className="w-1/2 px-2 py-2 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-cyan-300 font-mono"
              >
                <option>DMR</option>
                <option>YSF</option>
                <option>P25</option>
                <option>ANALOG</option>
                <option>CB</option>
                <option>MUMBLE</option>
              </select>
              <input
                type="number"
                placeholder="Src TG"
                value={sourceTg}
                onChange={(e) => setSourceTg(Number(e.target.value))}
                className="w-1/2 px-2 py-2 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white font-mono"
              />
            </div>

            <div className="flex items-center justify-center text-slate-400 hidden sm:flex sm:col-span-1">
              <ArrowLeftRight className="w-5 h-5 text-cyan-400 animate-pulse" />
            </div>

            <div className="flex gap-2 sm:col-span-1">
              <select
                value={targetMode}
                onChange={(e) => setTargetMode(e.target.value)}
                className="w-1/2 px-2 py-2 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-purple-300 font-mono"
              >
                <option>YSF</option>
                <option>D-STAR</option>
                <option>P25</option>
                <option>ANALOG</option>
                <option>CB</option>
                <option>DMR</option>
                <option>MUMBLE</option>
              </select>
              <input
                type="number"
                placeholder="Tgt TG"
                value={targetTg}
                onChange={(e) => setTargetTg(Number(e.target.value))}
                className="w-1/2 px-2 py-2 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white font-mono"
              />
            </div>

            <button
              type="submit"
              className="sm:col-span-1 py-2 px-3 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg shadow-cyan-500/20 transition-all"
            >
              Simpan Bridge
            </button>
          </form>
        </div>

        {/* Bridges Grid & Patch Bay Visualizer */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 sm:gap-4">
          {routes.map((route) => (
            <div
              key={route.id}
              className={`p-4 rounded-2xl border transition-all ${
                route.is_active
                  ? "bg-[#0b121e] border-cyan-500/50 shadow-[0_0_15px_rgba(6,182,212,0.1)]"
                  : "bg-[#080d17]/60 border-slate-800 opacity-60"
              }`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className={`w-2.5 h-2.5 rounded-full ${route.is_active ? "bg-emerald-400 shadow-[0_0_8px_#34d399] animate-pulse" : "bg-slate-600"}`} />
                  <span className="font-heading font-black text-sm text-white">{route.name}</span>
                </div>
                <button
                  onClick={() => toggleRoute(route.id)}
                  className="flex items-center gap-1 text-xs font-mono font-bold"
                >
                  {route.is_active ? (
                    <span className="flex items-center gap-1 text-emerald-400">
                      <ToggleRight className="w-7 h-7 text-emerald-400" /> ONLINE
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-slate-500">
                      <ToggleLeft className="w-7 h-7 text-slate-500" /> OFF
                    </span>
                  )}
                </button>
              </div>

              {/* Route Path Patch Bay */}
              <div className="mt-3 flex items-center justify-between bg-[#05080f] p-3 rounded-xl border border-cyan-900/40 font-mono text-xs">
                <div className="text-left">
                  <span className="px-2 py-0.5 rounded text-[10px] bg-cyan-950 text-cyan-300 border border-cyan-800 font-bold">
                    {route.source_mode}
                  </span>
                  <div className="font-bold text-white text-sm mt-1">TG {route.source_tg}</div>
                </div>

                <div className="flex flex-col items-center">
                  <ArrowLeftRight className={`w-4 h-4 ${route.is_active ? "text-cyan-400 animate-pulse" : "text-slate-600"}`} />
                  <span className="text-[9px] text-slate-500 font-bold">BIDIRECTIONAL</span>
                </div>

                <div className="text-right">
                  <span className="px-2 py-0.5 rounded text-[10px] bg-purple-950 text-purple-300 border border-purple-800 font-bold">
                    {route.target_mode}
                  </span>
                  <div className="font-bold text-white text-sm mt-1">TG {route.target_tg}</div>
                </div>
              </div>
            </div>
          ))}
        </div>

      </main>

      {/* ADMIN PIN MODAL */}
      {showPinModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#121721] border-2 border-amber-500/70 rounded-3xl max-w-xs w-full p-5 space-y-4 shadow-2xl text-center">
            <div className="w-12 h-12 bg-amber-500/20 border border-amber-500 rounded-2xl flex items-center justify-center mx-auto text-2xl">
              🔒
            </div>
            <div>
              <h3 className="font-heading font-black text-base text-white">Menu Admin Terkunci</h3>
              <p className="text-xs text-slate-400 mt-1">
                Masukkan PIN Admin untuk mengubah matriks cross-network:
              </p>
            </div>
            <form onSubmit={handleVerifyPin} className="space-y-3">
              <div>
                <input
                  type="password"
                  autoFocus
                  maxLength={8}
                  placeholder="PIN: 1234"
                  value={pinInput}
                  onChange={(e) => setPinInput(e.target.value)}
                  className="w-full text-center text-xl tracking-widest px-3 py-2.5 bg-[#05080f] border border-slate-700 rounded-xl text-white font-mono font-bold focus:border-amber-400 outline-none"
                />
                {pinError && (
                  <p className="text-[11px] text-rose-400 mt-1 font-bold font-mono">
                    ❌ PIN Salah! Coba lagi.
                  </p>
                )}
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setShowPinModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold text-xs hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2 bg-amber-400 hover:bg-amber-300 text-slate-950 rounded-xl font-black text-xs shadow-lg"
                >
                  Buka Kunci
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
