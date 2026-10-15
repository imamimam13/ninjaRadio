"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { 
  Volume2, 
  Smartphone, 
  Radio, 
  RefreshCw, 
  CheckCircle2, 
  Headphones, 
  Layers, 
  Copy, 
  Check, 
  Bot,
  Settings
} from "lucide-react";

interface ChannelMapping {
  channel: string;
  tg: number;
  slot: number;
  description: string;
}

interface MumbleBot {
  name: string;
  channel: string;
  tg: number;
  slot: number;
  status: string;
}

interface BridgeBotInfo {
  name: string;
  status: string;
  audio_transcoding: string;
  gateway_port: number;
}

interface MumlaTelemetry {
  online: boolean;
  server_host: string;
  server_port: number;
  protocol: string;
  server_version: string;
  tcp_status: string;
  udp_status: string;
  connected_users: number;
  max_users: number;
  bridge_bot: BridgeBotInfo;
  channels: ChannelMapping[];
}

export default function MumlaPage() {
  const { isConnected } = useLiveMonitor();
  const [telemetry, setTelemetry] = useState<MumlaTelemetry | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [copiedField, setCopiedField] = useState<string | null>(null);

  // Read-only Channel & Bot Monitoring States
  const [channels, setChannels] = useState<ChannelMapping[]>([]);
  const [bots, setBots] = useState<MumbleBot[]>([]);

  const fetchBots = async () => {
    try {
      const res = await fetch("/api/mumla/bots");
      if (res.ok) {
        const data = await res.json();
        if (data.bots) {
          setBots(data.bots);
        }
      }
    } catch (err) {
      console.error("Failed to fetch Mumble bots:", err);
    }
  };

  const fetchTelemetry = async () => {
    try {
      const res = await fetch("/api/mumla/telemetry");
      if (res.ok) {
        const data = await res.json();
        setTelemetry(data);
        if (data.channels) {
          setChannels(data.channels);
        }
      }
    } catch (err) {
      console.error("Failed to fetch Mumla telemetry:", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchTelemetry();
    fetchBots();
    const interval = setInterval(() => {
      fetchTelemetry();
      fetchBots();
    }, 3000);
    return () => clearInterval(interval);
  }, []);

  const handleManualRefresh = () => {
    setRefreshing(true);
    fetchTelemetry();
    fetchBots();
  };

  const copyToClipboard = (text: string, field: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(field);
    setTimeout(() => setCopiedField(null), 2000);
  };

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-20 md:pb-8 selection:bg-cyan-500 selection:text-black">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
        {/* Header Section */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0b121e]/80 backdrop-blur-md p-5 rounded-2xl border border-cyan-900/30 shadow-xl">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-purple-500 to-cyan-400 p-[1.5px] shadow-[0_0_20px_rgba(168,85,247,0.3)]">
              <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center">
                <Volume2 className="w-6 h-6 text-purple-400" />
              </div>
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-white">
                  MUMBLE / MUMLA VOIP SERVER
                </h1>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded-full font-bold bg-purple-950/80 text-purple-400 border border-purple-800">
                  MURMUR v{telemetry?.server_version || "1.5"}
                </span>
              </div>
              <p className="text-xs sm:text-sm text-slate-400 font-mono mt-0.5">
                Low-Latency VoIP Reflector • Mumla Android / iOS / Desktop • TCP/UDP Port 64738
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
                {telemetry?.online ? "VOIP: ONLINE" : "VOIP: CHECKING"}
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

            <a
              href="/settings"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-950/70 hover:bg-purple-900/60 text-purple-300 border border-purple-800 text-xs font-mono font-bold transition-all shadow-md hover:scale-[1.02]"
            >
              <Settings className="w-3.5 h-3.5 text-purple-400" />
              <span>KELOLA ROOM & BOT (PIN)</span>
            </a>
          </div>
        </div>

        {/* Server & Bridge Status Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* Murmur VoIP Server */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Headphones className="w-5 h-5 text-purple-400" />
                <h2 className="font-heading font-black text-white text-base">MURMUR VOIP CORE</h2>
              </div>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                  telemetry?.online
                    ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                    : "bg-rose-950 text-rose-400 border border-rose-800"
                }`}
              >
                {telemetry?.online ? "SERVER RUNNING" : "OFFLINE"}
              </span>
            </div>

            <p className="text-xs text-slate-400 font-mono">
              High-fidelity Opus audio engine running on port 64738 with zero-latency full duplex.
            </p>

            <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-2 text-slate-300">
              <div className="flex justify-between items-center">
                <span className="text-slate-500">TCP Voice & Control:</span>
                <span className="text-emerald-400 font-bold flex items-center gap-1">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  PORT 64738 ({telemetry?.tcp_status || "OPEN"})
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">UDP Low-Latency Ping:</span>
                <span className="text-emerald-400 font-bold flex items-center gap-1">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  PORT 64738 ({telemetry?.udp_status || "ACTIVE"})
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">Connected Users:</span>
                <span className="text-cyan-400 font-bold">
                  {telemetry?.connected_users || 0} / {telemetry?.max_users || 100} Online
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">Audio Codec:</span>
                <span className="text-purple-400 font-bold">Opus 48,000 Hz High-Fidelity</span>
              </div>
            </div>
          </div>

          {/* Gateway Bridge Bot */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Radio className="w-5 h-5 text-cyan-400" />
                <h2 className="font-heading font-black text-white text-base">DMR AUDIO BRIDGE BOT</h2>
              </div>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                  telemetry?.bridge_bot?.status === "RUNNING"
                    ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                    : "bg-amber-950 text-amber-400 border border-amber-800"
                }`}
              >
                {telemetry?.bridge_bot?.status || "ACTIVE"}
              </span>
            </div>

            <p className="text-xs text-slate-400 font-mono">
              Bi-directional audio bridge transcoding between Mumble VoIP rooms and DMR Talkgroups.
            </p>

            <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-2 text-slate-300">
              <div className="flex justify-between items-center">
                <span className="text-slate-500">Gateway Bot Name:</span>
                <span className="text-cyan-300 font-bold">{telemetry?.bridge_bot?.name || "NinjaRadio-Gateway"}</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">Audio Transcoder:</span>
                <span className="text-emerald-400 font-bold">
                  {telemetry?.bridge_bot?.audio_transcoding || "PCM 8kHz <-> Opus 48kHz"}
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">Internal Audio Route:</span>
                <span className="text-purple-400 font-bold">UDP Port 12346 (Core Audio)</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-slate-500">PTT Logic:</span>
                <span className="text-slate-300 font-bold">VOX & Digital Carrier Detect</span>
              </div>
            </div>
          </div>
        </div>

        {/* Room / Channel to DMR Talkgroup Mappings */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Layers className="w-5 h-5 text-purple-400" />
              <div>
                <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                  MUMBLE ROOMS & DMR TALKGROUP MAPPINGS
                </h2>
                <p className="text-[11px] text-slate-400 font-mono">
                  Daftar ruangan aktif Mumble yang terhubung dengan Talkgroup DMR.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 self-end sm:self-center">
              <a
                href="/settings"
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-950/70 hover:bg-purple-900/60 text-purple-300 border border-purple-800 text-xs font-mono font-bold transition-all shadow-md hover:scale-[1.02]"
              >
                <Settings className="w-3.5 h-3.5 text-purple-400" />
                <span>PENGATURAN ROOM (PIN)</span>
              </a>
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">MUMBLE ROOM / CHANNEL</th>
                  <th className="p-3 font-bold">MAPPED DMR TG</th>
                  <th className="p-3 font-bold">TIMESLOT</th>
                  <th className="p-3 font-bold">PURPOSE / KETERANGAN</th>
                  <th className="p-3 font-bold text-center">STATUS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {channels && channels.length > 0 ? (
                  channels.map((ch, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 font-bold text-white flex items-center gap-2">
                        <Volume2 className="w-4 h-4 text-purple-400" />
                        <span className="text-purple-300 font-black text-sm">{ch.channel}</span>
                      </td>
                      <td className="p-3 font-bold text-cyan-400 text-sm">TG {ch.tg}</td>
                      <td className="p-3 text-slate-300">Slot {ch.slot}</td>
                      <td className="p-3 text-slate-400">{ch.description}</td>
                      <td className="p-3 text-center">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                          LINKED
                        </span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={5} className="p-8 text-center text-slate-500">
                      Belum ada channel yang terkonfigurasi di server.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Mumble User Bot Management */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Bot className="w-5 h-5 text-cyan-400" />
              <div>
                <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                  USER BOT MUMBLE (DYNAMIC BOT ROUTER)
                </h2>
                <p className="text-[11px] text-slate-400 font-mono">
                  Daftar user bot aktif yang menjembatani channel Mumble ke jaringan DMR.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 self-end sm:self-center">
              <a
                href="/settings"
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-cyan-950/70 hover:bg-cyan-900/60 text-cyan-300 border border-cyan-800 text-xs font-mono font-bold transition-all shadow-md hover:scale-[1.02]"
              >
                <Settings className="w-3.5 h-3.5 text-cyan-400" />
                <span>KELOLA BOT DI PENGATURAN (PIN)</span>
              </a>
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">NAMA USER BOT</th>
                  <th className="p-3 font-bold">RUANGAN / CHANNEL AKTIF</th>
                  <th className="p-3 font-bold">MAPPED DMR TG</th>
                  <th className="p-3 font-bold">TIMESLOT</th>
                  <th className="p-3 font-bold text-center">STATUS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {bots && bots.length > 0 ? (
                  bots.map((bot, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 font-bold text-white flex items-center gap-2">
                        <Bot className="w-4 h-4 text-cyan-400" />
                        <span className="text-cyan-300 font-black text-sm">{bot.name}</span>
                      </td>
                      <td className="p-3">
                        <span className="px-2.5 py-1 rounded-lg bg-purple-950/60 text-purple-300 border border-purple-800/70 font-bold inline-flex items-center gap-1.5">
                          <Volume2 className="w-3 h-3 text-purple-400" />
                          {bot.channel}
                        </span>
                      </td>
                      <td className="p-3 font-bold text-emerald-400 text-sm">TG {bot.tg}</td>
                      <td className="p-3 text-slate-300">Slot {bot.slot}</td>
                      <td className="p-3 text-center">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                          {bot.status || "STATIONED"}
                        </span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={5} className="p-8 text-center text-slate-500">
                      Belum ada user bot aktif. Kelola bot di menu Pengaturan.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Quick Connection Guide */}
        <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
          <div className="flex items-center gap-2">
            <Smartphone className="w-5 h-5 text-cyan-400" />
            <h2 className="font-heading font-bold text-base text-white">
              HOW TO CONNECT VIA MUMLA (ANDROID) OR MUMBLE (IOS / PC)
            </h2>
          </div>

          <p className="text-xs text-slate-400 font-mono">
            You can talk into DMR Talkgroups directly from your smartphone using Mumla app on Android or Mumble on PC:
          </p>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs font-mono">
            <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
              <span className="text-slate-500 block">Server Address / Host:</span>
              <div className="flex items-center justify-between">
                <span className="text-white font-bold">{typeof window !== "undefined" ? window.location.hostname : "Your VPS IP"}</span>
                <button
                  onClick={() => copyToClipboard(typeof window !== "undefined" ? window.location.hostname : "", "host")}
                  className="text-slate-400 hover:text-cyan-400"
                >
                  {copiedField === "host" ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                </button>
              </div>
            </div>

            <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
              <span className="text-slate-500 block">Port:</span>
              <div className="flex items-center justify-between">
                <span className="text-cyan-400 font-bold">64738</span>
                <button
                  onClick={() => copyToClipboard("64738", "port")}
                  className="text-slate-400 hover:text-cyan-400"
                >
                  {copiedField === "port" ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                </button>
              </div>
            </div>

            <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 space-y-1">
              <span className="text-slate-500 block">Username:</span>
              <span className="text-purple-400 font-bold">Your Callsign (e.g. BD62BOT)</span>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
