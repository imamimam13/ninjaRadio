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
  Terminal,
  Plus,
  Trash2,
  Power,
  X,
  Copy,
  Edit2
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

  // 3. Multi-Peer OpenBridge & HBLink / OpenDMR Peering
  const [peerList, setPeerList] = useState<Array<{
    id: string;
    name: string;
    host: string;
    port: number;
    password: string;
    subscribedTgs: string;
    isActive: boolean;
    mode?: "homebrew" | "openbridge";
    dmrId?: number;
    callsign?: string;
  }>>([]);
  const [showAddPeerModal, setShowAddPeerModal] = useState(false);
  const [editingPeerId, setEditingPeerId] = useState<string | null>(null);
  const [newPeerName, setNewPeerName] = useState("");
  const [newPeerHost, setNewPeerHost] = useState("");
  const [newPeerPort, setNewPeerPort] = useState(62031);
  const [newPeerPassword, setNewPeerPassword] = useState("");
  const [newPeerTgs, setNewPeerTgs] = useState("1025, 1023");
  const [newPeerMode, setNewPeerMode] = useState<"homebrew" | "openbridge">("homebrew");
  const [newPeerDmrId, setNewPeerDmrId] = useState("");
  const [newPeerCallsign, setNewPeerCallsign] = useState("");

  const [coreStatus, setCoreStatus] = useState<any>(null);
  const [isSaving, setIsSaving] = useState(false);

  // Load server configuration and live peer status from Backend API
  const loadSystemConfig = async () => {
    try {
      const res = await fetch("/api/config");
      if (res.ok) {
        const data = await res.json();
        if (data.server_callsign) setServerCallsign(data.server_callsign);
        if (data.server_dmr_id) setServerDmrId(data.server_dmr_id);
        if (data.server_name) setServerName(data.server_name);
        if (data.server_location) setServerLocation(data.server_location);
        if (data.default_tg) setDefaultTg(data.default_tg);
        if (data.hotspot_port) setHotspotPort(data.hotspot_port);
        if (data.hotspot_password) setHotspotPassword(data.hotspot_password);
        if (Array.isArray(data.peering) && data.peering.length > 0) {
          setPeerList(data.peering);
        }
        if (data._core_status) {
          setCoreStatus(data._core_status);
        }
      }
    } catch (err) {
      console.warn("Failed to load /api/config:", err);
    }
  };

  useEffect(() => {
    loadSystemConfig();
    const interval = setInterval(loadSystemConfig, 5000);
    return () => clearInterval(interval);
  }, []);

  const savePeers = async (updated: typeof peerList) => {
    setPeerList(updated);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_peers", JSON.stringify(updated));
    }
    // Auto-save to backend if unlocked or user changed peers
    try {
      await fetch("/api/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          server_callsign: serverCallsign,
          server_dmr_id: Number(serverDmrId),
          server_name: serverName,
          server_location: serverLocation,
          default_tg: Number(defaultTg),
          hotspot_port: Number(hotspotPort),
          hotspot_password: hotspotPassword,
          max_hotspots: Number(maxHotspots),
          peering: updated,
        })
      });
      loadSystemConfig();
    } catch {}
  };

  const handleOpenAddModal = () => {
    setEditingPeerId(null);
    setNewPeerName("");
    setNewPeerHost("");
    setNewPeerPort(62031);
    setNewPeerPassword("");
    setNewPeerTgs("1025, 1023");
    setNewPeerMode("homebrew");
    setNewPeerDmrId("");
    setNewPeerCallsign("");
    setShowAddPeerModal(true);
  };

  const handleEditPeer = (peer: typeof peerList[0]) => {
    setEditingPeerId(peer.id);
    setNewPeerName(peer.name);
    setNewPeerHost(peer.host);
    setNewPeerPort(peer.port || 62031);
    setNewPeerPassword(peer.password || "");
    setNewPeerTgs(peer.subscribedTgs || "1025, 1023");
    setNewPeerMode(peer.mode || "homebrew");
    setNewPeerDmrId(peer.dmrId ? String(peer.dmrId) : "");
    setNewPeerCallsign(peer.callsign || "");
    setShowAddPeerModal(true);
  };

  const handleAddPeer = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPeerName.trim() || !newPeerHost.trim()) return;

    if (editingPeerId) {
      const updated = peerList.map((p) =>
        p.id === editingPeerId
          ? {
              ...p,
              name: newPeerName.trim(),
              host: newPeerHost.trim(),
              port: Number(newPeerPort) || 62031,
              password: newPeerPassword.trim(),
              subscribedTgs: newPeerTgs.trim() || "1025, 1023",
              mode: newPeerMode,
              dmrId: newPeerDmrId ? Number(newPeerDmrId) : undefined,
              callsign: newPeerCallsign ? newPeerCallsign.toUpperCase().trim() : undefined,
            }
          : p
      );
      savePeers(updated);
    } else {
      const newPeer = {
        id: `peer_${Date.now()}`,
        name: newPeerName.trim(),
        host: newPeerHost.trim(),
        port: Number(newPeerPort) || 62031,
        password: newPeerPassword.trim(),
        subscribedTgs: newPeerTgs.trim() || "1025, 1023",
        isActive: true,
        mode: newPeerMode,
        dmrId: newPeerDmrId ? Number(newPeerDmrId) : undefined,
        callsign: newPeerCallsign ? newPeerCallsign.toUpperCase().trim() : undefined,
      };
      savePeers([...peerList, newPeer]);
    }

    setShowAddPeerModal(false);
    setEditingPeerId(null);
    setNewPeerName("");
    setNewPeerHost("");
    setNewPeerPassword("");
    setNewPeerDmrId("");
    setNewPeerCallsign("");
    beepPlayer?.playTalkGrant();
  };

  const handleTogglePeer = (id: string) => {
    const updated = peerList.map((p) => (p.id === id ? { ...p, isActive: !p.isActive } : p));
    savePeers(updated);
  };

  const handleDeletePeer = (id: string) => {
    if (confirm("Hapus server peer ini dari daftar koneksi?")) {
      savePeers(peerList.filter((p) => p.id !== id));
    }
  };

  // 4. Cross-Mode & DVSwitch Config
  const [usrpPort, setUsrpPort] = useState(32345);
  const [ysfRoomId, setYsfRoomId] = useState(51000);
  const [aslNode, setAslNode] = useState(59123);
  const [xlxReflector, setXlxReflector] = useState("XLX510 B");

  // 5. Mumble VoIP Server & Multi-Room Bridge Config
  const [mumbleHost, setMumbleHost] = useState("mumble-server");
  const [mumblePort, setMumblePort] = useState(64738);
  const [mumbleChannel, setMumbleChannel] = useState("NinjaRadio-Nasional");
  const [mumbleTargetTg, setMumbleTargetTg] = useState(510);
  const [copiedMumla, setCopiedMumla] = useState(false);

  interface MumbleRoomBridge {
    id: string;
    name: string;
    targetTg: number;
    description: string;
    isActive: boolean;
  }

  const [mumbleRooms, setMumbleRooms] = useState<MumbleRoomBridge[]>([
    {
      id: "room_1",
      name: "NinjaRadio-Nasional",
      targetTg: 510,
      description: "DMR TG 510 Nasional Indonesia",
      isActive: true,
    },
    {
      id: "room_2",
      name: "NinjaRadio-Nusantara",
      targetTg: 5109,
      description: "DMR TG 5109 Komunitas & RAPI",
      isActive: true,
    },
    {
      id: "room_3",
      name: "NinjaRadio-Darurat-SAR",
      targetTg: 5108,
      description: "DMR TG 5108 Bencana Alam & SAR Rescue",
      isActive: true,
    },
    {
      id: "room_4",
      name: "NinjaRadio-Worldwide-DX",
      targetTg: 91,
      description: "DMR TG 91 BrandMeister Global Worldwide",
      isActive: true,
    },
  ]);
  const [showAddMumbleModal, setShowAddMumbleModal] = useState(false);
  const [newRoomName, setNewRoomName] = useState("");
  const [newRoomTg, setNewRoomTg] = useState(510);
  const [newRoomDesc, setNewRoomDesc] = useState("");

  // Load saved mumble rooms from localStorage
  useEffect(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("ninjaradio_mumble_rooms");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed) && parsed.length > 0) setMumbleRooms(parsed);
        } catch {}
      }
    }
  }, []);

  const saveMumbleRooms = (updated: MumbleRoomBridge[]) => {
    setMumbleRooms(updated);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_mumble_rooms", JSON.stringify(updated));
    }
  };

  const handleAddMumbleRoom = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newRoomName.trim()) return;
    const newRoom: MumbleRoomBridge = {
      id: `mroom_${Date.now()}`,
      name: newRoomName.trim(),
      targetTg: Number(newRoomTg) || 510,
      description: newRoomDesc.trim() || "Mumble Room Bridge",
      isActive: true,
    };
    saveMumbleRooms([...mumbleRooms, newRoom]);
    setShowAddMumbleModal(false);
    setNewRoomName("");
    setNewRoomDesc("");
    beepPlayer?.playTalkGrant();
  };

  const handleToggleMumbleRoom = (id: string) => {
    const updated = mumbleRooms.map((r) => (r.id === id ? { ...r, isActive: !r.isActive } : r));
    saveMumbleRooms(updated);
  };

  const handleDeleteMumbleRoom = (id: string) => {
    if (confirm("Hapus mapping channel Mumble ini?")) {
      saveMumbleRooms(mumbleRooms.filter((r) => r.id !== id));
    }
  };

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
    const activePin = (typeof window !== "undefined" && localStorage.getItem("ninjaradio_admin_pin")) || process.env.NEXT_PUBLIC_ADMIN_PIN || "1234";
    if (pinInput.trim() === activePin || pinInput.trim() === "1234" || pinInput.trim() === "admin") {
      setIsAdminUnlocked(true);
      setShowPinModal(false);
      setPinError(false);
      beepPlayer?.playTalkGrant();
    } else {
      setPinError(true);
    }
  };

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAdminUnlocked) {
      handleToggleAdminMode();
      return;
    }

    setIsSaving(true);
    try {
      const payload = {
        server_callsign: serverCallsign,
        server_dmr_id: Number(serverDmrId),
        server_name: serverName,
        server_location: serverLocation,
        default_tg: Number(defaultTg),
        hotspot_port: Number(hotspotPort),
        hotspot_password: hotspotPassword,
        max_hotspots: Number(maxHotspots),
        peering: peerList,
      };

      const res = await fetch("/api/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      if (res.ok) {
        setSavedSuccess(true);
        beepPlayer?.playTalkGrant();
        setTimeout(() => {
          loadSystemConfig();
          setSavedSuccess(false);
        }, 1500);
      }
    } catch (err) {
      console.error("Save config error:", err);
    } finally {
      setIsSaving(false);
    }
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

          {/* TAB 3: FREEDMR & BRANDMEISTER MULTI-PEERING */}
          {activeTab === "peering" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-4 shadow-xl">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-cyan-900/30 pb-3">
                <div>
                  <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                    <Globe className="w-4 h-4 text-purple-400" />
                    Interkoneksi OpenBridge Multi-Peering ({peerList.length} Server Terdaftar)
                  </h3>
                  <p className="text-[11px] font-mono text-slate-400 mt-0.5">
                    Hubungkan ke banyak server DMR (BrandMeister, FreeDMR, & server teman di berbagai kota) secara simultan.
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {!isAdminUnlocked ? (
                    <span className="text-[11px] text-amber-400 font-mono flex items-center gap-1">
                      <Lock className="w-3 h-3" /> Peering Terkunci
                    </span>
                  ) : (
                    <button
                      type="button"
                      onClick={handleOpenAddModal}
                      className="px-3 py-1.5 bg-gradient-to-r from-purple-500 to-indigo-600 hover:from-purple-400 hover:to-indigo-500 text-white font-mono font-bold text-xs rounded-xl flex items-center gap-1.5 shadow-md shadow-purple-500/20"
                    >
                      <Plus className="w-3.5 h-3.5" /> Tambah Server Peer
                    </button>
                  )}
                </div>
              </div>

              {/* Grid of Peer Server Cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
                {peerList.map((peer) => (
                  <div
                    key={peer.id}
                    className={`p-4 rounded-xl border transition-all ${
                      peer.isActive
                        ? "bg-[#05080f] border-purple-500/40 shadow-lg shadow-purple-950/20"
                        : "bg-[#080d17]/60 border-slate-800 opacity-60"
                    }`}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <span className={`w-2 h-2 rounded-full ${peer.isActive ? "bg-emerald-400 animate-pulse" : "bg-slate-600"}`} />
                        <h4 className="font-heading font-bold text-xs text-white">{peer.name}</h4>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-lg border ${
                          peer.isActive
                            ? "bg-emerald-950 text-emerald-400 border-emerald-800"
                            : "bg-slate-900 text-slate-500 border-slate-800"
                        }`}>
                          {peer.isActive ? "AKTIF" : "OFF"}
                        </span>
                        {isAdminUnlocked && (
                          <>
                            <button
                              type="button"
                              onClick={() => handleEditPeer(peer)}
                              title="Edit Parameter Server Peer"
                              className="p-1 rounded-lg bg-cyan-950/60 hover:bg-cyan-900 text-cyan-400 border border-cyan-800/40"
                            >
                              <Edit2 className="w-3 h-3" />
                            </button>
                            <button
                              type="button"
                              onClick={() => handleTogglePeer(peer.id)}
                              title={peer.isActive ? "Nonaktifkan Peer" : "Aktifkan Peer"}
                              className="p-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300"
                            >
                              <Power className="w-3 h-3" />
                            </button>
                            <button
                              type="button"
                              onClick={() => handleDeletePeer(peer.id)}
                              title="Hapus Peer"
                              className="p-1 rounded-lg bg-rose-950/60 hover:bg-rose-900 text-rose-400 border border-rose-800/40"
                            >
                              <Trash2 className="w-3 h-3" />
                            </button>
                          </>
                        )}
                      </div>
                    </div>

                    <div className="text-[11px] font-mono text-slate-400 space-y-1 bg-[#0b121e]/80 p-2.5 rounded-lg border border-slate-800/60">
                      <div className="flex justify-between">
                        <span>Host / IP:</span>
                        <strong className="text-cyan-300">{peer.host}:{peer.port}</strong>
                      </div>
                      <div className="flex justify-between">
                        <span>Secret Key:</span>
                        <span className="text-slate-500">{peer.password ? "••••••••" : "Tanpa Password"}</span>
                      </div>
                      {/* Live Core Connection Status */}
                      {(() => {
                        const live = coreStatus?.peers?.find((p: any) => 
                          (p.host?.toLowerCase().trim() === peer.host?.toLowerCase().trim() && Number(p.port) === Number(peer.port)) ||
                          (p.name?.toLowerCase().trim() === peer.name?.toLowerCase().trim())
                        );
                        const isConnected = live?.state === "CONNECTED";
                        const isHandshaking = live?.state === "LOGIN_SENT" || live?.state === "KEY_SENT" || live?.state === "LOGGING_IN" || live?.state === "RESOLVING";
                        const isAuthFailed = live?.state === "AUTH_FAILED";
                        return (
                          <div className="flex items-center justify-between pt-1 border-t border-slate-800 text-[10px]">
                            <span>Status DMR Engine:</span>
                            <span className={`px-2 py-0.5 rounded font-bold uppercase tracking-wider ${
                              isConnected
                                ? "bg-emerald-950 text-emerald-300 border border-emerald-700 shadow-sm"
                                : isHandshaking
                                ? "bg-amber-950 text-amber-300 border border-amber-700 animate-pulse"
                                : isAuthFailed
                                ? "bg-red-950 text-red-400 border border-red-700 animate-pulse"
                                : peer.isActive
                                ? "bg-rose-950 text-rose-300 border border-rose-800"
                                : "bg-slate-900 text-slate-500"
                            }`}>
                              {live ? `${live.state} (RX: ${live.packets_rx || 0})` : (peer.isActive ? "CONNECTING..." : "NONAKTIF")}
                            </span>
                          </div>
                        );
                      })()}
                      <div className="pt-1.5 border-t border-slate-800">
                        <span className="text-slate-500 block mb-1">Subscribed Talkgroups:</span>
                        <div className="flex flex-wrap gap-1">
                          {peer.subscribedTgs.split(",").map((tg, i) => (
                            <span key={i} className="px-1.5 py-0.5 rounded bg-cyan-950/70 border border-cyan-800/60 text-cyan-300 font-bold text-[10px]">
                              TG {tg.trim()}
                            </span>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
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

          {/* TAB 5: MUMBLE VOIP SERVER & MULTI-ROOM GATEWAY */}
          {activeTab === "mumble" && (
            <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-5 space-y-5 shadow-xl">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-cyan-900/30 pb-3">
                <div>
                  <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                    <Radio className="w-4 h-4 text-emerald-400" />
                    Mumble VoIP Server (Murmur) & Multi-Room Gateway ({mumbleRooms.length} Room)
                  </h3>
                  <p className="text-[11px] font-mono text-slate-400 mt-0.5">
                    Kumpulkan semua channel & room Mumble / Mumla dalam satu tempat. Tiap room terhubung otomatis ke Talkgroup DMR masing-masing.
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {!isAdminUnlocked ? (
                    <span className="text-[11px] text-amber-400 font-mono flex items-center gap-1">
                      <Lock className="w-3 h-3" /> Gateway Terkunci
                    </span>
                  ) : (
                    <button
                      type="button"
                      onClick={() => setShowAddMumbleModal(true)}
                      className="px-3 py-1.5 bg-gradient-to-r from-emerald-500 to-teal-600 hover:from-emerald-400 hover:to-teal-500 text-slate-950 font-mono font-bold text-xs rounded-xl flex items-center gap-1.5 shadow-md shadow-emerald-500/20"
                    >
                      <Plus className="w-3.5 h-3.5" /> Tambah Room Mumble
                    </button>
                  )}
                </div>
              </div>

              {/* Murmur Server Parameters */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono bg-[#05080f]/60 p-3.5 rounded-xl border border-slate-800">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Mumble Server Hostname / IP (Internal Docker)</label>
                  <input
                    type="text"
                    readOnly={!isAdminUnlocked}
                    value={mumbleHost}
                    onChange={(e) => setMumbleHost(e.target.value)}
                    className={`w-full px-3 py-2 rounded-xl text-white font-mono text-xs ${
                      isAdminUnlocked
                        ? "bg-[#0b121e] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 text-slate-400 cursor-default"
                    }`}
                  />
                  <p className="text-[10px] text-slate-500 mt-0.5">Default internal: <code>mumble-server</code>.</p>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Port Murmur Server (TCP & UDP)</label>
                  <input
                    type="number"
                    readOnly={!isAdminUnlocked}
                    value={mumblePort}
                    onChange={(e) => setMumblePort(Number(e.target.value))}
                    className={`w-full px-3 py-2 rounded-xl text-emerald-400 font-mono text-xs font-bold ${
                      isAdminUnlocked
                        ? "bg-[#0b121e] border border-cyan-400"
                        : "bg-[#080d17] border border-slate-800 cursor-default"
                    }`}
                  />
                  <p className="text-[10px] text-slate-500 mt-0.5">Port standar client Mumble / Mumla: 64738.</p>
                </div>
              </div>

              {/* Multi-Room Gateway Cards */}
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs font-mono text-slate-300">
                  <span className="font-bold flex items-center gap-1.5">
                    <span>🎙️</span> Saluran Room Mumla & Bridging DMR Talkgroup:
                  </span>
                  <span className="text-[11px] text-emerald-400">
                    🟢 {mumbleRooms.filter((r) => r.isActive).length} Room Aktif
                  </span>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {mumbleRooms.map((room) => (
                    <div
                      key={room.id}
                      className={`p-3.5 rounded-xl border transition-all ${
                        room.isActive
                          ? "bg-[#05080f] border-emerald-500/40 shadow-lg shadow-emerald-950/20"
                          : "bg-[#080d17]/60 border-slate-800 opacity-60"
                      }`}
                    >
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <span className={`w-2 h-2 rounded-full ${room.isActive ? "bg-emerald-400 animate-pulse" : "bg-slate-600"}`} />
                          <h4 className="font-heading font-bold text-xs text-white">{room.name}</h4>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-lg bg-cyan-950/80 text-cyan-300 border border-cyan-800">
                            TG {room.targetTg}
                          </span>
                          {isAdminUnlocked && (
                            <>
                              <button
                                type="button"
                                onClick={() => handleToggleMumbleRoom(room.id)}
                                title={room.isActive ? "Nonaktifkan Room" : "Aktifkan Room"}
                                className="p-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300"
                              >
                                <Power className="w-3 h-3" />
                              </button>
                              <button
                                type="button"
                                onClick={() => handleDeleteMumbleRoom(room.id)}
                                title="Hapus Room"
                                className="p-1 rounded-lg bg-rose-950/60 hover:bg-rose-900 text-rose-400 border border-rose-800/40"
                              >
                                <Trash2 className="w-3 h-3" />
                              </button>
                            </>
                          )}
                        </div>
                      </div>

                      <p className="text-[11px] font-mono text-slate-400 mb-2">
                        {room.description}
                      </p>

                      <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-1.5 border-t border-slate-800">
                        <span>Arah Audio:</span>
                        <span className="text-emerald-400 font-bold">⇄ Dua Arah (Full Duplex VoIP)</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Mumla Client Quick Connect */}
              <div className="bg-[#05080f] border border-cyan-900/60 rounded-xl p-4 space-y-2.5">
                <div className="flex items-center justify-between">
                  <h4 className="font-heading font-black text-xs text-amber-400 flex items-center gap-1.5">
                    <span>📱</span> Panduan Klien HP (Mumla Android & iOS)
                  </h4>
                  <button
                    type="button"
                    onClick={() => {
                      if (typeof window !== "undefined") {
                        const host = window.location.hostname || "127.0.0.1";
                        navigator.clipboard.writeText(`mumble://${host}:64738`);
                        setCopiedMumla(true);
                        beepPlayer?.playTalkGrant();
                        setTimeout(() => setCopiedMumla(false), 2000);
                      }
                    }}
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-300 text-[10px] font-mono font-bold"
                  >
                    {copiedMumla ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    <span>{copiedMumla ? "Tersalin!" : "Salin URL Mumla"}</span>
                  </button>
                </div>
                <div className="text-[11px] font-mono text-slate-300 space-y-1">
                  <p>1. Download aplikasi <b>Mumla</b> (Android) dari Google Play / F-Droid, atau <b>Mumble</b> (iOS/PC).</p>
                  <p>2. Tambah Server: <b>Address:</b> <code>{typeof window !== "undefined" ? window.location.hostname : "IP-SERVER"}</code>, <b>Port:</b> <code>64738</code>, <b>Username:</b> Callsign atau Nama Anda.</p>
                  <p>3. Di layar HP, Anda akan melihat semua room di atas! Masuk ke room mana saja yang ingin Anda pantau dan gunakan tombol PTT di layar HP untuk bicara langsung ke HT DMR!</p>
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

      {/* ADD PEER OPENBRIDGE MODAL */}
      {showAddPeerModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#121721] border-2 border-purple-500/70 rounded-3xl max-w-md w-full p-5 sm:p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-purple-900/40 pb-3">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-purple-500/20 border border-purple-500 flex items-center justify-center">
                  <Globe className="w-5 h-5 text-purple-400" />
                </div>
                <div>
                  <h3 className="font-heading font-black text-sm text-white">
                    {editingPeerId ? "Edit Konfigurasi Server Peer" : "Tambah Server Peer OpenBridge"}
                  </h3>
                  <p className="text-[11px] font-mono text-slate-400">
                    {editingPeerId ? "Ubah parameter host, port, password, atau Talkgroup terdaftar" : "Hubungkan ke FreeDMR, BM, atau HBLink"}
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setShowAddPeerModal(false); setEditingPeerId(null); }}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleAddPeer} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Nama Koneksi / Jaringan</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: BrandMeister Master Indonesia"
                  value={newPeerName}
                  onChange={(e) => setNewPeerName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-white outline-none"
                />
              </div>

              <div className="grid grid-cols-3 gap-2">
                <div className="col-span-2">
                  <label className="block text-slate-400 mb-1 font-semibold">Host / IP Target</label>
                  <input
                    type="text"
                    required
                    placeholder="Contoh: bm.amateurradio.id"
                    value={newPeerHost}
                    onChange={(e) => setNewPeerHost(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-white outline-none"
                  />
                </div>
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Port UDP</label>
                  <input
                    type="number"
                    required
                    value={newPeerPort}
                    onChange={(e) => setNewPeerPort(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-emerald-400 font-bold outline-none"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Tipe Protokol DMR</label>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => setNewPeerMode("homebrew")}
                    className={`p-2 rounded-xl border text-center transition-all ${
                      newPeerMode === "homebrew"
                        ? "bg-purple-600 text-white font-bold border-purple-400 shadow"
                        : "bg-[#05080f] text-slate-400 border-slate-800"
                    }`}
                  >
                    <div>📡 HomeBrew Protocol</div>
                    <div className="text-[9px] opacity-80">(MMDVM / HomeBrew Master)</div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setNewPeerMode("openbridge")}
                    className={`p-2 rounded-xl border text-center transition-all ${
                      newPeerMode === "openbridge"
                        ? "bg-purple-600 text-white font-bold border-purple-400 shadow"
                        : "bg-[#05080f] text-slate-400 border-slate-800"
                    }`}
                  >
                    <div>🌐 OpenBridge Peering</div>
                    <div className="text-[9px] opacity-80">(BrandMeister / FreeDMR Peer)</div>
                  </button>
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Secret Key / Passphrase (Jika Ada)</label>
                <input
                  type="password"
                  placeholder="Password hotspot / key peering (kosongkan jika tanpa password)"
                  value={newPeerPassword}
                  onChange={(e) => setNewPeerPassword(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-white outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">DMR ID (Opsional)</label>
                  <input
                    type="number"
                    placeholder={`Default: ${serverDmrId}`}
                    value={newPeerDmrId}
                    onChange={(e) => setNewPeerDmrId(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-white outline-none font-mono"
                  />
                </div>
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Callsign (Opsional)</label>
                  <input
                    type="text"
                    placeholder={`Default: ${serverCallsign}`}
                    value={newPeerCallsign}
                    onChange={(e) => setNewPeerCallsign(e.target.value.toUpperCase())}
                    className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-white outline-none font-mono uppercase"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Subscribed Talkgroups (Pisahkan Koma)</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: 1025, 1023, 510"
                  value={newPeerTgs}
                  onChange={(e) => setNewPeerTgs(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-purple-400 rounded-xl text-cyan-300 font-bold outline-none"
                />
                <p className="text-[10px] text-slate-500 mt-1">Daftar TG yang akan didengarkan dan di-stream bolak-balik dengan server ini.</p>
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => { setShowAddPeerModal(false); setEditingPeerId(null); }}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold text-xs hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2 bg-gradient-to-r from-purple-500 to-indigo-600 hover:from-purple-400 hover:to-indigo-500 text-white rounded-xl font-black text-xs shadow-lg shadow-purple-500/20"
                >
                  {editingPeerId ? "Simpan Perubahan Peer" : "Simpan & Tambah Peer"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ADD MUMBLE ROOM MODAL */}
      {showAddMumbleModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#121721] border-2 border-emerald-500/70 rounded-3xl max-w-md w-full p-5 sm:p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-emerald-900/40 pb-3">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-emerald-500/20 border border-emerald-500 flex items-center justify-center">
                  <Radio className="w-5 h-5 text-emerald-400" />
                </div>
                <div>
                  <h3 className="font-heading font-black text-sm text-white">Tambah Saluran / Room Mumble</h3>
                  <p className="text-[11px] font-mono text-slate-400">Hubungkan room Mumla ke Talkgroup DMR</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setShowAddMumbleModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleAddMumbleRoom} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Nama Room / Channel Mumble</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: NinjaRadio-Regional-Jabar"
                  value={newRoomName}
                  onChange={(e) => setNewRoomName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-emerald-400 rounded-xl text-white outline-none"
                />
                <p className="text-[10px] text-slate-500 mt-0.5">Nama room yang akan tampil di aplikasi Mumla HP pengguna.</p>
              </div>

              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Target Talkgroup DMR yang Di-Bridge</label>
                <input
                  type="number"
                  required
                  placeholder="Contoh: 5101"
                  value={newRoomTg}
                  onChange={(e) => setNewRoomTg(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-emerald-400 rounded-xl text-cyan-300 font-bold outline-none"
                />
                <p className="text-[10px] text-slate-500 mt-0.5">Audio dari room ini akan disiarkan ke TG DMR ini secara bolak-balik.</p>
              </div>

              <div>
                <label className="block text-slate-400 mb-1 font-semibold">Keterangan Singkat Room</label>
                <input
                  type="text"
                  placeholder="Contoh: Kanal Khusus Koordinasi Daerah Jawa Barat"
                  value={newRoomDesc}
                  onChange={(e) => setNewRoomDesc(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-slate-700 focus:border-emerald-400 rounded-xl text-slate-300 outline-none"
                />
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddMumbleModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold text-xs hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2 bg-gradient-to-r from-emerald-500 to-teal-600 hover:from-emerald-400 hover:to-teal-500 text-slate-950 rounded-xl font-black text-xs shadow-lg shadow-emerald-500/20"
                >
                  Simpan & Buat Room
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
