"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { AdminGuard } from "@/components/AdminGuard";
import {
  Volume2,
  Server,
  Key,
  ShieldCheck,
  Copy,
  Check,
  Terminal,
  Radio,
  ExternalLink,
  Layers,
  Headphones,
  CheckCircle2,
  AlertTriangle,
  Info,
  Users,
  Bot,
  Settings
} from "lucide-react";

interface TSChannel {
  cid: number;
  name: string;
  topic: string;
  total_clients: number;
  clients: string[];
}

interface TSBot {
  name: string;
  channel: string;
  tg: number;
  slot: number;
  svx_bot?: string;
  status: string;
}

export default function TeamSpeakPage() {
  const { isConnected } = useLiveMonitor();
  const [copiedField, setCopiedField] = useState<string | null>(null);
  const [telemetry, setTelemetry] = useState<any>(null);
  const [bots, setBots] = useState<TSBot[]>([]);
  const [loading, setLoading] = useState<boolean>(true);

  const serverHost = typeof window !== "undefined" ? window.location.hostname : "43.173.13.117";
  const voicePort = 9987;
  const queryPort = 10011;
  const filePort = 30033;
  const adminToken = "nROmoSRen56eICbHWRtGvjpZx5fexEoVcuoTdDvS";
  const queryUser = "serveradmin";
  const queryPass = "Eao7tfYU";

  useEffect(() => {
    const fetchTelemetry = async () => {
      try {
        const res = await fetch("/api/teamspeak/telemetry");
        if (res.ok) {
          const data = await res.json();
          setTelemetry(data);
        }
      } catch (err) {
        console.error("Failed to fetch TS3 telemetry:", err);
      } finally {
        setLoading(false);
      }
    };

    const fetchBots = async () => {
      try {
        const res = await fetch("/api/teamspeak/bots");
        if (res.ok) {
          const data = await res.json();
          if (Array.isArray(data.bots)) {
            setBots(data.bots);
          }
        }
      } catch (err) {
        console.error("Failed to fetch TS3 bots:", err);
      }
    };

    fetchTelemetry();
    fetchBots();
    const interval = setInterval(() => {
      fetchTelemetry();
      fetchBots();
    }, 3500);
    return () => clearInterval(interval);
  }, []);

  const copyToClipboard = (text: string, field: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(field);
    setTimeout(() => setCopiedField(null), 2000);
  };

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-20 md:pb-8 selection:bg-cyan-500 selection:text-black">
      <Navbar isConnected={isConnected} />

      <AdminGuard
        title="TEAMSPEAK 3 SERVER MANAGEMENT"
        description="Kontrol Server Voice TeamSpeak 3, Kredensial ServerAdmin & ServerQuery, serta Status Anti Phone-Home."
        tag="TEAMSPEAK 3"
      >
        <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
          {/* Header */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0b121e]/80 backdrop-blur-md p-5 rounded-2xl border border-cyan-900/30 shadow-xl">
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-blue-500 to-cyan-400 p-[1.5px] shadow-[0_0_20px_rgba(59,130,246,0.3)]">
                <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center">
                  <Headphones className="w-6 h-6 text-blue-400" />
                </div>
              </div>
              <div>
                <div className="flex items-center gap-2.5">
                  <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-white">
                    TEAMSPEAK 3 SERVER (ISOLATED)
                  </h1>
                  <span className="text-[11px] font-mono px-2 py-0.5 rounded-full font-bold bg-blue-950/80 text-blue-400 border border-blue-800">
                    PORT 9987 UDP
                  </span>
                </div>
                <p className="text-xs sm:text-sm text-slate-400 font-mono mt-0.5">
                  512-Slot MESMERiZE Emulated License • 100% Anti Phone-Home • ServerQuery CLI Active
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-slate-900/80 border border-slate-800 text-xs font-mono self-end md:self-center">
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 shadow-[0_0_8px_#34d399] animate-pulse" />
              <span className="text-slate-300 font-bold">STATUS: SERVER ONLINE</span>
            </div>
          </div>

          {/* Quick Connection Cards */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Voice Connection */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Volume2 className="w-5 h-5 text-cyan-400" />
                  <h2 className="font-heading font-bold text-sm text-white">VOICE CONNECTION</h2>
                </div>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-800">
                  UDP 9987
                </span>
              </div>
              <div className="p-3 bg-slate-950/80 rounded-xl border border-slate-800 space-y-1 font-mono text-xs">
                <div className="text-slate-400 text-[11px]">Server Address:</div>
                <div className="flex items-center justify-between">
                  <span className="text-cyan-300 font-bold">{serverHost}:{voicePort}</span>
                  <button
                    onClick={() => copyToClipboard(`${serverHost}:${voicePort}`, "voice")}
                    className="text-slate-400 hover:text-cyan-400 transition-colors"
                    title="Copy Address:Port"
                  >
                    {copiedField === "voice" ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                  </button>
                </div>
              </div>
              <div className="text-[11px] text-slate-400 font-mono">
                <span className="text-amber-400 font-bold">Password Server:</span> KOSONGKAN saja saat connect dari aplikasi klien.
              </div>
            </div>

            {/* ServerAdmin Privilege Key */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Key className="w-5 h-5 text-emerald-400" />
                  <h2 className="font-heading font-bold text-sm text-white">SERVERADMIN PRIVILEGE KEY</h2>
                </div>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800">
                  TOKEN
                </span>
              </div>
              <div className="p-3 bg-slate-950/80 rounded-xl border border-slate-800 space-y-1 font-mono text-xs">
                <div className="text-slate-400 text-[11px]">One-Time Claim Token:</div>
                <div className="flex items-center justify-between">
                  <span className="text-emerald-300 font-bold truncate max-w-[200px]">{adminToken}</span>
                  <button
                    onClick={() => copyToClipboard(adminToken, "token")}
                    className="text-slate-400 hover:text-emerald-400 transition-colors shrink-0 ml-2"
                    title="Copy Token"
                  >
                    {copiedField === "token" ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                  </button>
                </div>
              </div>
              <div className="text-[11px] text-slate-400 font-mono">
                Gunakan token di menu <code className="text-emerald-300">Permissions → Use Privilege Key</code> setelah terhubung.
              </div>
            </div>

            {/* ServerQuery Management */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Terminal className="w-5 h-5 text-purple-400" />
                  <h2 className="font-heading font-bold text-sm text-white">SERVERQUERY ADMIN</h2>
                </div>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-purple-950 text-purple-300 border border-purple-800">
                  TCP 10011
                </span>
              </div>
              <div className="p-3 bg-slate-950/80 rounded-xl border border-slate-800 space-y-1 font-mono text-xs">
                <div className="flex justify-between">
                  <span className="text-slate-400">User:</span>
                  <span className="text-purple-300 font-bold">{queryUser}</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-400">Pass:</span>
                  <div className="flex items-center gap-2">
                    <span className="text-purple-300 font-bold">{queryPass}</span>
                    <button
                      onClick={() => copyToClipboard(queryPass, "querypass")}
                      className="text-slate-400 hover:text-purple-400 transition-colors"
                      title="Copy Query Password"
                    >
                      {copiedField === "querypass" ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    </button>
                  </div>
                </div>
              </div>
              <div className="text-[11px] text-slate-400 font-mono">
                Digunakan untuk bot otomatisasi & telnet remote control via TCP port 10011.
              </div>
            </div>
          </div>

          {/* Live Channels & Rooms Matrix */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-800/80 pb-3">
              <div className="flex items-center gap-2">
                <Layers className="w-5 h-5 text-cyan-400" />
                <h2 className="font-heading font-bold text-sm sm:text-base text-white">
                  LIVE TEAMSPEAK 3 ROOMS & CHANNELS
                </h2>
              </div>
              <div className="flex items-center gap-3 text-xs font-mono">
                <span className="text-slate-400">
                  Total Users: <strong className="text-cyan-300">{telemetry?.total_clients ?? 0}</strong> / {telemetry?.max_clients ?? 512}
                </span>
                <span className="text-slate-600">•</span>
                <span className="text-slate-400">
                  Rooms: <strong className="text-emerald-400">{telemetry?.channels?.length ?? 0}</strong> Active
                </span>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              {(telemetry?.channels || []).map((ch: TSChannel) => (
                <div
                  key={ch.cid}
                  className="bg-slate-950/80 border border-slate-800/90 rounded-xl p-3.5 space-y-2.5 flex flex-col justify-between hover:border-cyan-700/50 transition-colors"
                >
                  <div>
                    <div className="flex items-start justify-between gap-1.5">
                      <div className="font-bold text-white text-xs truncate" title={ch.name}>
                        {ch.name}
                      </div>
                      <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-cyan-950/80 text-cyan-300 border border-cyan-800/80 shrink-0">
                        CID {ch.cid}
                      </span>
                    </div>
                    {ch.topic && (
                      <p className="text-[11px] text-slate-400 font-mono mt-0.5 truncate" title={ch.topic}>
                        {ch.topic}
                      </p>
                    )}
                  </div>

                  <div className="pt-2 border-t border-slate-900 space-y-2">
                    <div className="flex items-center justify-between text-[11px] font-mono">
                      <span className="text-slate-400 flex items-center gap-1">
                        <Users className="w-3.5 h-3.5 text-slate-400" />
                        Online:
                      </span>
                      <span className={`font-bold ${ch.total_clients > 0 ? "text-emerald-400" : "text-slate-500"}`}>
                        {ch.total_clients} Users
                      </span>
                    </div>

                    {ch.clients && ch.clients.length > 0 && (
                      <div className="flex flex-wrap gap-1">
                        {ch.clients.map((u, i) => (
                          <span
                            key={i}
                            className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-emerald-950/80 text-emerald-300 border border-emerald-800/50"
                          >
                            {u}
                          </span>
                        ))}
                      </div>
                    )}

                    <a
                      href={`ts3server://${serverHost}?port=${voicePort}&channel=${encodeURIComponent(ch.name)}`}
                      className="inline-flex items-center justify-center gap-1.5 w-full py-1.5 px-2 rounded-lg bg-cyan-950/60 hover:bg-cyan-900/80 text-cyan-300 border border-cyan-800/70 text-[11px] font-mono font-bold transition-all shadow-sm"
                    >
                      <ExternalLink className="w-3 h-3" />
                      <span>Join Room</span>
                    </a>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* DMR Audio Bridge Bot & Stationed Bots Table */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            {/* Left 1-col: Bridge Status Card */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4 flex flex-col justify-between">
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Radio className="w-5 h-5 text-cyan-400" />
                    <h2 className="font-heading font-black text-white text-base">TS3 AUDIO BRIDGE BOT</h2>
                  </div>
                  <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                    ACTIVE
                  </span>
                </div>
                <p className="text-xs text-slate-400 font-mono">
                  Gateway otomatis audio dua arah antara TeamSpeak 3 Voice Rooms dan jaringan DMR Core.
                </p>
                <div className="space-y-2 text-xs font-mono pt-2 border-t border-slate-900">
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Gateway Bot:</span>
                    <span className="text-cyan-300 font-bold">NINJA-TS3-BRIDGE</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Transcoding:</span>
                    <span className="text-emerald-400 font-bold">PCM 8kHz (DMR) &lt;-&gt; Opus (TS3)</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Anti-Collision:</span>
                    <span className="text-purple-400 font-bold">Mutual Exclusion TG Lock</span>
                  </div>
                </div>
              </div>
              <a
                href="/settings"
                className="w-full py-2 px-3 rounded-xl bg-cyan-950/70 hover:bg-cyan-900/60 text-cyan-300 border border-cyan-800 text-xs font-mono font-bold flex items-center justify-center gap-2 transition shadow-md"
              >
                <Settings className="w-3.5 h-3.5" />
                <span>KELOLA BOT DI PENGATURAN</span>
              </a>
            </div>

            {/* Right 2-col: Table of Stationed User Bots */}
            <div className="lg:col-span-2 bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl flex flex-col justify-between">
              <div>
                <div className="p-4 border-b border-cyan-900/30 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Bot className="w-5 h-5 text-purple-400" />
                    <div>
                      <h2 className="font-heading font-bold text-sm sm:text-base text-white">
                        USER BOT TEAMSPEAK 3 (DYNAMIC BOT ROUTER)
                      </h2>
                      <p className="text-[11px] text-slate-400 font-mono">
                        Daftar virtual client bot yang disiagakan di channel TS3 untuk menjembatani Talkgroup DMR.
                      </p>
                    </div>
                  </div>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs font-mono">
                    <thead>
                      <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                        <th className="p-3 font-bold">NAMA USER BOT</th>
                        <th className="p-3 font-bold">CHANNEL STANDBY</th>
                        <th className="p-3 font-bold">PAIRED SVXLINK BOT</th>
                        <th className="p-3 font-bold">TARGET DMR TG</th>
                        <th className="p-3 font-bold">TIMESLOT</th>
                        <th className="p-3 font-bold text-center">STATUS</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-800/60">
                      {bots && bots.length > 0 ? (
                        bots.map((bot, idx) => (
                          <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                            <td className="p-3 font-bold text-white flex items-center gap-2">
                              <Bot className="w-4 h-4 text-purple-400" />
                              <span className="text-cyan-300 font-black">{bot.name}</span>
                            </td>
                            <td className="p-3">
                              <span className="px-2.5 py-1 rounded-lg bg-blue-950/60 text-blue-300 border border-blue-800/70 font-bold inline-flex items-center gap-1.5">
                                <Volume2 className="w-3 h-3 text-cyan-400" />
                                {bot.channel}
                              </span>
                            </td>
                            <td className="p-3">
                              {bot.svx_bot ? (
                                <span className="px-2.5 py-1 rounded-lg bg-cyan-950/80 text-cyan-300 border border-cyan-800 text-[11px] font-bold inline-flex items-center gap-1.5">
                                  <Radio className="w-3 h-3 text-cyan-400" />
                                  {bot.svx_bot}
                                </span>
                              ) : (
                                <span className="text-slate-500 text-[11px] font-mono">Direct TG</span>
                              )}
                            </td>
                            <td className="p-3 font-bold text-emerald-400">TG {bot.tg}</td>
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
                          <td colSpan={6} className="p-6 text-center text-slate-500">
                            Belum ada bot terdaftar. Tambahkan bot di menu Pengaturan.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>

          {/* Guide & Architecture Section */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Step-by-Step Connection Instructions */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
              <div className="flex items-center gap-2 text-cyan-400 font-bold text-sm font-heading">
                <Info className="w-4 h-4" />
                <span>PANDUAN KONEKSI APLIKASI TEAMSPEAK 3</span>
              </div>

              <div className="space-y-3 text-xs font-mono text-slate-300">
                <div className="flex items-start gap-2.5 p-3 rounded-xl bg-slate-950/70 border border-slate-800">
                  <span className="w-5 h-5 rounded-full bg-cyan-950 text-cyan-400 border border-cyan-800 flex items-center justify-center shrink-0 font-bold text-[11px]">
                    1
                  </span>
                  <div>
                    <div className="font-bold text-white">Masukkan Alamat Server & Port</div>
                    <p className="text-slate-400 text-[11px] mt-0.5">
                      Pada kolom <span className="text-cyan-300">Server Nickname or Address</span>, masukkan: <code className="text-cyan-300 font-bold">{serverHost}:9987</code>
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-2.5 p-3 rounded-xl bg-slate-950/70 border border-slate-800">
                  <span className="w-5 h-5 rounded-full bg-cyan-950 text-cyan-400 border border-cyan-800 flex items-center justify-center shrink-0 font-bold text-[11px]">
                    2
                  </span>
                  <div>
                    <div className="font-bold text-white">Kosongkan Password Server</div>
                    <p className="text-slate-400 text-[11px] mt-0.5">
                      Jangan masukkan apa pun pada kolom <span className="text-amber-300">Server Password</span>. Jangan memasukkan token admin ke kolom password ini!
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-2.5 p-3 rounded-xl bg-slate-950/70 border border-slate-800">
                  <span className="w-5 h-5 rounded-full bg-emerald-950 text-emerald-400 border border-emerald-800 flex items-center justify-center shrink-0 font-bold text-[11px]">
                    3
                  </span>
                  <div>
                    <div className="font-bold text-white">Klaim Hak Akses ServerAdmin</div>
                    <p className="text-slate-400 text-[11px] mt-0.5">
                      Setelah berhasil masuk ke server, buka menu <span className="text-emerald-300 font-bold">Permissions → Use Privilege Key</span> lalu paste token <code className="text-emerald-300">{adminToken}</code>.
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* Anti Phone-Home & Security Isolation */}
            <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-4">
              <div className="flex items-center gap-2 text-emerald-400 font-bold text-sm font-heading">
                <ShieldCheck className="w-4 h-4" />
                <span>PROTEKSI ANTI PHONE-HOME (ZERO-OUTBOUND)</span>
              </div>

              <p className="text-xs text-slate-400 font-mono">
                Server TeamSpeak 3 ini dibungkus dengan proteksi ganda di container Docker agar tidak pernah menghubungi server lisensi pusat di Jerman:
              </p>

              <div className="space-y-2 text-xs font-mono">
                <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 flex items-center justify-between">
                  <span className="text-slate-300">Accounting Emulator:</span>
                  <span className="text-emerald-400 font-bold">127.0.0.1:2008 (Active)</span>
                </div>
                <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 flex items-center justify-between">
                  <span className="text-slate-300">Weblist Reporting:</span>
                  <span className="text-emerald-400 font-bold">Disabled (weblist_enabled=0)</span>
                </div>
                <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800 flex items-center justify-between">
                  <span className="text-slate-300">DNS Loopback Override:</span>
                  <span className="text-emerald-400 font-bold">accounting / blacklist → 127.0.0.1</span>
                </div>
              </div>

              {/* Telnet Example */}
              <div className="p-3 bg-slate-950/90 rounded-xl border border-cyan-950 space-y-1.5 font-mono text-xs">
                <div className="flex items-center justify-between text-slate-400 text-[11px]">
                  <span>Contoh Koneksi ServerQuery Telnet:</span>
                  <button
                    onClick={() =>
                      copyToClipboard(
                        `telnet ${serverHost} 10011\nlogin ${queryUser} ${queryPass}\nuse sid=1`,
                        "telnet"
                      )
                    }
                    className="text-slate-400 hover:text-cyan-400"
                  >
                    {copiedField === "telnet" ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  </button>
                </div>
                <div className="text-cyan-400 select-all overflow-x-auto text-[11px]">
                  telnet {serverHost} 10011<br/>
                  login {queryUser} {queryPass}<br/>
                  use sid=1
                </div>
              </div>
            </div>
          </div>
        </main>
      </AdminGuard>
    </div>
  );
}
