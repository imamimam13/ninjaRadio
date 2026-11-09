"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import {
  Radio,
  RefreshCw,
  CheckCircle2,
  Layers,
  Copy,
  Check,
  Bot,
  Plus,
  Trash2,
  Terminal,
  Activity,
  Server
} from "lucide-react";

import { AdminGuard } from "@/components/AdminGuard";
import { getIsAdminUnlocked, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";

interface SvxChannel {
  id?: string;
  name: string;
  channel: string;
  tg: number;
  targetTg?: number;
  slot: number;
  description: string;
}

interface SvxBot {
  name: string;
  channel: string;
  tg: number;
  slot: number;
  connected?: boolean;
  active_talker?: string | null;
  echolink_enabled?: boolean;
  echolink_callsign?: string;
  status: string;
}

interface SvxTelemetry {
  online: boolean;
  server_host: string;
  server_port: number;
  auth_key: string;
  protocol: string;
  server_version: string;
  tcp_status: string;
  udp_status: string;
  connected_nodes: number;
  reflector_nodes: Record<string, any>;
  active_bots: SvxBot[];
  bridge_bot: {
    name: string;
    status: string;
    audio_transcoding: string;
    gateway_port: number;
  };
  channels: SvxChannel[];
}

export default function SvxLinkPage() {
  const { isConnected } = useLiveMonitor();
  const [telemetry, setTelemetry] = useState<SvxTelemetry | null>(null);
  const [channels, setChannels] = useState<SvxChannel[]>([]);
  const [bots, setBots] = useState<SvxBot[]>([]);
  const [logs, setLogs] = useState<string[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [copiedField, setCopiedField] = useState<string | null>(null);

  // Add Bot Form State
  const [newBotName, setNewBotName] = useState("");
  const [newBotRoom, setNewBotRoom] = useState("");
  const [newBotTg, setNewBotTg] = useState("510");
  const [newBotSlot, setNewBotSlot] = useState("2");
  const [echolinkEnabled, setEcholinkEnabled] = useState(false);
  const [echolinkCallsign, setEcholinkCallsign] = useState("");
  const [echolinkPassword, setEcholinkPassword] = useState("");
  const [savingBot, setSavingBot] = useState(false);

  const fetchAll = async () => {
    try {
      const [telRes, botRes, logRes] = await Promise.all([
        fetch("/api/svxlink/telemetry"),
        fetch("/api/svxlink/bots"),
        fetch("/api/svxlink/logs")
      ]);
      if (telRes.ok) {
        const tData = await telRes.json();
        setTelemetry(tData);
        if (tData.channels) setChannels(tData.channels);
      }
      if (botRes.ok) {
        const bData = await botRes.json();
        if (bData.bots) setBots(bData.bots);
      }
      if (logRes.ok) {
        const lData = await logRes.json();
        if (lData.recent_logs) setLogs(lData.recent_logs.slice(-30));
      }
    } catch (err) {
      console.error("Failed to fetch SvxLink data:", err);
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => {
    const checkAndFetch = () => {
      if (getIsAdminUnlocked()) {
        fetchAll();
      }
    };
    checkAndFetch();
    const interval = setInterval(checkAndFetch, 3000);
    window.addEventListener(ADMIN_AUTH_EVENT, checkAndFetch);
    return () => {
      clearInterval(interval);
      window.removeEventListener(ADMIN_AUTH_EVENT, checkAndFetch);
    };
  }, []);

  const handleManualRefresh = () => {
    if (!getIsAdminUnlocked()) return;
    setRefreshing(true);
    fetchAll();
  };

  const handleAddBot = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newBotName.trim() || !newBotRoom.trim()) return;
    setSavingBot(true);
    try {
      const res = await fetch("/api/svxlink/bots", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: newBotName.trim().toUpperCase(),
          channel: newBotRoom.trim(),
          tg: parseInt(newBotTg, 10) || 510,
          slot: parseInt(newBotSlot, 10) || 2,
          echolink_enabled: echolinkEnabled,
          echolink_callsign: echolinkCallsign.trim().toUpperCase(),
          echolink_password: echolinkPassword.trim()
        })
      });
      if (res.ok) {
        setNewBotName("");
        setNewBotRoom("");
        fetchAll();
      }
    } catch (err) {
      console.error("Failed to add SvxLink bot:", err);
    } finally {
      setSavingBot(false);
    }
  };

  const handleDeleteBot = async (botName: string) => {
    try {
      const res = await fetch(`/api/svxlink/bots/${encodeURIComponent(botName)}`, {
        method: "DELETE"
      });
      if (res.ok) {
        fetchAll();
      }
    } catch (err) {
      console.error("Failed to delete SvxLink bot:", err);
    }
  };

  const copyToClipboard = (text: string, field: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(field);
    setTimeout(() => setCopiedField(null), 2000);
  };

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-20 md:pb-8 selection:bg-cyan-500 selection:text-black">
      <Navbar isConnected={isConnected} />

      <AdminGuard
        title="SVXLINK & SVXREFLECTOR MULTI-TG GATEWAY"
        description="Multi-TG Analog FM, SvxReflector, dan EchoLink Gateway dengan Direct DMR Callsign Carry-Over."
        tag="SVXREFLECTOR CORE"
      >
        <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
          {/* Header Section */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0b121e]/80 backdrop-blur-md p-5 rounded-2xl border border-cyan-900/30 shadow-xl">
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-emerald-500 to-cyan-400 p-[1.5px] shadow-[0_0_20px_rgba(16,185,129,0.3)]">
                <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center">
                  <Radio className="w-6 h-6 text-emerald-400" />
                </div>
              </div>
              <div>
                <div className="flex items-center gap-2.5">
                  <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-white">
                    SVXREFLECTOR & ECHOLINK MULTI-TG
                  </h1>
                  <span className="text-[11px] font-mono px-2 py-0.5 rounded-full font-bold bg-emerald-950/80 text-emerald-400 border border-emerald-800">
                    PORT 5300
                  </span>
                </div>
                <p className="text-xs sm:text-sm text-slate-400 font-mono mt-0.5">
                  Multi-TG Analog RoIP Switchboard • Direct DMR Vocoder (Zero USRP) • Callsign Carry-Over
                </p>
              </div>
            </div>

            <div className="flex items-center gap-3 self-end md:self-center">
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-slate-900/80 border border-slate-800 text-xs font-mono">
                <span
                  className={`w-2.5 h-2.5 rounded-full ${
                    telemetry?.online ? "bg-emerald-400 shadow-[0_0_8px_#34d399] animate-pulse" : "bg-rose-500"
                  }`}
                />
                <span className="text-slate-300 font-bold">
                  {telemetry?.online ? "SVXREFLECTOR: ONLINE" : "SVXREFLECTOR: STANDBY"}
                </span>
              </div>

              <button
                onClick={handleManualRefresh}
                disabled={refreshing}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-cyan-950/60 hover:bg-cyan-900/50 text-cyan-400 border border-cyan-800/60 text-xs font-mono font-bold transition-all disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? "animate-spin" : ""}`} />
                <span>SYNC</span>
              </button>
            </div>
          </div>

          {/* Server & Bridge Status Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* SvxReflector Server */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Server className="w-5 h-5 text-emerald-400" />
                  <h2 className="font-heading font-black text-white text-base">SVXREFLECTOR CONFERENCE CORE</h2>
                </div>
                <span
                  className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                    telemetry?.online
                      ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                      : "bg-rose-950 text-rose-400 border border-rose-800"
                  }`}
                >
                  {telemetry?.online ? "SERVER RUNNING" : "STANDBY"}
                </span>
              </div>

              <p className="text-xs text-slate-400 font-mono">
                Central Multi-TG audio matrix allowing external SvxLink repeaters, simplex nodes, and EchoLink stations to link into any DMR Talkgroup.
              </p>

              <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-2 text-slate-300">
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">TCP Control & Auth:</span>
                  <span className="text-emerald-400 font-bold flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    PORT {telemetry?.server_port || 5300} ({telemetry?.tcp_status || "OPEN"})
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">UDP Opus Voice Stream:</span>
                  <span className="text-emerald-400 font-bold flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    PORT {telemetry?.server_port || 5300} ({telemetry?.udp_status || "ACTIVE"})
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">Active Reflector Nodes:</span>
                  <span className="text-cyan-400 font-bold">
                    {telemetry?.connected_nodes || bots.length} Nodes Linked
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">Shared AUTH_KEY:</span>
                  <span className="text-amber-400 font-bold">{telemetry?.auth_key || "ninjaradio_svx"}</span>
                </div>
              </div>
            </div>

            {/* Direct DMR Bridge Pool */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Activity className="w-5 h-5 text-cyan-400" />
                  <h2 className="font-heading font-black text-white text-base">DIRECT DMR MULTI-TG BOT POOL</h2>
                </div>
                <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                  DIRECT VOCODER
                </span>
              </div>

              <p className="text-xs text-slate-400 font-mono">
                Streams 16kHz UDP PCM from each SvxLink bot directly to Core DirectDmrEngine (UDP 32100) with dynamic Callsign Carry-Over.
              </p>

              <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-2 text-slate-300">
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">DMR Ingress Pipeline:</span>
                  <span className="text-cyan-300 font-bold">DirectDmrEngine (Zero USRP / Zero AB)</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">Audio Transcoder:</span>
                  <span className="text-emerald-400 font-bold">
                    {telemetry?.bridge_bot?.audio_transcoding || "PCM 8kHz <-> PCM 16kHz / Opus"}
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">Callsign Carry-Over:</span>
                  <span className="text-purple-400 font-bold">Tcl Event Hook -> Callbook DB Lookup</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">Internal DMR RX Port:</span>
                  <span className="text-slate-300 font-bold">UDP Port 12348 (Multi-TG Demux)</span>
                </div>
              </div>
            </div>
          </div>

          {/* SvxPortal-Style Live Reflector Node Matrix */}
          <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 p-5 shadow-xl space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Radio className="w-5 h-5 text-cyan-400" />
                <div>
                  <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                    SVXPORTAL LIVE REFLECTOR MATRIX (CONNECTED STATIONS & NODES)
                  </h2>
                  <p className="text-[11px] text-slate-400 font-mono">
                    Matriks real-time seluruh node SvxLink & Gateway yang sedang terhubung di SvxReflector Server.
                  </p>
                </div>
              </div>
              <span className="px-2.5 py-1 rounded-full text-[11px] font-mono font-bold bg-cyan-950/80 text-cyan-300 border border-cyan-800">
                {telemetry?.reflector_nodes ? Object.keys(telemetry.reflector_nodes).length : bots.length} ACTIVE NODES
              </span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 font-mono">
              {(telemetry?.reflector_nodes && Object.keys(telemetry.reflector_nodes).length > 0
                ? Object.entries(telemetry.reflector_nodes)
                : bots.map((b) => [
                    b.name,
                    {
                      callsign: b.name,
                      tg: b.tg,
                      monitoredTGs: [b.tg],
                      isTalker: !!b.active_talker,
                      nodeLocation: b.channel,
                      sw: "NinjaRadio-DirectDMR"
                    }
                  ])
              ).map(([nodeCall, info]: [string, any]) => {
                const isTalking = info?.isTalker;
                const tgNum = info?.tg || 0;
                const monitored = Array.isArray(info?.monitoredTGs) ? info.monitoredTGs.join(", ") : tgNum;
                return (
                  <div
                    key={nodeCall}
                    className={`p-3.5 rounded-xl border transition-all ${
                      isTalking
                        ? "bg-rose-950/40 border-rose-500 shadow-[0_0_15px_rgba(244,63,94,0.3)]"
                        : "bg-slate-950/70 border-slate-800/80 hover:border-cyan-800/60"
                    }`}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-black text-sm text-emerald-300 flex items-center gap-1.5">
                        <span
                          className={`w-2 h-2 rounded-full ${
                            isTalking ? "bg-rose-500 animate-ping" : "bg-emerald-400"
                          }`}
                        />
                        {nodeCall}
                      </span>
                      <span
                        className={`text-[10px] px-2 py-0.5 rounded font-bold ${
                          isTalking
                            ? "bg-rose-600 text-white"
                            : "bg-amber-950/80 text-amber-400 border border-amber-800/60"
                        }`}
                      >
                        {isTalking ? "TX ACTIVE" : `TG #${tgNum}`}
                      </span>
                    </div>
                    <div className="text-[11px] text-slate-400 space-y-1">
                      <div className="truncate text-slate-300">{info?.nodeLocation || "SvxLink Node"}</div>
                      <div className="flex justify-between text-[10px] text-slate-500 pt-1 border-t border-slate-800/60">
                        <span>Monitor: TG {monitored}</span>
                        <span className="text-cyan-400">{info?.sw || "SvxLink"}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Add / Station New SvxLink Multi-TG Bot */}
          <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 p-5 shadow-xl space-y-4">
            <div className="flex items-center gap-2">
              <Plus className="w-5 h-5 text-emerald-400" />
              <div>
                <h2 className="font-heading font-bold text-base text-white">
                  TAMBAH / UPDATE BOT SVXLINK (MULTI-TG SWITCHBOARD)
                </h2>
                <p className="text-[11px] text-slate-400 font-mono">
                  Setiap bot yang ditambahkan akan memonitor 1 Talkgroup di SvxReflector dan menjembataninya secara bi-direksional ke DMR.
                </p>
              </div>
            </div>

            <form onSubmit={handleAddBot} className="grid grid-cols-1 sm:grid-cols-5 gap-3 text-xs font-mono">
              <div>
                <label className="text-slate-400 block mb-1">Bot Callsign / ID</label>
                <input
                  type="text"
                  placeholder="e.g. SVX-TG510"
                  value={newBotName}
                  onChange={(e) => setNewBotName(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 text-white focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="text-slate-400 block mb-1">Nama Room / Label</label>
                <input
                  type="text"
                  placeholder="e.g. SVX-Nasional"
                  value={newBotRoom}
                  onChange={(e) => setNewBotRoom(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 text-white focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="text-slate-400 block mb-1">Target Talkgroup (TG)</label>
                <input
                  type="number"
                  placeholder="510"
                  value={newBotTg}
                  onChange={(e) => setNewBotTg(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 text-cyan-400 font-bold focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="text-slate-400 block mb-1">DMR TimeSlot</label>
                <select
                  value={newBotSlot}
                  onChange={(e) => setNewBotSlot(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 text-white focus:border-cyan-500 focus:outline-none"
                >
                  <option value="1">Slot 1</option>
                  <option value="2">Slot 2</option>
                </select>
              </div>
              <div className="flex items-end">
                <button
                  type="submit"
                  disabled={savingBot}
                  className="w-full py-2 px-4 rounded-xl bg-gradient-to-r from-emerald-600 to-cyan-600 hover:from-emerald-500 hover:to-cyan-500 text-white font-bold shadow-lg transition-all disabled:opacity-50"
                >
                  {savingBot ? "MENYIMPAN..." : "+ DEPLOY BOT"}
                </button>
              </div>
            </form>

            {/* Optional EchoLink Toggle */}
            <div className="pt-2 border-t border-slate-800/70 flex flex-col sm:flex-row sm:items-center gap-4 text-xs font-mono">
              <label className="flex items-center gap-2 cursor-pointer text-slate-300">
                <input
                  type="checkbox"
                  checked={echolinkEnabled}
                  onChange={(e) => setEcholinkEnabled(e.target.checked)}
                  className="rounded border-slate-700 bg-slate-900 text-emerald-500"
                />
                <span>Aktifkan Modul EchoLink pada Bot ini (Opsional)</span>
              </label>
              {echolinkEnabled && (
                <div className="flex flex-wrap items-center gap-2 flex-1">
                  <input
                    type="text"
                    placeholder="EchoLink Callsign (e.g. YB0ABC-L)"
                    value={echolinkCallsign}
                    onChange={(e) => setEcholinkCallsign(e.target.value)}
                    className="px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-white"
                  />
                  <input
                    type="password"
                    placeholder="EchoLink Password"
                    value={echolinkPassword}
                    onChange={(e) => setEcholinkPassword(e.target.value)}
                    className="px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-white"
                  />
                </div>
              )}
            </div>
          </div>

          {/* Active SvxLink Bot Pool Table */}
          <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
            <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Bot className="w-5 h-5 text-emerald-400" />
                <div>
                  <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                    SVXLINK MULTI-TG BOT POOL (ACTIVE GATEWAYS)
                  </h2>
                  <p className="text-[11px] text-slate-400 font-mono">
                    Daftar bot SvxLink yang menjembatani Talkgroup SvxReflector dengan Direct DMR Core.
                  </p>
                </div>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse text-xs font-mono">
                <thead>
                  <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                    <th className="p-3 font-bold">BOT CALLSIGN</th>
                    <th className="p-3 font-bold">REFLECTOR ROOM</th>
                    <th className="p-3 font-bold">MAPPED TG (SVX & DMR)</th>
                    <th className="p-3 font-bold">TIMESLOT</th>
                    <th className="p-3 font-bold">ACTIVE TALKER</th>
                    <th className="p-3 font-bold text-center">STATUS</th>
                    <th className="p-3 font-bold text-right">AKSI</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {bots && bots.length > 0 ? (
                    bots.map((bot, idx) => (
                      <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                        <td className="p-3 font-bold text-white flex items-center gap-2">
                          <Bot className="w-4 h-4 text-emerald-400" />
                          <span className="text-emerald-300 font-black text-sm">{bot.name}</span>
                        </td>
                        <td className="p-3">
                          <span className="px-2.5 py-1 rounded-lg bg-cyan-950/60 text-cyan-300 border border-cyan-800/70 font-bold inline-flex items-center gap-1.5">
                            <Layers className="w-3 h-3 text-cyan-400" />
                            {bot.channel}
                          </span>
                        </td>
                        <td className="p-3 font-bold text-amber-400 text-sm">TG {bot.tg}</td>
                        <td className="p-3 text-slate-300">Slot {bot.slot}</td>
                        <td className="p-3">
                          {bot.active_talker ? (
                            <span className="px-2 py-0.5 rounded bg-rose-950 text-rose-400 border border-rose-800 font-bold animate-pulse">
                              🎙️ {bot.active_talker}
                            </span>
                          ) : (
                            <span className="text-slate-500">STANDBY</span>
                          )}
                        </td>
                        <td className="p-3 text-center">
                          <span
                            className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                              bot.connected
                                ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                                : "bg-cyan-950 text-cyan-400 border border-cyan-800"
                            }`}
                          >
                            {bot.status || "STATIONED"}
                          </span>
                        </td>
                        <td className="p-3 text-right">
                          <button
                            onClick={() => handleDeleteBot(bot.name)}
                            className="p-1.5 rounded-lg bg-rose-950/60 hover:bg-rose-900/70 text-rose-400 border border-rose-800/60 transition-colors"
                            title="Hapus Bot"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={7} className="p-8 text-center text-slate-500">
                        Belum ada bot SvxLink aktif. Tambahkan bot di atas.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* Connection Guide for External SvxLink Repeaters / Analog Nodes */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
            <div className="flex items-center gap-2">
              <Terminal className="w-5 h-5 text-emerald-400" />
              <h2 className="font-heading font-bold text-base text-white">
                CARA CONNECT NODE SVXLINK / REPEATER ANALOG EKSTERNAL KE SERVER INI
              </h2>
            </div>

            <p className="text-xs text-slate-400 font-mono">
              Masukkan konfigurasi berikut pada file <code className="text-emerald-400">/etc/svxlink/svxlink.conf</code> di Raspberry Pi / Repeater Analog Anda untuk terhubung ke Multi-TG Reflector NinjaRadio:
            </p>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs font-mono">
              <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
                <span className="text-slate-500 block">Reflector Host / IP:</span>
                <div className="flex items-center justify-between">
                  <span className="text-white font-bold">
                    {typeof window !== "undefined" ? window.location.hostname : "43.173.13.117"}
                  </span>
                  <button
                    onClick={() =>
                      copyToClipboard(
                        typeof window !== "undefined" ? window.location.hostname : "43.173.13.117",
                        "host"
                      )
                    }
                    className="text-slate-400 hover:text-cyan-400"
                  >
                    {copiedField === "host" ? (
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                    ) : (
                      <Copy className="w-3.5 h-3.5" />
                    )}
                  </button>
                </div>
              </div>

              <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
                <span className="text-slate-500 block">Port (TCP & UDP):</span>
                <div className="flex items-center justify-between">
                  <span className="text-cyan-400 font-bold">{telemetry?.server_port || 5300}</span>
                  <button
                    onClick={() => copyToClipboard(String(telemetry?.server_port || 5300), "port")}
                    className="text-slate-400 hover:text-cyan-400"
                  >
                    {copiedField === "port" ? (
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                    ) : (
                      <Copy className="w-3.5 h-3.5" />
                    )}
                  </button>
                </div>
              </div>

              <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
                <span className="text-slate-500 block">AUTH_KEY:</span>
                <div className="flex items-center justify-between">
                  <span className="text-emerald-400 font-bold">{telemetry?.auth_key || "ninjaradio_svx"}</span>
                  <button
                    onClick={() => copyToClipboard(telemetry?.auth_key || "ninjaradio_svx", "auth")}
                    className="text-slate-400 hover:text-cyan-400"
                  >
                    {copiedField === "auth" ? (
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                    ) : (
                      <Copy className="w-3.5 h-3.5" />
                    )}
                  </button>
                </div>
              </div>
            </div>

            {logs.length > 0 && (
              <div className="pt-3 border-t border-slate-800">
                <span className="text-[11px] font-mono text-slate-400 block mb-2">LIVE SVXLINK BRIDGE LOGS:</span>
                <div className="bg-slate-950/90 border border-slate-800 rounded-xl p-3 max-h-48 overflow-y-auto text-[11px] font-mono text-slate-300 space-y-1">
                  {logs.map((line, i) => (
                    <div key={i} className="truncate">
                      {line}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </main>
      </AdminGuard>
    </div>
  );
}
