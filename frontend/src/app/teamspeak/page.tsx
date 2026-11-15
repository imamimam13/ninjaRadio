"use client";

import React, { useState } from "react";
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
  Info
} from "lucide-react";

export default function TeamSpeakPage() {
  const { isConnected } = useLiveMonitor();
  const [copiedField, setCopiedField] = useState<string | null>(null);

  const serverHost = typeof window !== "undefined" ? window.location.hostname : "43.173.13.117";
  const voicePort = 9987;
  const queryPort = 10011;
  const filePort = 30033;
  const adminToken = "nROmoSRen56eICbHWRtGvjpZx5fexEoVcuoTdDvS";
  const queryUser = "serveradmin";
  const queryPass = "Eao7tfYU";

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
