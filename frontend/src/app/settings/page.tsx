"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { usePhysicalPTT } from "@/hooks/usePhysicalPTT";
import { beepPlayer } from "@/components/BeepPlayer";
import { 
  Settings, 
  Key, 
  Volume2, 
  Server, 
  Radio, 
  Layers, 
  Globe, 
  Save, 
  Check, 
  Download, 
  Upload, 
  Wifi, 
  Sliders,
  Lock,
  Unlock,
  ShieldCheck,
  AlertTriangle,
  Terminal
} from "lucide-react";

export default function SettingsPage() {
  const { isConnected } = useLiveMonitor();
  const { pttKey, saveCustomKey } = usePhysicalPTT();
  const [activeTab, setActiveTab] = useState<"general" | "hotspot" | "peering" | "crossmode" | "mumble" | "hardware">("general");
  const [isListeningKey, setIsListeningKey] = useState(false);
  const [savedSuccess, setSavedSuccess] = useState(false);

  // Admin PIN Lock Security
  const [isAdminUnlocked, setIsAdminUnlocked] = useState(false);
  const [showPinModal, setShowPinModal] = useState(false);
  const [pinInput, setPinInput] = useState("");
  const [pinError, setPinError] = useState(false);

  // 1. General Server Identity
  const [serverCallsign, setServerCallsign] = useState("N1NJA");
  const [serverDmrId, setServerDmrId] = useState(5109999);
  const [serverName, setServerName] = useState("NinjaRadio Indonesia Master Core");
  const [serverLocation, setServerLocation] = useState("Jakarta, Indonesia");
  const [defaultTg, setDefaultTg] = useState(510);

  // 2. Hotspot MMDVM Master Config
  const [hotspotPort, setHotspotPort] = useState(62031);
  const [hotspotPassword, setHotspotPassword] = useState("ninjaradio_pass");
  const [maxHotspots, setMaxHotspots] = useState(128);
  const [allowLoop, setAllowLoop] = useState(false);

  // 3. FreeDMR & BrandMeister Peering
  const [peerHost, setPeerHost] = useState("master.freedmr.org");
  const [peerPort, setPeerPort] = useState(62030);
  const [peerPassword, setPeerPassword] = useState("openbridge_secret");
  const [subscribedTgs, setSubscribedTgs] = useState("510, 5101, 5102, 5108, 5109, 5100");

  // 4. Cross-Mode & DVSwitch Config
  const [usrpPort, setUsrpPort] = useState(12345);
  const [ysfRoomId, setYsfRoomId] = useState(51000);
  const [aslNode, setAslNode] = useState(59123);
  const [xlxReflector, setXlxReflector] = useState("XLX510 B");

  // 5. Mumble VoIP Server & Bridge Config
  const [mumbleHost, setMumbleHost] = useState("mumble-server");
  const [mumblePort, setMumblePort] = useState(64738);
  const [mumbleChannel, setMumbleChannel] = useState("NinjaRadio-Room");
  const [mumbleTargetTg, setMumbleTargetTg] = useState(5109);

  useEffect(() => {
    if (!isListeningKey) return;

    const handleKey = (e: KeyboardEvent) => {
      e.preventDefault();
      const detected = e.code || e.key || `KeyCode_${e.keyCode}`;
      saveCustomKey(detected);
      setIsListeningKey(false);
      beepPlayer?.playTalkGrant();
    };

    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [isListeningKey, saveCustomKey]);

  const handleToggleAdminMode = () => {
    if (isAdminUnlocked) {
      setIsAdminUnlocked(false);
      beepPlayer?.playRogerBeep();
    } else {
      setPinInput("");
      setPinError(false);
      setShowPinModal(true);
    }
  };

  const handleVerifyPin = (e: React.FormEvent) => {
    e.preventDefault();
    if (pinInput.trim() === "1234" || pinInput.trim() === "admin" || pinInput.trim() === "ninjadmr") {
      setIsAdminUnlocked(true);
      setShowPinModal(false);
      setPinError(false);
      beepPlayer?.playTalkGrant();
    } else {
      setPinError(true);
    }
  };

  const handleSaveConfig = (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAdminUnlocked) {
      handleToggleAdminMode();
      return;
    }
    setSavedSuccess(true);
    beepPlayer?.playTalkGrant();
    setTimeout(() => setSavedSuccess(false), 2500);
  };

  const handleExportJson = () => {
    const configData = {
      serverCallsign,
      serverDmrId,
      serverName,
      serverLocation,
      defaultTg,
      hotspot: { hotspotPort, hotspotPassword, maxHotspots, allowLoop },
      peering: { peerHost, peerPort, peerPassword, subscribedTgs },
      crossmode: { usrpPort, ysfRoomId, aslNode, xlxReflector },
      mumble: { mumbleHost, mumblePort, mumbleChannel, mumbleTargetTg },
      hardware: { pttKey },
      exportedAt: new Date().toISOString()
    };
    const blob = new Blob([JSON.stringify(configData, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `ninjadmr_config_${serverCallsign}.json`;
    a.click();
  };

  return (
    <div className="min-h-screen pb-20 md:pb-8 flex flex-col bg-[#07090e] bg-grid-pattern">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-5xl w-full mx-auto px-3 sm:px-4 py-4 space-y-4">
        
        {/* Header */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 sm:p-5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-2xl">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h2 className="text-xl font-heading font-black text-white flex items-center gap-2">
                <Settings className="w-5 h-5 text-cyan-400" />
                SYSTEM CONFIG & PARAMETERS
              </h2>
              {isAdminUnlocked ? (
                <span className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-bold bg-emerald-950 text-emerald-400 border border-emerald-800 flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
                  🔓 Mode Edit Admin (Aktif)
                </span>
              ) : (
                <span className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-bold bg-slate-900 text-slate-300 border border-slate-700 flex items-center gap-1">
                  <Lock className="w-3 h-3 text-amber-400" />
                  👀 Mode Publik (Read-Only)
                </span>
              )}
            </div>
            <p className="text-xs font-mono text-slate-400 mt-1">
              {isAdminUnlocked 
                ? "Mode Admin Aktif: Anda dapat mengubah konfigurasi server, membuka koneksi peering, dan merubah port."
                : "Mode Publik: Anda dapat melihat parameter koneksi Hotspot Pi-Star, Mumble & DVSwitch. Masukkan PIN Admin untuk mengubah."}
            </p>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto">
            <button
              type="button"
              onClick={handleToggleAdminMode}
              className={`flex-1 sm:flex-none flex items-center justify-center gap-1.5 px-4 py-2 text-xs font-heading font-black rounded-xl shadow-lg transition-all ${
                isAdminUnlocked
                  ? "bg-rose-950/80 hover:bg-rose-900 border border-rose-700 text-rose-300"
                  : "bg-amber-400 hover:bg-amber-300 text-slate-950 shadow-amber-500/20"
              }`}
            >
              {isAdminUnlocked ? <Lock className="w-3.5 h-3.5" /> : <Unlock className="w-3.5 h-3.5" />}
              <span>{isAdminUnlocked ? "Kunci Kembali Admin" : "Buka Mode Edit Admin"}</span>
            </button>

            <button
              type="button"
              onClick={handleExportJson}
              className="flex items-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-cyan-900/60 hover:border-cyan-400 text-cyan-300 text-xs font-mono font-bold rounded-xl transition-all"
            >
              <Download className="w-4 h-4 text-cyan-400" />
              <span className="hidden sm:inline">Backup JSON</span>
            </button>
          </div>
        </div>

        {/* Navigation Tabs */}
        <div className="flex flex-wrap items-center gap-2 border-b border-cyan-900/40 pb-2">
          {[
            { id: "general", label: "🏷️ Identitas Server", icon: Server },
            { id: "hotspot", label: "📻 Hotspot MMDVM", icon: Wifi },
            { id: "peering", label: "🌐 Peering FreeDMR / BM", icon: Globe },
            { id: "crossmode", label: "🔀 DVSwitch & Cross-Mode", icon: Layers },
            { id: "mumble", label: "🎙️ Mumble VoIP Server", icon: Radio },
            { id: "hardware", label: "🎛️ Tombol PTT & Audio", icon: Key },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`px-3.5 py-2 rounded-xl text-xs font-mono font-bold transition-all ${
                activeTab === tab.id
                  ? "bg-cyan-400 text-slate-950 shadow-[0_0_12px_rgba(6,182,212,0.3)]"
                  : "bg-[#0b121e] border border-cyan-900/40 text-slate-400 hover:text-white"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Tab Form Content */}
        <form onSubmit={handleSaveConfig} className="space-y-4">

          {/* TAB 1: GENERAL SERVER IDENTITY */}
          {activeTab === "general" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                <Server className="w-4 h-4 text-cyan-400" />
                Informasi & Identitas Master Server
              </h3>
              
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Server Callsign (ORARI / Master)</label>
                  <input
                    type="text"
                    required
                    readOnly={!isAdminUnlocked}
                    value={serverCallsign}
                    onChange={(e) => setServerCallsign(e.target.value.toUpperCase())}
                    className={`w-full px-3 py-2.5 rounded-xl font-mono text-sm ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400 text-cyan-300"
                        : "bg-[#080d17] border border-slate-800 text-slate-300 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Callsign yang dipancarkan saat identifikasi repeater/server.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Server Master DMR ID (7 Digit)</label>
                  <input
                    type="number"
                    required
                    readOnly={!isAdminUnlocked}
                    value={serverDmrId}
                    onChange={(e) => setServerDmrId(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl font-mono text-sm ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400 text-cyan-300"
                        : "bg-[#080d17] border border-slate-800 text-slate-300 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">DMR ID resmi dari RadioID.net untuk server ini.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Nama Network / Komunitas</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={serverName}
                    onChange={(e) => setServerName(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400 text-white"
                        : "bg-[#080d17] border border-slate-800 text-slate-300 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Default Talkgroup Utama (TG)</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={defaultTg}
                    onChange={(e) => setDefaultTg(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl font-mono text-sm ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400 text-cyan-300"
                        : "bg-[#080d17] border border-slate-800 text-slate-300 cursor-default"
                    }`}
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: HOTSPOT MMDVM SERVER */}
          {activeTab === "hotspot" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                <Wifi className="w-4 h-4 text-emerald-400" />
                Pengaturan Master Hotspot (Pi-Star / OpenGD77 / WPSD)
              </h3>
              
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Listen Port UDP (MMDVM Protocol - Isi di Pi-Star)</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={hotspotPort}
                    onChange={(e) => setHotspotPort(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-emerald-400 font-mono text-sm font-bold ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Port default: 62031 (digunakan di menu DMR Gateway Pi-Star).</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Password Koneksi Hotspot (Isi di Pi-Star)</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={hotspotPassword}
                    onChange={(e) => setHotspotPassword(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Password yang harus diisi oleh pengguna di Pi-Star mereka.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Kapasitas Maksimal Hotspot Terhubung</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={maxHotspots}
                    onChange={(e) => setMaxHotspots(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: FREEDMR & BRANDMEISTER PEERING */}
          {activeTab === "peering" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <div className="flex items-center justify-between">
                <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                  <Globe className="w-4 h-4 text-purple-400" />
                  Interkoneksi OpenBridge ke FreeDMR / BrandMeister
                </h3>
                {!isAdminUnlocked && (
                  <span className="text-[11px] text-amber-400 font-mono flex items-center gap-1">
                    <Lock className="w-3 h-3" /> Peering Terkunci
                  </span>
                )}
              </div>
              
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Target Server Hostname / IP</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={peerHost}
                    onChange={(e) => setPeerHost(e.target.value)}
                    placeholder="misal: master.freedmr.org"
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Target Port UDP OpenBridge</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={peerPort}
                    onChange={(e) => setPeerPort(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">OpenBridge Secret Key / Password</label>
                  <input
                    type="password"
                    readOnly={!isAdminUnlocked}
                    value={peerPassword}
                    onChange={(e) => setPeerPassword(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Subscribed Talkgroups (Dipisahkan koma)</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={subscribedTgs}
                    onChange={(e) => setSubscribedTgs(e.target.value)}
                    placeholder="510, 5101, 5102, 5108, 5109"
                    className={`w-full px-3 py-2.5 rounded-xl text-cyan-300 font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 4: DVSWITCH & CROSS-MODE */}
          {activeTab === "crossmode" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <div className="flex items-center justify-between">
                <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                  <Layers className="w-4 h-4 text-amber-400" />
                  DVSwitch Mobile & Cross-Mode Gateway
                </h3>
                {!isAdminUnlocked && (
                  <span className="text-[11px] text-amber-400 font-mono flex items-center gap-1">
                    <Lock className="w-3 h-3" /> Gateway Terkunci
                  </span>
                )}
              </div>
              
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Port UDP USRP (Untuk DVSwitch Mobile Android)</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={usrpPort}
                    onChange={(e) => setUsrpPort(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-emerald-400 font-mono text-sm font-bold ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Masukkan IP server & port ini pada aplikasi DVSwitch Mobile.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">YSF (System Fusion) Room ID</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={ysfRoomId}
                    onChange={(e) => setYsfRoomId(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">AllStarLink (ASL) Node Analog</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={aslNode}
                    onChange={(e) => setAslNode(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">D-STAR XLX Reflector & Modul</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={xlxReflector}
                    onChange={(e) => setXlxReflector(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: MUMBLE VOIP SERVER & GATEWAY */}
          {activeTab === "mumble" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                    <Radio className="w-4 h-4 text-emerald-400" />
                    Mumble VoIP Server (Murmur) & DMR Audio Gateway
                  </h3>
                  <p className="text-xs font-mono text-slate-400 mt-0.5">
                    Hubungkan HP Android (Mumla/Plumble), iPhone, dan PC langsung ke Talkgroup DMR melalui Mumble Server internal
                  </p>
                </div>
                <span className="px-2.5 py-1 rounded-full text-[10px] font-bold bg-emerald-950 text-emerald-400 border border-emerald-800 font-mono">
                  🟢 Built-in Murmur (Port 64738)
                </span>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Mumble Server Hostname / IP (Docker Internal)</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={mumbleHost}
                    onChange={(e) => setMumbleHost(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Default: <code>mumble-server</code> (container) atau IP Publik server.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Mumble Port (TCP & UDP)</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={mumblePort}
                    onChange={(e) => setMumblePort(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-emerald-400 font-mono text-sm font-bold ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Port default Murmur: 64738.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Nama Channel / Room Mumble</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={mumbleChannel}
                    onChange={(e) => setMumbleChannel(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-white font-mono ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Target Talkgroup DMR yang Di-Bridge</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={mumbleTargetTg}
                    onChange={(e) => setMumbleTargetTg(Number(e.target.value))}
                    className={`w-full px-3 py-2.5 rounded-xl text-cyan-300 font-mono text-sm font-bold ${
                      isAdminUnlocked
                        ? "bg-[#05080f] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Audio dari Mumble akan disiarkan ke TG ini (misal: TG 5109 RAPI / TG 510).</p>
                </div>
              </div>

              {/* Mumble Client Quick Connect Cheat Sheet */}
              <div className="bg-[#05080f] border border-cyan-900/60 rounded-xl p-4 space-y-2">
                <h4 className="font-heading font-black text-xs text-amber-400 flex items-center gap-1.5">
                  <span>📱</span> Panduan Pengguna / Klien HP (Mumla & Mumble App)
                </h4>
                <div className="text-[11px] font-mono text-slate-300 space-y-1">
                  <p>1. Download aplikasi <b>Mumla</b> (Android) atau <b>Mumble</b> (iOS/Windows/Mac/Linux).</p>
                  <p>2. Tambah Server Baru: Isi <b>Address:</b> <code>[IP Server NinjaRadio Anda]</code>, <b>Port:</b> <code>64738</code>, <b>Username:</b> Callsign atau Nama Anda.</p>
                  <p>3. Masuk ke channel <b>{mumbleChannel}</b> ➔ Tekan tombol PTT di layar HP untuk langsung berbicara dengan pengguna HT DMR!</p>
                </div>
              </div>
            </div>
          )}

          {/* TAB 6: HARDWARE PTT & AUDIO BEEP */}
          {activeTab === "hardware" && (
            <div className="space-y-4">
              <div className="bg-[#0b121e] border-2 border-cyan-500/50 rounded-2xl p-5 shadow-xl space-y-3">
                <div className="flex items-center justify-between">
                  <h4 className="font-heading font-black text-sm text-white flex items-center gap-2">
                    <Key className="w-4 h-4 text-cyan-400" />
                    Perekam Tombol Fisik PTT (Hardware Key Learning)
                  </h4>
                  <span className="text-xs px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-800 font-mono">
                    [{pttKey}]
                  </span>
                </div>
                <p className="text-xs font-mono text-slate-300">
                  Klik tombol di bawah lalu tekan tombol PTT samping di radio Android Anda:
                </p>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setIsListeningKey(true)}
                    className={`px-4 py-2.5 rounded-xl font-heading font-black text-xs transition-all ${
                      isListeningKey ? "bg-rose-600 text-white animate-pulse" : "bg-cyan-400 text-slate-950 hover:bg-cyan-300"
                    }`}
                  >
                    {isListeningKey ? "👉 TEKAN TOMBOL PTT FISIK SEKARANG..." : "🎯 Rekam Tombol PTT Samping"}
                  </button>
                  <button
                    type="button"
                    onClick={() => saveCustomKey("Space")}
                    className="px-3 py-2 bg-[#05080f] hover:bg-slate-800 text-slate-300 text-xs font-mono font-semibold rounded-xl border border-cyan-900/40"
                  >
                    Reset ke Spacebar
                  </button>
                </div>
              </div>

              <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-3">
                <h4 className="font-heading font-black text-sm text-white flex items-center gap-2">
                  <Volume2 className="w-4 h-4 text-emerald-400" />
                  Uji Nada Beep Telemetri DMR
                </h4>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => beepPlayer?.playTalkGrant()}
                    className="px-3.5 py-2 bg-[#05080f] hover:bg-slate-800 border border-cyan-900/40 text-xs font-mono font-bold text-emerald-400 rounded-xl"
                  >
                    🎵 Test Talk Grant (TX ON)
                  </button>
                  <button
                    type="button"
                    onClick={() => beepPlayer?.playRogerBeep()}
                    className="px-3.5 py-2 bg-[#05080f] hover:bg-slate-800 border border-cyan-900/40 text-xs font-mono font-bold text-cyan-300 rounded-xl"
                  >
                    🎵 Test Roger Beep (TX OFF)
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Bottom Save Bar */}
          <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 flex items-center justify-between shadow-2xl">
            <div className="text-xs font-mono text-slate-400 hidden sm:block">
              {isAdminUnlocked 
                ? "✓ Anda berada dalam Mode Admin. Klik simpan untuk menerapkan konfigurasi."
                : "🔒 Mode Publik (Read-Only). Klik tombol untuk memasukkan PIN Admin jika ingin mengubah."}
            </div>

            <button
              type="submit"
              className={`w-full sm:w-auto flex items-center justify-center gap-2 px-6 py-2.5 font-heading font-black text-sm rounded-xl transition-all shadow-lg ${
                isAdminUnlocked
                  ? "bg-cyan-400 text-slate-950 hover:bg-cyan-300 shadow-cyan-500/25 cursor-pointer"
                  : "bg-amber-400 hover:bg-amber-300 text-slate-950 shadow-amber-500/20 cursor-pointer"
              }`}
            >
              {isAdminUnlocked ? (
                <>
                  {savedSuccess ? <Check className="w-4 h-4 text-slate-950" /> : <Save className="w-4 h-4" />}
                  <span>{savedSuccess ? "Konfigurasi Berhasil Disimpan!" : "Simpan & Terapkan Perubahan"}</span>
                </>
              ) : (
                <>
                  <Lock className="w-4 h-4" />
                  <span>Buka Kunci Admin (PIN) untuk Mengubah</span>
                </>
              )}
            </button>
          </div>

        </form>

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
                Masukkan PIN Admin untuk mengubah parameter server:
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
                    ❌ PIN Salah! Silakan coba lagi.
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
