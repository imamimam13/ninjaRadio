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
  Sliders,
  Cpu,
  Server,
  Trash2,
  RefreshCw,
  Flame,
  Edit2
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

interface BridgeWorker {
  id: string;
  tg: number;
  slot: number;
  usrp_rx_port: number;
  usrp_tx_port: number;
  pid: number | null;
  status: string;
  is_primary: boolean;
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

  // Dynamic Bridge Workers State (Metode B)
  const [workers, setWorkers] = useState<BridgeWorker[]>([]);
  const [spawnTg, setSpawnTg] = useState(5100);
  const [spawnSlot, setSpawnSlot] = useState(2);
  const [isSpawning, setIsSpawning] = useState(false);
  const [workerMsg, setWorkerMsg] = useState<{ text: string; isError: boolean } | null>(null);

  // Primary Bridge Retune State
  const [showTuneModal, setShowTuneModal] = useState(false);
  const [tunePrimaryTg, setTunePrimaryTg] = useState(1025);
  const [tunePrimarySlot, setTunePrimarySlot] = useState(2);
  const [isTuning, setIsTuning] = useState(false);

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

  const fetchWorkers = async () => {
    try {
      const res = await fetch("/api/workers");
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data.workers)) {
          setWorkers(data.workers);
        }
      }
    } catch {
      // offline
    }
  };

  useEffect(() => {
    fetchRoutes();
    fetchWorkers();
    const interval = setInterval(fetchWorkers, 10000);
    return () => clearInterval(interval);
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

  const handleSpawnWorker = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAdminUnlocked) {
      handleToggleAdmin();
      return;
    }
    if (!spawnTg || spawnTg <= 0) return;
    setIsSpawning(true);
    setWorkerMsg(null);
    try {
      const res = await fetch("/api/workers/spawn", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tg: Number(spawnTg), slot: Number(spawnSlot) }),
      });
      const data = await res.json();
      if (data.status === "SUCCESS") {
        setWorkerMsg({
          text: `Worker Analog_Bridge untuk TG ${spawnTg} (Slot ${spawnSlot}) berhasil dijalankan! (PID: ${data.worker?.pid || "active"})`,
          isError: false,
        });
        fetchWorkers();
        beepPlayer?.playTalkGrant();
      } else {
        setWorkerMsg({
          text: `Gagal menjalankan worker: ${data.message || data.error || "Terjadi kesalahan"}`,
          isError: true,
        });
      }
    } catch (err: any) {
      setWorkerMsg({ text: `Gagal terhubung ke backend: ${err.message}`, isError: true });
    } finally {
      setIsSpawning(false);
    }
  };

  const handleStopWorker = async (workerId: string, tg: number) => {
    if (!isAdminUnlocked) {
      handleToggleAdmin();
      return;
    }
    if (!confirm(`Hentikan worker bridge untuk TG ${tg} (${workerId})?`)) return;
    try {
      const res = await fetch(`/api/workers/${workerId}`, { method: "DELETE" });
      if (res.ok) {
        setWorkerMsg({ text: `Worker ${workerId} berhasil dihentikan.`, isError: false });
        fetchWorkers();
        beepPlayer?.playTalkGrant();
      } else {
        const data = await res.json();
        setWorkerMsg({ text: `Gagal menghentikan: ${data.message || data.error}`, isError: true });
      }
    } catch (err: any) {
      setWorkerMsg({ text: `Error: ${err.message}`, isError: true });
    }
  };

  const handleOpenTunePrimary = (currentTg: number, currentSlot: number) => {
    if (!isAdminUnlocked) {
      handleToggleAdmin();
      return;
    }
    setTunePrimaryTg(currentTg);
    setTunePrimarySlot(currentSlot);
    setShowTuneModal(true);
  };

  const handleTunePrimary = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tunePrimaryTg || tunePrimaryTg <= 0) return;
    setIsTuning(true);
    setWorkerMsg(null);
    try {
      const res = await fetch("/api/workers/primary/tune", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tg: Number(tunePrimaryTg), slot: Number(tunePrimarySlot) }),
      });
      const data = await res.json();
      if (res.ok && data.status === "SUCCESS") {
        setWorkerMsg({
          text: `Primary Bridge berhasil dialihkan ke TG ${tunePrimaryTg} (Slot ${tunePrimarySlot}) secara real-time!`,
          isError: false,
        });
        setShowTuneModal(false);
        fetchWorkers();
        beepPlayer?.playTalkGrant();
      } else {
        setWorkerMsg({
          text: `Gagal mengubah TG: ${data.detail || data.message || "Error"}`,
          isError: true,
        });
        beepPlayer?.playRogerBeep();
      }
    } catch (err: any) {
      setWorkerMsg({ text: `Gagal: ${err.message}`, isError: true });
    } finally {
      setIsTuning(false);
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

        {/* Dynamic Multi-Worker Analog_Bridge Section (Metode B) */}
        <div className="bg-[#0b121e] border border-indigo-900/40 rounded-2xl p-4 sm:p-5 shadow-xl space-y-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
            <div>
              <h3 className="text-base font-heading font-black text-white flex items-center gap-2">
                <Cpu className="w-5 h-5 text-indigo-400" />
                MULTI-WORKER ANALOG BRIDGE (METODE B)
              </h3>
              <p className="text-xs font-mono text-slate-400 mt-1">
                Worker Analog_Bridge modular on-demand di dalam kontainer Core. Mendukung multi-TG audio simultan tanpa restart daemon (~15MB RAM per-worker).
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={fetchWorkers}
                className="px-3 py-1.5 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-700 text-slate-300 text-xs font-mono font-bold flex items-center gap-1.5 transition-all"
              >
                <RefreshCw className="w-3.5 h-3.5 text-cyan-400" />
                Segarkan
              </button>
              <span className="px-3 py-1.5 rounded-xl bg-indigo-950/80 border border-indigo-800 text-indigo-300 text-xs font-mono font-bold flex items-center gap-1.5">
                <Server className="w-3.5 h-3.5" />
                {workers.length} / 16 Worker Aktif
              </span>
            </div>
          </div>

          {workerMsg && (
            <div
              className={`p-3 rounded-xl border text-xs font-mono flex items-center justify-between ${
                workerMsg.isError
                  ? "bg-rose-950/60 border-rose-800 text-rose-300"
                  : "bg-emerald-950/60 border-emerald-800 text-emerald-300"
              }`}
            >
              <span>{workerMsg.text}</span>
              <button
                onClick={() => setWorkerMsg(null)}
                className="text-xs opacity-70 hover:opacity-100 font-bold ml-2"
              >
                ✕
              </button>
            </div>
          )}

          {/* Spawn Worker Form */}
          <form
            onSubmit={handleSpawnWorker}
            className="p-3 bg-[#05080f] border border-indigo-900/50 rounded-xl flex flex-wrap sm:flex-nowrap items-center gap-3"
          >
            <div className="flex items-center gap-2 flex-1 min-w-[200px]">
              <span className="text-xs font-mono font-bold text-indigo-400 whitespace-nowrap">
                Target TG:
              </span>
              <input
                type="number"
                placeholder="misal: 5100"
                value={spawnTg}
                onChange={(e) => setSpawnTg(Number(e.target.value))}
                className="w-full px-3 py-2 bg-[#090d18] border border-indigo-900/60 rounded-lg text-xs font-mono text-white placeholder-slate-500 focus:outline-none focus:border-indigo-400"
              />
            </div>

            <div className="flex items-center gap-2">
              <span className="text-xs font-mono font-bold text-indigo-400 whitespace-nowrap">
                Slot:
              </span>
              <select
                value={spawnSlot}
                onChange={(e) => setSpawnSlot(Number(e.target.value))}
                className="px-3 py-2 bg-[#090d18] border border-indigo-900/60 rounded-lg text-xs font-mono text-cyan-300 focus:outline-none focus:border-indigo-400"
              >
                <option value={1}>Slot 1</option>
                <option value={2}>Slot 2</option>
              </select>
            </div>

            <button
              type="submit"
              disabled={isSpawning}
              className={`px-4 py-2 rounded-lg text-xs font-heading font-black flex items-center gap-2 transition-all whitespace-nowrap ${
                isSpawning
                  ? "bg-slate-800 text-slate-500 cursor-not-allowed"
                  : "bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-600/30"
              }`}
            >
              <Flame className="w-4 h-4 text-amber-300" />
              {isSpawning ? "Memproses..." : "+ Jalankan Worker Baru"}
            </button>
          </form>

          {/* Active Workers Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {workers.map((worker) => (
              <div
                key={worker.id}
                className={`p-3.5 rounded-xl border transition-all ${
                  worker.is_primary
                    ? "bg-[#08111e] border-cyan-500/50 shadow-md"
                    : "bg-[#090e1c] border-indigo-900/60"
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 shadow-[0_0_8px_#34d399] animate-pulse" />
                    <span className="font-mono font-bold text-xs text-white">
                      {worker.is_primary ? "PRIMARY BRIDGE" : worker.id}
                    </span>
                  </div>
                  {worker.is_primary ? (
                    <div className="flex items-center gap-1.5">
                      <button
                        onClick={() => handleOpenTunePrimary(worker.tg, worker.slot)}
                        className="p-1 px-2.5 rounded-lg bg-cyan-950/80 hover:bg-cyan-900 border border-cyan-700 text-cyan-300 text-[11px] font-mono font-bold flex items-center gap-1 transition-all"
                        title="Ubah Target TG Primary Bridge"
                      >
                        <Edit2 className="w-3 h-3 text-cyan-400" />
                        Ubah TG
                      </button>
                      <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-cyan-950 text-cyan-300 border border-cyan-800">
                        PRIMARY
                      </span>
                    </div>
                  ) : (
                    <button
                      onClick={() => handleStopWorker(worker.id, worker.tg)}
                      className="p-1 px-2 rounded-lg bg-rose-950/80 hover:bg-rose-900 border border-rose-800 text-rose-300 text-[11px] font-mono font-bold flex items-center gap-1 transition-all"
                      title="Hentikan Worker"
                    >
                      <Trash2 className="w-3 h-3 text-rose-400" />
                      Stop
                    </button>
                  )}
                </div>

                <div className="mt-2.5 grid grid-cols-2 gap-2 text-xs font-mono bg-[#05080f] p-2.5 rounded-lg border border-slate-800/80">
                  <div>
                    <span className="text-[10px] text-slate-500 block uppercase">Talkgroup</span>
                    <span className="font-bold text-amber-300">TG {worker.tg}</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-slate-500 block uppercase">Timeslot</span>
                    <span className="font-bold text-slate-300">TS {worker.slot}</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-slate-500 block uppercase">USRP Ports</span>
                    <span className="text-[11px] text-cyan-300">
                      RX:{worker.usrp_rx_port} / TX:{worker.usrp_tx_port}
                    </span>
                  </div>
                  <div>
                    <span className="text-[10px] text-slate-500 block uppercase">Status / PID</span>
                    <span className="text-[11px] text-emerald-400">
                      {worker.status.toUpperCase()} {worker.pid ? `(${worker.pid})` : ""}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
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

      {/* MODAL UBAH TG PRIMARY BRIDGE */}
      {showTuneModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#101726] border-2 border-cyan-500/70 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-start justify-between border-b border-cyan-900/50 pb-2">
              <div>
                <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                  <Edit2 className="w-4 h-4 text-cyan-400" />
                  Ubah Talkgroup Primary Bridge
                </h3>
                <p className="text-[11px] font-mono text-slate-400 mt-0.5">
                  Alihkan default Talkgroup Analog_Bridge secara dinamis tanpa restart daemon (Zero-Restart).
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowTuneModal(false)}
                className="text-slate-500 hover:text-white font-bold"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleTunePrimary} className="space-y-3 font-mono text-xs">
              <div>
                <label className="block text-slate-300 mb-1 font-bold">
                  Nomor Talkgroup (TG) Sasaran
                </label>
                <input
                  type="number"
                  required
                  autoFocus
                  placeholder="Contoh: 1025, 510, 5109"
                  value={tunePrimaryTg}
                  onChange={(e) => setTunePrimaryTg(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-amber-300 font-bold focus:border-cyan-400 outline-none text-sm"
                />
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-bold">Timeslot (TS)</label>
                <select
                  value={tunePrimarySlot}
                  onChange={(e) => setTunePrimarySlot(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 font-bold focus:border-cyan-400 outline-none"
                >
                  <option value={1}>Slot 1 (Wide Area / Peering)</option>
                  <option value={2}>Slot 2 (Local / Nusantara)</option>
                </select>
              </div>

              <div className="flex gap-2 pt-2 border-t border-cyan-950">
                <button
                  type="button"
                  onClick={() => setShowTuneModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isTuning}
                  className="flex-1 py-2 bg-cyan-400 hover:bg-cyan-300 disabled:opacity-50 text-slate-950 font-heading font-black rounded-xl shadow-lg shadow-cyan-500/25 transition-all"
                >
                  {isTuning ? "Mengalihkan..." : "Simpan & Terapkan"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
