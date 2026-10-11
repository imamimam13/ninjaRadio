"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { AudioVisualizer } from "@/components/AudioVisualizer";
import { 
  Radio, 
  Wifi, 
  Cpu, 
  Server, 
  Zap, 
  Layers, 
  Volume2, 
  Signal, 
  ShieldCheck, 
  Clock, 
  Search,
  SlidersHorizontal,
  Activity,
  Terminal,
  RefreshCw,
  Globe,
  Gauge
} from "lucide-react";

export default function DashboardPage() {
  const { lastHeard, activeTx, isConnected } = useLiveMonitor();
  const [filterMode, setFilterMode] = useState<string>("ALL");
  const [searchTerm, setSearchTerm] = useState<string>("");
  const [vizMode, setVizMode] = useState<"oscilloscope" | "smeter" | "bars">("oscilloscope");
  const [stats, setStats] = useState({
    server_callsign: "N1NJA",
    server_id: 5109999,
    uptime: "24h 12m 05s",
    active_transmissions: 0,
    connected_hotspots: 18,
    connected_peers: 5,
    active_bridges: 6,
    cpu_usage: 14.2,
    ram_usage: 32.8
  });

  useEffect(() => {
    fetch("/api/status")
      .then((res) => res.json())
      .then((data) => setStats(data))
      .catch(() => {});
  }, []);

  const filteredLastHeard = lastHeard.filter((item) => {
    const matchesMode = filterMode === "ALL" || item.mode.toUpperCase() === filterMode.toUpperCase();
    const matchesSearch =
      item.callsign.toLowerCase().includes(searchTerm.toLowerCase()) ||
      item.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      item.talkgroup.toString().includes(searchTerm);
    return matchesMode && matchesSearch;
  });

  return (
    <div className="min-h-screen pb-20 md:pb-8 flex flex-col bg-[#07090e] bg-grid-pattern">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-7xl w-full mx-auto px-3 sm:px-4 py-4 space-y-4">
        
        {/* Active Transmission Flight Deck Banner (Live RX Monitor) */}
        {activeTx ? (
          <div className="relative overflow-hidden bg-gradient-to-r from-emerald-950/90 via-[#0a121e] to-slate-950 border-2 border-emerald-500/80 rounded-2xl p-4 sm:p-6 shadow-[0_0_30px_rgba(16,185,129,0.25)] animate-pulse">
            <div className="absolute top-0 right-0 w-96 h-96 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
            
            <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-6 relative z-10">
              {/* Left Info */}
              <div className="flex items-center gap-4">
                <div className="w-16 h-16 rounded-2xl bg-emerald-500/20 border-2 border-emerald-400 flex items-center justify-center shadow-[0_0_20px_rgba(52,211,153,0.4)]">
                  <Radio className="w-8 h-8 text-emerald-400 animate-bounce" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="px-2.5 py-0.5 rounded bg-emerald-400 text-slate-950 font-heading font-black text-xs uppercase tracking-wider shadow-sm">
                      ● LIVE RX • TRANSMITTING
                    </span>
                    <span className="text-xs text-emerald-300 font-mono font-bold px-2 py-0.5 rounded bg-emerald-950/80 border border-emerald-800">
                      {activeTx.mode}
                    </span>
                    <span className="text-xs text-cyan-400 font-mono">
                      SLOT {activeTx.slot}
                    </span>
                  </div>
                  <div className="flex items-baseline gap-3 mt-1.5">
                    <h2 className="text-3xl sm:text-4xl font-heading font-black text-white tracking-wider">
                      {activeTx.callsign}
                    </h2>
                    <span className="text-base font-medium text-slate-300 hidden sm:inline">
                      {activeTx.name}
                    </span>
                  </div>
                  <div className="text-xs font-mono text-emerald-400/90 mt-1 flex items-center gap-3">
                    <span>DMR ID: {activeTx.dmr_id}</span>
                    <span>•</span>
                    <span>RSSI: <strong className="text-emerald-300 font-bold">{activeTx.rssi}</strong></span>
                    <span>•</span>
                    <span>LOSS: <strong className="text-emerald-300 font-bold">{activeTx.loss || "0.0%"}</strong></span>
                  </div>
                </div>
              </div>

              {/* Right Visualizer & TG Target */}
              <div className="flex flex-col sm:flex-row items-start sm:items-center gap-6 w-full lg:w-auto justify-between border-t lg:border-t-0 border-emerald-800/40 pt-3 lg:pt-0">
                <div className="text-left sm:text-right">
                  <div className="text-[10px] font-mono text-slate-400 uppercase tracking-widest font-bold">Target Route</div>
                  <div className="text-xl sm:text-2xl font-mono font-black text-cyan-300 glow-cyan">
                    TG {activeTx.talkgroup}
                  </div>
                  <div className="text-xs font-medium text-slate-300">
                    {activeTx.tg_name}
                  </div>
                </div>

                <div className="w-full sm:w-64 bg-slate-950/70 p-2.5 rounded-xl border border-emerald-800/60 shadow-inner">
                  <div className="flex items-center justify-between text-[10px] font-mono text-slate-400 mb-1.5">
                    <span>RF SPECTRUM</span>
                    <div className="flex gap-1">
                      <button 
                        onClick={() => setVizMode("oscilloscope")} 
                        className={`px-1.5 py-0.5 rounded text-[9px] ${vizMode === "oscilloscope" ? "bg-emerald-500 text-slate-950 font-bold" : "text-slate-400"}`}
                      >
                        OSC
                      </button>
                      <button 
                        onClick={() => setVizMode("smeter")} 
                        className={`px-1.5 py-0.5 rounded text-[9px] ${vizMode === "smeter" ? "bg-emerald-500 text-slate-950 font-bold" : "text-slate-400"}`}
                      >
                        S-MTR
                      </button>
                      <button 
                        onClick={() => setVizMode("bars")} 
                        className={`px-1.5 py-0.5 rounded text-[9px] ${vizMode === "bars" ? "bg-emerald-500 text-slate-950 font-bold" : "text-slate-400"}`}
                      >
                        VU
                      </button>
                    </div>
                  </div>
                  <AudioVisualizer 
                    isActive={true} 
                    mode={vizMode} 
                    color="bg-emerald-400" 
                    barsCount={16} 
                  />
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="bg-[#0b121e]/90 border border-cyan-900/40 rounded-2xl p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-lg">
            <div className="flex items-center gap-3 text-slate-300 text-xs sm:text-sm">
              <div className="relative flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500" />
              </div>
              <span className="font-mono text-xs text-slate-300">
                STANDBY RECEIVER • Memonitor seluruh Talkgroup & Jembatan Cross-Network...
              </span>
            </div>
            <div className="flex items-center gap-3 text-xs font-mono text-cyan-400">
              <span className="px-2 py-0.5 rounded bg-cyan-950/80 border border-cyan-800/80">
                MASTER: {stats.server_callsign} ({stats.server_id})
              </span>
            </div>
          </div>
        )}

        {/* Cockpit Avionics Telemetry Grid */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4">
          <div className="bg-[#0c1322] border border-cyan-900/40 rounded-2xl p-4 flex flex-col justify-between shadow-lg hover:border-cyan-500/50 transition-all">
            <div className="flex items-center justify-between text-slate-400 text-xs font-mono">
              <span className="font-bold">HOTSPOTS / MMDVM</span>
              <Wifi className="w-4 h-4 text-cyan-400" />
            </div>
            <div className="mt-3 flex items-baseline justify-between">
              <span className="text-3xl font-black text-white font-mono">{stats.connected_hotspots}</span>
              <span className="text-[11px] text-emerald-400 font-mono font-bold px-1.5 py-0.5 rounded bg-emerald-950/60 border border-emerald-800">
                ONLINE
              </span>
            </div>
            <div className="text-[10px] text-slate-500 font-mono mt-1">Pi-Star, OpenGD77, WPSD</div>
          </div>

          <div className="bg-[#0c1322] border border-cyan-900/40 rounded-2xl p-4 flex flex-col justify-between shadow-lg hover:border-cyan-500/50 transition-all">
            <div className="flex items-center justify-between text-slate-400 text-xs font-mono">
              <span className="font-bold">CROSS-BRIDGES</span>
              <Layers className="w-4 h-4 text-purple-400" />
            </div>
            <div className="mt-3 flex items-baseline justify-between">
              <span className="text-3xl font-black text-white font-mono">{stats.active_bridges}</span>
              <span className="text-[11px] text-purple-400 font-mono font-bold px-1.5 py-0.5 rounded bg-purple-950/60 border border-purple-800">
                ACTIVE
              </span>
            </div>
            <div className="text-[10px] text-slate-500 font-mono mt-1">YSF / P25 / ASL / CB / Mumble</div>
          </div>

          <div className="bg-[#0c1322] border border-cyan-900/40 rounded-2xl p-4 flex flex-col justify-between shadow-lg hover:border-cyan-500/50 transition-all">
            <div className="flex items-center justify-between text-slate-400 text-xs font-mono">
              <span className="font-bold">OPENBRIDGE PEERS</span>
              <Globe className="w-4 h-4 text-amber-400" />
            </div>
            <div className="mt-3 flex items-baseline justify-between">
              <span className="text-3xl font-black text-white font-mono">{stats.connected_peers}</span>
              <span className="text-[11px] text-amber-400 font-mono font-bold px-1.5 py-0.5 rounded bg-amber-950/60 border border-amber-800">
                LINKED
              </span>
            </div>
            <div className="text-[10px] text-slate-500 font-mono mt-1">FreeDMR & BrandMeister</div>
          </div>

          <div className="bg-[#0c1322] border border-cyan-900/40 rounded-2xl p-4 flex flex-col justify-between shadow-lg hover:border-cyan-500/50 transition-all">
            <div className="flex items-center justify-between text-slate-400 text-xs font-mono">
              <span className="font-bold">UPTIME & TELEMETRY</span>
              <Clock className="w-4 h-4 text-emerald-400" />
            </div>
            <div className="mt-3 flex items-baseline justify-between">
              <span className="text-xl font-black text-white font-mono truncate">{stats.uptime}</span>
            </div>
            <div className="text-[10px] text-slate-400 font-mono mt-1 flex items-center justify-between">
              <span>CPU: <strong className="text-cyan-400">{stats.cpu_usage}%</strong></span>
              <span>RAM: <strong className="text-cyan-400">{stats.ram_usage}%</strong></span>
            </div>
          </div>
        </div>

        {/* Live Last Heard Table & Cyberdeck Feed */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl overflow-hidden shadow-2xl">
          
          {/* Table Header Controls */}
          <div className="p-4 border-b border-cyan-900/40 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 bg-[#080d17]">
            <div>
              <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
                <Activity className="w-4 h-4 text-cyan-400" />
                LIVE LAST HEARD FEED
              </h3>
              <p className="text-xs font-mono text-slate-400 mt-0.5">
                Riwayat transmisi radio real-time (DMR, YSF, P25, CB, RAPI, Mumble & Analog)
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">
              {/* Search */}
              <div className="relative flex-1 sm:w-56">
                <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  placeholder="Cari Callsign / TG / ID..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full pl-9 pr-3 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white placeholder-slate-500 font-mono focus:outline-none focus:border-cyan-400"
                />
              </div>

              {/* Mode Filters */}
              <div className="flex items-center gap-1 bg-[#05080f] p-1 rounded-xl border border-cyan-900/60 text-xs font-mono">
                {["ALL", "DMR", "YSF", "P25", "ANALOG", "CB"].map((mode) => (
                  <button
                    key={mode}
                    onClick={() => setFilterMode(mode)}
                    className={`px-2 py-0.5 rounded-lg text-[10px] font-bold transition-all ${
                      filterMode === mode
                        ? "bg-cyan-400 text-slate-950 shadow-sm"
                        : "text-slate-400 hover:text-white"
                    }`}
                  >
                    {mode}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Table Body */}
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-cyan-900/40 bg-[#070c16] text-slate-400 font-mono text-[10px] uppercase tracking-wider">
                  <th className="py-3 px-3">WAKTU</th>
                  <th className="py-3 px-3">CALLSIGN</th>
                  <th className="py-3 px-3">NAMA OPERATOR</th>
                  <th className="py-3 px-3">DMR ID</th>
                  <th className="py-3 px-3">TARGET TG</th>
                  <th className="py-3 px-3">SLOT</th>
                  <th className="py-3 px-3">MODE</th>
                  <th className="py-3 px-3">DURASI</th>
                  <th className="py-3 px-3">SIGNAL / RSSI</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-cyan-950/60 font-mono">
                {filteredLastHeard.length === 0 ? (
                  <tr>
                    <td colSpan={9} className="text-center py-12 text-slate-500 font-mono text-xs">
                      Belum ada aktivitas transmisi yang cocok.
                    </td>
                  </tr>
                ) : (
                  filteredLastHeard.map((item, idx) => (
                    <tr
                      key={item.id || idx}
                      className="hover:bg-cyan-950/20 transition-colors group"
                    >
                      <td className="py-2.5 px-3 text-slate-400 text-[11px]">
                        {item.timestamp}
                      </td>
                      <td className="py-2.5 px-3 font-bold text-white tracking-wide">
                        <span className="text-cyan-400 group-hover:underline cursor-pointer font-black text-sm">
                          {item.callsign}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 text-slate-300 font-sans font-medium max-w-[150px] truncate">
                        {item.name || "-"}
                      </td>
                      <td className="py-2.5 px-3 text-slate-400">{item.dmr_id}</td>
                      <td className="py-2.5 px-3 font-bold text-slate-200">
                        <span className="px-2 py-0.5 rounded bg-slate-900 border border-slate-700 text-cyan-300">
                          TG {item.talkgroup}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 text-slate-400">TS{item.slot}</td>
                      <td className="py-2.5 px-3">
                        <span
                          className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                            item.mode === "DMR"
                              ? "bg-cyan-950 text-cyan-300 border border-cyan-800"
                              : item.mode === "YSF"
                              ? "bg-blue-950 text-blue-300 border border-blue-800"
                              : item.mode === "P25"
                              ? "bg-purple-950 text-purple-300 border border-purple-800"
                              : item.mode === "CB"
                              ? "bg-amber-950 text-amber-300 border border-amber-800"
                              : "bg-emerald-950 text-emerald-300 border border-emerald-800"
                          }`}
                        >
                          {item.mode}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 text-slate-300 font-bold">{item.duration}</td>
                      <td className="py-2.5 px-3 text-emerald-400 text-[11px] font-bold">
                        {item.rssi}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

      </main>
    </div>
  );
}
