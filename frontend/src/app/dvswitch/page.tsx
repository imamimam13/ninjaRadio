"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { 
  Terminal, 
  Cpu, 
  RefreshCw, 
  CheckCircle2, 
  AlertCircle, 
  Smartphone, 
  ArrowRight, 
  Activity, 
  Radio, 
  Layers, 
  Wifi, 
  RotateCw 
} from "lucide-react";

import { AdminGuard } from "@/components/AdminGuard";
import { getIsAdminUnlocked, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";

interface DaemonInfo {
  name: string;
  status: string;
  pid?: number | null;
  target_master?: string;
  port?: number;
  description: string;
}

interface UsrpClient {
  ip: string;
  port: number;
  type: string;
  last_seen_seconds_ago: number;
  is_active: boolean;
}

interface DVSwitchTelemetry {
  online: boolean;
  daemons: {
    analog_bridge: DaemonInfo;
    mmdvm_bridge: DaemonInfo;
    md380_emu: DaemonInfo;
  };
  ports: {
    ambe_rx: number;
    ambe_tx: number;
    usrp_tx: number;
    usrp_rx: number;
    usrp_gateway: number;
  };
  live_carrier: {
    is_transmitting: boolean;
    operator_callsign: string;
    operator_dmr_id: number;
    tx_tg: number;
    tx_slot: number;
    rx_active: boolean;
    rx_callsign: string;
    rx_src: number;
    rx_tg: number;
    rx_slot: number;
  };
  connected_usrp_clients: UsrpClient[];
  packets: {
    rx_count: number;
    tx_count: number;
  };
}

export default function DvSwitchPage() {
  const { isConnected } = useLiveMonitor();
  const [telemetry, setTelemetry] = useState<DVSwitchTelemetry | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [reloadingBridge, setReloadingBridge] = useState(false);
  const [reloadMessage, setReloadMessage] = useState<string | null>(null);

  const fetchTelemetry = async () => {
    try {
      const res = await fetch("/api/dvswitch/telemetry");
      if (res.ok) {
        const data = await res.json();
        setTelemetry(data);
      }
    } catch (err) {
      console.error("Failed to fetch DVSwitch telemetry:", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    const checkAndFetch = () => {
      if (getIsAdminUnlocked()) {
        fetchTelemetry();
      }
    };

    checkAndFetch();
    const interval = setInterval(checkAndFetch, 2500);
    window.addEventListener(ADMIN_AUTH_EVENT, checkAndFetch);

    return () => {
      clearInterval(interval);
      window.removeEventListener(ADMIN_AUTH_EVENT, checkAndFetch);
    };
  }, []);

  const handleManualRefresh = () => {
    if (!getIsAdminUnlocked()) return;
    setRefreshing(true);
    fetchTelemetry();
  };

  const handleReloadBridges = async () => {
    if (!getIsAdminUnlocked()) return;
    setReloadingBridge(true);
    setReloadMessage(null);
    try {
      const res = await fetch("/api/dvswitch/reload", { method: "POST" });
      const data = await res.json();
      setReloadMessage(data.message || "Bridges reloaded successfully.");
      await fetchTelemetry();
    } catch (err) {
      setReloadMessage("Reload command failed.");
    } finally {
      setReloadingBridge(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-20 md:pb-8 selection:bg-cyan-500 selection:text-black">
      <Navbar isConnected={isConnected} />

      <AdminGuard
        title="DVSWITCH DAEMON GATEWAY"
        description="Halaman ini memuat kontrol internal daemon Analog_Bridge, MMDVM_Bridge, port USRP, dan AMBE transcoder."
        tag="DVSWITCH BRIDGES"
      >
        <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
        {/* Header Section */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0b121e]/80 backdrop-blur-md p-5 rounded-2xl border border-cyan-900/30 shadow-xl">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-amber-400 to-cyan-500 p-[1.5px] shadow-[0_0_20px_rgba(245,158,11,0.25)]">
              <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center">
                <Terminal className="w-6 h-6 text-amber-400" />
              </div>
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-white">
                  DVSWITCH SUITE MONITOR
                </h1>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded-full font-bold bg-amber-950/80 text-amber-400 border border-amber-800">
                  NATIVE STACK
                </span>
              </div>
              <p className="text-xs sm:text-sm text-slate-400 font-mono mt-0.5">
                Analog_Bridge (Vocoder) • MMDVM_Bridge (DMR) • USRP Gateway Port {telemetry?.ports?.usrp_gateway || 32345}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3 self-end md:self-center">
            <button
              onClick={handleReloadBridges}
              disabled={reloadingBridge}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-amber-950/60 hover:bg-amber-900/50 text-amber-400 border border-amber-800/60 text-xs font-mono font-bold transition-all disabled:opacity-50"
              title="Restart Bridges with dynamic config sync"
            >
              <RotateCw className={`w-3.5 h-3.5 ${reloadingBridge ? "animate-spin" : ""}`} />
              <span>RELOAD BRIDGES</span>
            </button>

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

        {reloadMessage && (
          <div className="p-3 bg-cyan-950/60 border border-cyan-800 rounded-xl text-xs font-mono text-cyan-300">
            {reloadMessage}
          </div>
        )}

        {/* Daemons Health Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {/* Analog Bridge */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
            <div className="flex items-center justify-between">
              <span className="font-heading font-black text-white text-base">ANALOG_BRIDGE</span>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                  telemetry?.daemons?.analog_bridge?.status === "RUNNING"
                    ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                    : "bg-rose-950 text-rose-400 border border-rose-800"
                }`}
              >
                {telemetry?.daemons?.analog_bridge?.status || "STOPPED"}
              </span>
            </div>
            <p className="text-xs text-slate-400 font-mono">
              {telemetry?.daemons?.analog_bridge?.description || "AMBE <-> USRP PCM Vocoder Transcoder"}
            </p>
            <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-1 text-slate-300">
              <div className="flex justify-between">
                <span className="text-slate-500">PCM Audio In:</span>
                <span className="text-cyan-400 font-bold">Port 32000 (RX)</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">PCM Audio Out:</span>
                <span className="text-cyan-400 font-bold">Port 32001 (TX)</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">AMBE Frames:</span>
                <span className="text-emerald-400 font-bold">Port 31100 / 31103</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Process PID:</span>
                <span className="text-slate-300">{telemetry?.daemons?.analog_bridge?.pid || "System / Active"}</span>
              </div>
            </div>
          </div>

          {/* MMDVM Bridge */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
            <div className="flex items-center justify-between">
              <span className="font-heading font-black text-white text-base">MMDVM_BRIDGE</span>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                  telemetry?.daemons?.mmdvm_bridge?.status === "RUNNING"
                    ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                    : "bg-rose-950 text-rose-400 border border-rose-800"
                }`}
              >
                {telemetry?.daemons?.mmdvm_bridge?.status || "STOPPED"}
              </span>
            </div>
            <p className="text-xs text-slate-400 font-mono">
              {telemetry?.daemons?.mmdvm_bridge?.description || "HomeBrew DMR Network Bridge"}
            </p>
            <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-1 text-slate-300">
              <div className="flex justify-between">
                <span className="text-slate-500">Target DMR Master:</span>
                <span className="text-cyan-400 font-bold">{telemetry?.daemons?.mmdvm_bridge?.target_master || "127.0.0.1:62031"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">AMBE Bus:</span>
                <span className="text-emerald-400 font-bold">Port 31103 / 31100</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">DMR Protocol:</span>
                <span className="text-slate-300 font-bold">HomeBrew v1</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Process PID:</span>
                <span className="text-slate-300">{telemetry?.daemons?.mmdvm_bridge?.pid || "System / Active"}</span>
              </div>
            </div>
          </div>

          {/* md380-emu Vocoder */}
          <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl space-y-3">
            <div className="flex items-center justify-between">
              <span className="font-heading font-black text-white text-base">MD380_EMU / OP25</span>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                  telemetry?.daemons?.md380_emu?.status === "RUNNING"
                    ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                    : "bg-amber-950 text-amber-400 border border-amber-800"
                }`}
              >
                {telemetry?.daemons?.md380_emu?.status || "BUILT-IN"}
              </span>
            </div>
            <p className="text-xs text-slate-400 font-mono">
              AMBE Vocoder Software Synthesizer & Codec Emulation
            </p>
            <div className="pt-2 border-t border-slate-800 text-xs font-mono space-y-1 text-slate-300">
              <div className="flex justify-between">
                <span className="text-slate-500">Vocoder Type:</span>
                <span className="text-cyan-400 font-bold">AMBE+2 (DMR/YSF/D-Star)</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Emulator Port:</span>
                <span className="text-slate-300 font-bold">2470/udp</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Transcoding:</span>
                <span className="text-emerald-400 font-bold">Bit-Exact 8kHz PCM</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Hardware Dongle:</span>
                <span className="text-slate-400">Not Required (Soft-Emu)</span>
              </div>
            </div>
          </div>
        </div>

        {/* Audio & Port Flow Diagram */}
        <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl">
          <div className="flex items-center gap-2 mb-4">
            <Layers className="w-5 h-5 text-cyan-400" />
            <h2 className="font-heading font-bold text-base text-white">
              DVSWITCH BI-DIRECTIONAL AUDIO & CONTROL BUS ARCHITECTURE
            </h2>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-3 text-xs font-mono">
            {/* Step 1 */}
            <div className="p-4 rounded-xl bg-slate-900/70 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between text-cyan-400 font-bold">
                <span>1. CLIENT INGRESS</span>
                <Smartphone className="w-4 h-4" />
              </div>
              <p className="text-slate-400 text-[11px]">
                DVSwitch Mobile Android or Web Audio browser client connects on UDP port 32345.
              </p>
              <div className="text-[10px] text-emerald-400 bg-slate-950 p-2 rounded border border-slate-800">
                PORT 32345 (USRP PCM)
              </div>
            </div>

            {/* Step 2 */}
            <div className="p-4 rounded-xl bg-slate-900/70 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between text-cyan-400 font-bold">
                <span>2. PCM ROUTER</span>
                <Cpu className="w-4 h-4" />
              </div>
              <p className="text-slate-400 text-[11px]">
                NinjaRadio Core injects 32-byte USRP header & dispatches to Analog_Bridge.
              </p>
              <div className="text-[10px] text-emerald-400 bg-slate-950 p-2 rounded border border-slate-800">
                PORT 32000 (TX) / 32001 (RX)
              </div>
            </div>

            {/* Step 3 */}
            <div className="p-4 rounded-xl bg-slate-900/70 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between text-cyan-400 font-bold">
                <span>3. VOCODER ENGINE</span>
                <Activity className="w-4 h-4" />
              </div>
              <p className="text-slate-400 text-[11px]">
                Analog_Bridge encodes PCM to AMBE frames (or decodes incoming AMBE).
              </p>
              <div className="text-[10px] text-emerald-400 bg-slate-950 p-2 rounded border border-slate-800">
                PORT 31103 (TX) / 31100 (RX)
              </div>
            </div>

            {/* Step 4 */}
            <div className="p-4 rounded-xl bg-slate-900/70 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between text-cyan-400 font-bold">
                <span>4. DMR NETWORK</span>
                <Radio className="w-4 h-4" />
              </div>
              <p className="text-slate-400 text-[11px]">
                MMDVM_Bridge formats DMR TDMA bursts and sends to DMR Master server.
              </p>
              <div className="text-[10px] text-emerald-400 bg-slate-950 p-2 rounded border border-slate-800">
                PORT 62031 (MMDVM / HomeBrew)
              </div>
            </div>
          </div>
        </div>

        {/* Connected DVSwitch Mobile Android Clients */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Smartphone className="w-5 h-5 text-amber-400" />
              <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                CONNECTED DVSWITCH MOBILE & USRP CLIENTS
              </h2>
            </div>
            <span className="text-xs font-mono text-slate-400">
              {telemetry?.connected_usrp_clients?.length || 0} REGISTERED CLIENT(S)
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">CLIENT IP & PORT</th>
                  <th className="p-3 font-bold">TYPE / PROTOCOL</th>
                  <th className="p-3 font-bold">LAST SEEN</th>
                  <th className="p-3 font-bold text-right">ACTIVITY STATUS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {telemetry?.connected_usrp_clients && telemetry.connected_usrp_clients.length > 0 ? (
                  telemetry.connected_usrp_clients.map((client, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 font-bold text-white flex items-center gap-2">
                        <Smartphone className="w-4 h-4 text-amber-400" />
                        <span>{client.ip}:{client.port}</span>
                      </td>
                      <td className="p-3 text-slate-300">{client.type}</td>
                      <td className="p-3 text-slate-400">{client.last_seen_seconds_ago}s ago</td>
                      <td className="p-3 text-right">
                        <span
                          className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                            (client as any).is_transmitting
                              ? "bg-rose-950 text-rose-400 border border-rose-800 animate-pulse"
                              : client.is_active
                              ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                              : "bg-slate-900 text-slate-500 border border-slate-800"
                          }`}
                        >
                          {(client as any).is_transmitting
                            ? "TRANSMITTING"
                            : client.is_active
                            ? "CONNECTED / ONLINE"
                            : "IDLE / STANDBY"}
                        </span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={4} className="p-8 text-center text-slate-500">
                      <Smartphone className="w-8 h-8 mx-auto mb-2 opacity-40 text-amber-400" />
                      <div>No DVSwitch Mobile clients registered right now.</div>
                      <div className="text-[11px] text-slate-600 mt-1">
                        In DVSwitch Mobile Android app: Configure Mode: USRP, Host: (Your Server IP), Port: 32345.
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Live Carrier Telemetry Box */}
        <div className="bg-[#0b121e]/80 border border-cyan-900/30 rounded-2xl p-5 shadow-xl">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Activity className="w-5 h-5 text-emerald-400" />
              <h2 className="font-heading font-bold text-base text-white">
                LIVE CARRIER & PTT TRANSMITTER STATE
              </h2>
            </div>
            <span
              className={`px-2.5 py-1 rounded text-xs font-mono font-bold ${
                telemetry?.live_carrier?.is_transmitting
                  ? "bg-rose-500 text-slate-950 animate-pulse"
                  : "bg-slate-900 text-slate-400 border border-slate-800"
              }`}
            >
              {telemetry?.live_carrier?.is_transmitting ? "PTT ACTIVE (TRANSMITTING)" : "CARRIER IDLE"}
            </span>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs font-mono">
            <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800">
              <span className="text-slate-500 block">Operator Callsign:</span>
              <span className="text-white font-bold text-sm">
                {telemetry?.live_carrier?.operator_callsign || "N1NJA"}
              </span>
            </div>
            <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800">
              <span className="text-slate-500 block">Operator DMR ID:</span>
              <span className="text-cyan-400 font-bold text-sm">
                {telemetry?.live_carrier?.operator_dmr_id || 5109999}
              </span>
            </div>
            <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800">
              <span className="text-slate-500 block">Default TX TG & Slot:</span>
              <span className="text-emerald-400 font-bold text-sm">
                TG {telemetry?.live_carrier?.tx_tg || 510} (TS {telemetry?.live_carrier?.tx_slot || 2})
              </span>
            </div>
            <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800">
              <span className="text-slate-500 block">Total Bridge Packets:</span>
              <span className="text-amber-400 font-bold text-sm">
                RX {telemetry?.packets?.rx_count || 0} / TX {telemetry?.packets?.tx_count || 0}
              </span>
            </div>
          </div>
        </div>
      </main>
      </AdminGuard>
    </div>
  );
}
