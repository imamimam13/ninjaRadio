"use client";

import React, { useState, useEffect, useRef } from "react";
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
  Server,
  Mic,
  Smartphone,
  Headphones,
  Globe,
  Wifi,
  LayoutGrid,
  ListFilter,
  Pencil,
  ExternalLink,
  X,
  Sparkles,
  HelpCircle,
  Sliders
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

interface UnifiedNode {
  id: string;
  callsign: string;
  category: "SvxLink" | "DMR" | "DVSwitch" | "Mumble" | "Hotspot";
  sourceType: "Local" | "Network" | "Ext SVX";
  bridgeLabel: string;
  hostLabel: string;
  routeFrom: string;
  routeTo: string;
  tg: number;
  slot: number;
  monitoredTGs: number[];
  isTalking: boolean;
  talkerCallsign: string;
  talkingDurationSec: number;
  connected: boolean;
  sysop: string;
  sw: string;
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
  unified_nodes?: UnifiedNode[];
  active_streams_by_tg?: Record<string, any>;
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

  // Node Matrix Filters & View Mode
  const [sourceFilter, setSourceFilter] = useState<string>("All");
  const [typeFilter, setTypeFilter] = useState<string>("All");
  const [viewMode, setViewMode] = useState<"detailed" | "compact">("detailed");

  // Add / Edit Bot Form State
  const [editingBotName, setEditingBotName] = useState<string | null>(null);
  const botFormRef = useRef<HTMLDivElement>(null);
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

  const handleEditBot = (bot: SvxBot) => {
    setEditingBotName(bot.name);
    setNewBotName(bot.name);
    setNewBotRoom(bot.channel);
    setNewBotTg(bot.tg.toString());
    setNewBotSlot(bot.slot.toString());
    setEcholinkEnabled(Boolean(bot.echolink_enabled));
    setEcholinkCallsign(bot.echolink_callsign || "");
    setEcholinkPassword("");
    botFormRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  const handleCancelEdit = () => {
    setEditingBotName(null);
    setNewBotName("");
    setNewBotRoom("");
    setNewBotTg("510");
    setNewBotSlot("2");
    setEcholinkEnabled(false);
    setEcholinkCallsign("");
    setEcholinkPassword("");
  };

  const handleApplyPreset = (name: string, room: string, tg: number, slot: number) => {
    setEditingBotName(null);
    setNewBotName(name);
    setNewBotRoom(room);
    setNewBotTg(tg.toString());
    setNewBotSlot(slot.toString());
    botFormRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
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
          original_name: editingBotName,
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
        setEditingBotName(null);
        setNewBotName("");
        setNewBotRoom("");
        setNewBotTg("510");
        setNewBotSlot("2");
        setEcholinkEnabled(false);
        setEcholinkCallsign("");
        setEcholinkPassword("");
        fetchAll();
      }
    } catch (err) {
      console.error("Failed to add/update SvxLink bot:", err);
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

          {/* SvxReflector Tactical Unified Node Matrix (SVXReflector Dashboard Style in NinjaRadio UI) */}
          {(() => {
            const fallbackNodes: UnifiedNode[] = bots.map((b) => ({
              id: `svx_${b.name}`,
              callsign: b.name,
              category: "SvxLink",
              sourceType: "Local",
              bridgeLabel: `SvxReflector · ${b.channel}`,
              hostLabel: `svxreflector:${telemetry?.server_port || 5300}`,
              routeFrom: `SVX TG ${b.tg}`,
              routeTo: `DMR TG ${b.tg} TS${b.slot}`,
              tg: b.tg,
              slot: b.slot,
              monitoredTGs: [b.tg],
              isTalking: !!b.active_talker,
              talkerCallsign: b.active_talker || "",
              talkingDurationSec: b.active_talker ? 3 : 0,
              connected: !!b.connected,
              sysop: "NINJARADIO · SVX MULTI-TG CORE",
              sw: "SvxLink_Bridge 1.9 (DirectDMR)"
            }));

            const allNodes: UnifiedNode[] =
              telemetry?.unified_nodes && telemetry.unified_nodes.length > 0
                ? telemetry.unified_nodes
                : fallbackNodes;

            const filteredNodes = allNodes.filter((n) => {
              if (sourceFilter !== "All" && n.sourceType !== sourceFilter) return false;
              if (typeFilter !== "All" && n.category !== typeFilter) return false;
              return true;
            });

            const totalCount = allNodes.length;
            const activeCount = allNodes.filter((n) => n.connected).length;
            const talkingCount = allNodes.filter((n) => n.isTalking).length;

            const getCategoryIcon = (cat: string, talking: boolean) => {
              const cls = `w-4 h-4 ${talking ? "text-emerald-400 animate-pulse" : "text-cyan-400"}`;
              if (cat === "DVSwitch") return <Smartphone className={cls} />;
              if (cat === "Mumble") return <Headphones className={cls} />;
              if (cat === "DMR") return <Globe className={cls} />;
              if (cat === "Hotspot") return <Wifi className={cls} />;
              return <Radio className={cls} />;
            };

            return (
              <div className="bg-[#0b121e]/90 rounded-2xl border border-cyan-900/40 p-5 shadow-2xl space-y-4">
                {/* Top Header Bar with Live Counters */}
                <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-3 border-b border-slate-800/80">
                  <div className="flex flex-wrap items-center gap-3">
                    <div className="flex items-center gap-2">
                      <Radio className="w-5 h-5 text-emerald-400" />
                      <h2 className="font-heading font-black text-base sm:text-lg text-white tracking-wide">
                        SVXREFLECTOR <span className="text-cyan-400">NINJARADIO</span>
                      </h2>
                    </div>

                    <div className="flex flex-wrap items-center gap-2 text-xs font-mono">
                      <span className="px-2.5 py-1 rounded-lg bg-slate-900/90 border border-slate-800 text-slate-300 font-bold">
                        📡 <strong className="text-white">{totalCount}</strong> Nodes
                      </span>
                      <span className="px-2.5 py-1 rounded-lg bg-cyan-950/60 border border-cyan-800/60 text-cyan-300 font-bold">
                        ⚡ <strong className="text-cyan-200">{activeCount}</strong> Active
                      </span>
                      <span
                        className={`px-2.5 py-1 rounded-lg border font-bold ${
                          talkingCount > 0
                            ? "bg-emerald-950/90 border-emerald-500 text-emerald-300 shadow-[0_0_12px_rgba(16,185,129,0.3)] animate-pulse"
                            : "bg-slate-900/90 border-slate-800 text-slate-400"
                        }`}
                      >
                        🎙️ <strong className="text-emerald-400">{talkingCount}</strong> Talking
                      </span>
                    </div>
                  </div>

                  {/* View Mode Toggle */}
                  <div className="flex items-center gap-1.5 self-start lg:self-auto bg-slate-950/90 p-1 rounded-xl border border-slate-800 text-xs font-mono">
                    <button
                      onClick={() => setViewMode("detailed")}
                      className={`px-3 py-1 rounded-lg font-bold flex items-center gap-1.5 transition-all ${
                        viewMode === "detailed"
                          ? "bg-cyan-950 text-cyan-300 border border-cyan-700/70 shadow"
                          : "text-slate-400 hover:text-slate-200"
                      }`}
                    >
                      <LayoutGrid className="w-3.5 h-3.5" />
                      Detailed
                    </button>
                    <button
                      onClick={() => setViewMode("compact")}
                      className={`px-3 py-1 rounded-lg font-bold flex items-center gap-1.5 transition-all ${
                        viewMode === "compact"
                          ? "bg-cyan-950 text-cyan-300 border border-cyan-700/70 shadow"
                          : "text-slate-400 hover:text-slate-200"
                      }`}
                    >
                      <ListFilter className="w-3.5 h-3.5" />
                      Compact
                    </button>
                  </div>
                </div>

                {/* Filter Bar: SOURCE & TYPE (SVXReflector Dashboard Style) */}
                <div className="flex flex-col md:flex-row md:items-center gap-3 text-xs font-mono bg-slate-950/60 p-3 rounded-xl border border-slate-800/80">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mr-1">
                      SOURCE:
                    </span>
                    {["All", "Local", "Network", "Ext SVX"].map((src) => (
                      <button
                        key={src}
                        onClick={() => setSourceFilter(src)}
                        className={`px-2.5 py-1 rounded-lg font-bold transition-all ${
                          sourceFilter === src
                            ? "bg-cyan-600/20 text-cyan-300 border border-cyan-500/60 shadow-[0_0_10px_rgba(6,182,212,0.2)]"
                            : "bg-slate-900/70 text-slate-400 border border-slate-800 hover:text-slate-200"
                        }`}
                      >
                        {src}
                      </button>
                    ))}
                  </div>

                  <div className="hidden md:block w-[1px] h-5 bg-slate-800 mx-1" />

                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mr-1">
                      TYPE:
                    </span>
                    {["All", "SvxLink", "DMR", "DVSwitch", "Mumble", "Hotspot"].map((t) => (
                      <button
                        key={t}
                        onClick={() => setTypeFilter(t)}
                        className={`px-2.5 py-1 rounded-lg font-bold transition-all ${
                          typeFilter === t
                            ? "bg-emerald-600/20 text-emerald-300 border border-emerald-500/60 shadow-[0_0_10px_rgba(16,185,129,0.2)]"
                            : "bg-slate-900/70 text-slate-400 border border-slate-800 hover:text-slate-200"
                        }`}
                      >
                        {t}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Node Cards Grid */}
                <div
                  className={`grid gap-4 font-mono ${
                    viewMode === "detailed"
                      ? "grid-cols-1 md:grid-cols-2 lg:grid-cols-3"
                      : "grid-cols-1 sm:grid-cols-2 lg:grid-cols-4"
                  }`}
                >
                  {filteredNodes.map((node) => {
                    const isTalking = node.isTalking;
                    return (
                      <div
                        key={node.id}
                        className={`rounded-xl border transition-all duration-200 flex flex-col justify-between overflow-hidden ${
                          isTalking
                            ? "bg-[#091f1a]/90 border-emerald-400 shadow-[0_0_24px_rgba(16,185,129,0.28)]"
                            : "bg-[#0a101d]/90 border-slate-800/90 hover:border-cyan-700/60 shadow-lg"
                        }`}
                      >
                        {/* Card Top Header */}
                        <div className="p-3.5 space-y-2.5">
                          <div className="flex items-center justify-between gap-2">
                            <div className="flex items-center gap-2 min-w-0">
                              <div
                                className={`w-7 h-7 rounded-lg flex items-center justify-center border ${
                                  isTalking
                                    ? "bg-emerald-950 border-emerald-500/60"
                                    : "bg-slate-900 border-slate-800"
                                }`}
                              >
                                {isTalking ? (
                                  <Mic className="w-4 h-4 text-emerald-400 animate-bounce" />
                                ) : (
                                  getCategoryIcon(node.category, false)
                                )}
                              </div>
                              <span className="font-black text-sm sm:text-base text-white tracking-wide truncate">
                                {node.callsign}
                              </span>
                            </div>

                            <span
                              className={`text-[11px] px-2.5 py-0.5 rounded-md font-black tracking-wide shrink-0 ${
                                isTalking
                                  ? "bg-emerald-500 text-slate-950 shadow-[0_0_12px_rgba(16,185,129,0.6)]"
                                  : "bg-blue-600/90 text-white border border-blue-400/40"
                              }`}
                            >
                              {isTalking ? `TALKING · TG ${node.tg}` : `TG ${node.tg}`}
                            </span>
                          </div>

                          {/* Active Transmitting Banner */}
                          {isTalking && (
                            <div className="flex items-center justify-between px-3 py-1.5 rounded-lg bg-emerald-500/15 border border-emerald-500/40 text-emerald-300 text-xs">
                              <span className="font-bold truncate flex items-center gap-1.5">
                                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                                TRANSMITTING · {node.talkerCallsign || `TG ${node.tg}`}
                              </span>
                              <span className="text-[11px] font-mono text-emerald-200 shrink-0 ml-2">
                                {node.talkingDurationSec || 1}s
                              </span>
                            </div>
                          )}

                          {/* Detailed Metadata Rows */}
                          {viewMode === "detailed" && (
                            <div className="space-y-2 pt-1 text-xs">
                              {/* Bridge Type & Location */}
                              <div className="flex items-center gap-2 text-slate-300">
                                <span className="text-cyan-400 font-bold">(({node.category}))</span>
                                <span className="truncate text-slate-400">{node.bridgeLabel}</span>
                              </div>

                              {/* Route Pill: host [RouteFrom ⇆ RouteTo] */}
                              <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
                                <span className="text-slate-400 truncate max-w-[130px]">{node.hostLabel}</span>
                                <span className="px-2 py-0.5 rounded bg-slate-900/90 border border-slate-700/80 text-slate-200 font-semibold">
                                  {node.routeFrom} <span className="text-cyan-400">⇆</span> {node.routeTo}
                                </span>
                              </div>

                              {/* Monitored TGs Pills */}
                              <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
                                <span className="text-slate-500">TGs:</span>
                                {(node.monitoredTGs && node.monitoredTGs.length > 0
                                  ? node.monitoredTGs
                                  : [node.tg]
                                ).map((t) => (
                                  <span
                                    key={t}
                                    className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                                      t === node.tg && isTalking
                                        ? "bg-emerald-500/20 text-emerald-300 border border-emerald-500/50"
                                        : "bg-blue-950/70 text-blue-300 border border-blue-800/60"
                                    }`}
                                  >
                                    {t}
                                  </span>
                                ))}
                              </div>

                              {/* Sysop / Network Info */}
                              <div className="text-[11px] text-slate-400 truncate pt-0.5">
                                👤 {node.sysop}
                              </div>
                            </div>
                          )}
                        </div>

                        {/* Card Footer */}
                        <div className="px-3.5 py-2 bg-slate-950/80 border-t border-slate-800/80 flex items-center justify-between text-[11px] text-slate-400">
                          <span className="truncate">{node.sw}</span>
                          <div className="flex items-center gap-2">
                            {node.category === "SvxLink" && (() => {
                              const b = bots.find(item => item.name.toUpperCase() === node.callsign.toUpperCase());
                              if (b) {
                                return (
                                  <div className="flex items-center gap-1">
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        handleEditBot(b);
                                      }}
                                      className="p-1 rounded bg-slate-800/90 hover:bg-cyan-900/70 text-cyan-400 border border-slate-700 hover:border-cyan-500 transition-colors"
                                      title={`Edit Bot ${b.name}`}
                                    >
                                      <Pencil className="w-3 h-3" />
                                    </button>
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        if (confirm(`Hapus bot ${b.name}?`)) {
                                          handleDeleteBot(b.name);
                                        }
                                      }}
                                      className="p-1 rounded bg-slate-800/90 hover:bg-rose-900/70 text-rose-400 border border-slate-700 hover:border-rose-500 transition-colors"
                                      title={`Hapus Bot ${b.name}`}
                                    >
                                      <Trash2 className="w-3 h-3" />
                                    </button>
                                  </div>
                                );
                              }
                              return null;
                            })()}
                            {node.category === "DMR" && (
                              <a
                                href="/settings"
                                className="px-1.5 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-[10px] text-cyan-300 border border-slate-700 flex items-center gap-1"
                                title="Edit Peering DMR di Settings"
                              >
                                <span>Peering</span>
                                <ExternalLink className="w-2.5 h-2.5" />
                              </a>
                            )}
                            <span className="flex items-center gap-1.5 text-[10px] font-bold">
                              <span
                                className={`w-2 h-2 rounded-full ${
                                  node.connected ? "bg-emerald-400" : "bg-amber-400"
                                }`}
                              />
                              <span className={node.connected ? "text-emerald-400" : "text-amber-400"}>
                                {node.connected ? "ONLINE" : "SYNCING"}
                              </span>
                            </span>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })()}

          {/* Unified Any-TG SvxLink Gateway Architecture */}
          <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl p-5 space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-cyan-900/30 pb-4">
              <div className="flex items-center gap-2">
                <Radio className="w-5 h-5 text-emerald-400" />
                <div>
                  <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                    UNIFIED ANY-TG SVXLINK GATEWAY ARCHITECTURE
                  </h2>
                  <p className="text-[11px] text-slate-400 font-mono">
                    Gateway tunggal multi-arah: DVSwitch, QRadioLink, & SvxLink App terhubung langsung ke Server DMR Core tanpa bot suara manual.
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className="px-2.5 py-1 rounded-full bg-emerald-950/80 text-emerald-400 border border-emerald-800 text-[11px] font-bold font-mono flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  ANY-TG DIRECT MODE
                </span>
              </div>
            </div>

            {/* Architecture Flow Banner */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs font-mono">
              <div className="bg-slate-950/70 border border-slate-800/80 rounded-xl p-3 space-y-1.5">
                <div className="text-cyan-400 font-bold flex items-center gap-1.5">
                  <Smartphone className="w-4 h-4" />
                  <span>1. Klien Analog Eksternal</span>
                </div>
                <div className="text-slate-300 text-[11px]">
                  ? SvxLink Mobile App (Android/iOS)<br/>
                  ? DVSwitch Mobile (USRP/Analog)<br/>
                  ? QRadioLink & Repeater Fisik
                </div>
                <div className="text-[10px] text-slate-500 pt-1">Port: TCP & UDP 5300</div>
              </div>

              <div className="bg-slate-950/70 border border-emerald-900/50 rounded-xl p-3 space-y-1.5">
                <div className="text-emerald-400 font-bold flex items-center gap-1.5">
                  <RefreshCw className="w-4 h-4" />
                  <span>2. SvxLink Any-TG Gateway</span>
                </div>
                <div className="text-slate-300 text-[11px]">
                  ? 100% Bebas Suara Robot / Bot Audio<br/>
                  ? Dynamic Talkgroup Routing<br/>
                  ? Callsign & DMR ID Carry-Over
                </div>
                <div className="text-[10px] text-emerald-400/80 pt-1">Zero Manual Bot Config</div>
              </div>

              <div className="bg-slate-950/70 border border-slate-800/80 rounded-xl p-3 space-y-1.5">
                <div className="text-purple-400 font-bold flex items-center gap-1.5">
                  <Server className="w-4 h-4" />
                  <span>3. Server DMR Core & TG Matrix</span>
                </div>
                <div className="text-slate-300 text-[11px]">
                  ? WebRTC (/radio) & Mumble<br/>
                  ? MMDVM Hotspot / Repeater<br/>
                  ? TG Matrix Peering (Bridi, TombolPTT)
                </div>
                <div className="text-[10px] text-purple-400/80 pt-1">Cross-TG Isolation Active</div>
              </div>
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

            {/* Informational Card: Guide to Managing & Adding Nodes Manually */}
            <div className="p-4 bg-slate-950/80 rounded-xl border border-cyan-900/40 space-y-3 text-xs font-mono">
              <div className="flex items-center gap-2 text-cyan-400 font-bold">
                <HelpCircle className="w-4 h-4 text-cyan-400" />
                <span>PANDUAN PENGELOLAAN & PENAMBAHAN NODE MANUAL MATRIX:</span>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-[11px] text-slate-300">
                <div className="p-2.5 rounded-lg bg-[#080e18] border border-slate-800 space-y-1">
                  <div className="font-bold text-emerald-400 flex items-center gap-1.5">
                    <Bot className="w-3.5 h-3.5" />
                    <span>1. Node Bot SvxLink (Local)</span>
                  </div>
                  <p className="text-slate-400">
                    Gunakan form di atas untuk menambah atau mengedit bot. Klik tombol <span className="text-cyan-300 font-bold">[✏️]</span> pada baris tabel untuk memuat konfigurasi bot, lalu klik <span className="text-emerald-400 font-bold">Simpan</span> tanpa restart daemon.
                  </p>
                </div>
                <div className="p-2.5 rounded-lg bg-[#080e18] border border-slate-800 space-y-1">
                  <div className="font-bold text-cyan-400 flex items-center gap-1.5">
                    <Globe className="w-3.5 h-3.5" />
                    <span>2. Node DMR Peering (FreeDMR/BM)</span>
                  </div>
                  <p className="text-slate-400">
                    Kartu seperti <code className="text-cyan-300">DMR-BRIDI</code>, <code className="text-cyan-300">DMR-TOMBOLPTT</code> diatur di menu <a href="/settings" className="text-cyan-400 underline font-bold">Settings</a> (Peering) dan rute TG-nya di menu <a href="/matrix" className="text-cyan-400 underline font-bold">TG Matrix</a>.
                  </p>
                </div>
                <div className="p-2.5 rounded-lg bg-[#080e18] border border-slate-800 space-y-1">
                  <div className="font-bold text-amber-400 flex items-center gap-1.5">
                    <Smartphone className="w-3.5 h-3.5" />
                    <span>3. Node Client / Mobile Eksternal</span>
                  </div>
                  <p className="text-slate-400">
                    Aplikasi Android SvxLink atau repeater fisik dapat terhubung via Host: <code className="text-amber-300 font-bold">43.173.13.117</code>, Port: <code className="text-amber-300 font-bold">5300</code>, Auth: <code className="text-amber-300 font-bold">ninjaradio_svx</code> (mendukung Protocol 2.0 & 1.0).
                  </p>
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
