"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { 
  Cpu, 
  Radio, 
  Wifi, 
  RefreshCw, 
  Activity, 
  ShieldCheck, 
  Clock, 
  Layers, 
  ArrowUpRight, 
  ArrowDownLeft, 
  Server, 
  Signal, 
  CheckCircle2, 
  AlertCircle,
  Settings,
  Save,
  Sliders,
  X
} from "lucide-react";

import { AdminGuard } from "@/components/AdminGuard";
import { getIsAdminUnlocked, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";

interface HotspotClient {
  ip: string;
  port: number;
  dmr_id: number;
  callsign: string;
  software: string;
  state: string;
  connected_at: number;
  uptime: string;
  last_seen_seconds_ago: number;
  rx_packets: number;
  tx_packets: number;
  last_tg: number;
  last_slot: number;
  options?: string;
}

interface PeerStatus {
  name: string;
  host: string;
  port: number;
  dmr_id: number;
  callsign: string;
  state: string;
  subscribed_tgs: string;
  packets_rx: number;
  packets_tx: number;
  is_connected: boolean;
}

interface ActiveTx {
  src_id: number;
  callsign: string;
  dst_tg: number;
  slot: number;
  duration: number;
  packets: number;
}

interface RecentQso {
  timestamp: number;
  end_time?: number;
  callsign: string;
  src_id: number;
  dst_tg: number;
  slot: number;
  duration: number;
  packets: number;
  loss: string;
}

interface ServerDmrTelemetry {
  online: boolean;
  port: number;
  protocol: string;
  server_callsign: string;
  server_dmr_id: number;
  uptime: string;
  uptime_seconds: number;
  connected_hotspots_count: number;
  total_rx_packets: number;
  total_tx_packets: number;
  active_transmission: ActiveTx | null;
  hotspots: HotspotClient[];
  upstream_peers: PeerStatus[];
  recent_qsos: RecentQso[];
}

interface DmrConfig {
  server_callsign: string;
  server_dmr_id: number;
  default_tg: number;
  parrot_tgs: string;
  color_code: number;
  hotspot_port: number;
  hotspot_password: string;
  tx_freq?: string;
  rx_freq?: string;
  tx_pwr?: number;
  max_hotspots?: number;
}

export default function ServerDmrPage() {
  const { isConnected } = useLiveMonitor();
  const [telemetry, setTelemetry] = useState<ServerDmrTelemetry | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const fetchTelemetry = async () => {
    try {
      const res = await fetch("/api/serverdmr/telemetry");
      if (res.ok) {
        const data = await res.json();
        setTelemetry(data);
      }
    } catch (err) {
      console.error("Failed to fetch DMR Master telemetry:", err);
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

  const formatTime = (ts: number) => {
    if (!ts) return "-";
    const d = new Date(ts * 1000);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  };

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-20 md:pb-8 selection:bg-cyan-500 selection:text-black">
      <Navbar isConnected={isConnected} />

      <AdminGuard
        title="DMR MASTER GATEWAY"
        description="Halaman ini memuat konfigurasi repeater DMR internal, upstream peering rahasia, dan hotspot terdaftar."
        tag="CORE DMR MASTER"
      >
        <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
        {/* Header Section */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0b121e]/80 backdrop-blur-md p-5 rounded-2xl border border-cyan-900/30 shadow-xl">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-cyan-500 to-emerald-400 p-[1.5px] shadow-[0_0_20px_rgba(6,182,212,0.3)]">
              <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center">
                <Cpu className="w-6 h-6 text-cyan-400" />
              </div>
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-white">
                  DMR MASTER SERVER
                </h1>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded-full font-bold bg-cyan-950/80 text-cyan-400 border border-cyan-800">
                  HOMEBREW v1
                </span>
              </div>
              <p className="text-xs sm:text-sm text-slate-400 font-mono mt-0.5">
                Native MMDVM / Pi-Star Master Controller • UDP Port {telemetry?.port || 62031}
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
                {telemetry?.online ? "MASTER: ONLINE" : "MASTER: OFFLINE"}
              </span>
            </div>

            <a
              href="/settings"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-950/70 hover:bg-purple-900/60 text-purple-300 border border-purple-800 text-xs font-mono font-bold transition-all shadow-md hover:scale-[1.02]"
              title="Buka Halaman Pengaturan Lengkap DMR Master (PIN Terlindungi)"
            >
              <Settings className="w-3.5 h-3.5 text-purple-400" />
              <span>PENGATURAN DMR (PIN)</span>
            </a>

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

        {/* Live Active Stream Banner */}
        {telemetry?.active_transmission ? (
          <div className="p-4 sm:p-5 rounded-2xl bg-gradient-to-r from-rose-950/60 via-red-950/40 to-slate-900 border border-rose-500/50 shadow-[0_0_25px_rgba(244,63,94,0.25)] flex flex-col md:flex-row items-start md:items-center justify-between gap-4 animate-pulse">
            <div className="flex items-center gap-3">
              <div className="px-2.5 py-1 rounded bg-rose-500 text-slate-950 font-black font-mono text-xs tracking-wider flex items-center gap-1.5 shadow-md">
                <span className="w-2 h-2 rounded-full bg-slate-950 animate-ping inline-block" />
                ON AIR
              </div>
              <div>
                <div className="text-sm font-mono text-rose-300 font-bold">LIVE DMR TRANSMISSION</div>
                <div className="text-xl sm:text-2xl font-black font-mono text-white tracking-wider flex items-center gap-2">
                  <span>{telemetry.active_transmission.callsign}</span>
                  <span className="text-xs font-normal text-slate-400">
                    (ID: {telemetry.active_transmission.src_id})
                  </span>
                </div>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-3 text-xs font-mono">
              <div className="px-3 py-1.5 rounded-lg bg-slate-900/90 border border-slate-700">
                <span className="text-slate-400 mr-1.5">TARGET TG:</span>
                <span className="text-cyan-400 font-bold">TG {telemetry.active_transmission.dst_tg}</span>
              </div>
              <div className="px-3 py-1.5 rounded-lg bg-slate-900/90 border border-slate-700">
                <span className="text-slate-400 mr-1.5">SLOT:</span>
                <span className="text-emerald-400 font-bold">TS {telemetry.active_transmission.slot}</span>
              </div>
              <div className="px-3 py-1.5 rounded-lg bg-slate-900/90 border border-slate-700">
                <span className="text-slate-400 mr-1.5">DURATION:</span>
                <span className="text-amber-400 font-bold">{telemetry.active_transmission.duration}s</span>
              </div>
              <div className="px-3 py-1.5 rounded-lg bg-slate-900/90 border border-slate-700">
                <span className="text-slate-400 mr-1.5">BURSTS:</span>
                <span className="text-purple-400 font-bold">{telemetry.active_transmission.packets} pkts</span>
              </div>
            </div>
          </div>
        ) : (
          <div className="p-3.5 rounded-2xl bg-[#0b121e]/40 border border-slate-800/80 flex items-center justify-between text-xs font-mono text-slate-400">
            <div className="flex items-center gap-2">
              <Activity className="w-4 h-4 text-emerald-400" />
              <span>CHANNEL STANDBY • MASTER READY FOR INBOUND TRAFFIC</span>
            </div>
            <div className="text-[11px] text-slate-500 hidden sm:block">
              SERVER CALLSIGN: <span className="text-cyan-400 font-bold">{telemetry?.server_callsign || "N1NJA"}</span> (ID: {telemetry?.server_dmr_id || 5109999})
            </div>
          </div>
        )}

        {/* Telemetry Metric Cards */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
          <div className="bg-[#0b121e]/70 border border-cyan-900/30 rounded-xl p-4">
            <div className="text-[11px] font-mono font-bold text-slate-400 flex items-center justify-between">
              <span>CONNECTED HOTSPOTS</span>
              <Radio className="w-4 h-4 text-cyan-400" />
            </div>
            <div className="text-2xl sm:text-3xl font-black font-mono text-white mt-1">
              {telemetry?.connected_hotspots_count || 0}
            </div>
            <div className="text-[10px] font-mono text-emerald-400 mt-1">
              Pi-Star / OpenGD77 / MMDVM
            </div>
          </div>

          <div className="bg-[#0b121e]/70 border border-cyan-900/30 rounded-xl p-4">
            <div className="text-[11px] font-mono font-bold text-slate-400 flex items-center justify-between">
              <span>SERVER UPTIME</span>
              <Clock className="w-4 h-4 text-emerald-400" />
            </div>
            <div className="text-2xl sm:text-3xl font-black font-mono text-white mt-1 truncate">
              {telemetry?.uptime || "0s"}
            </div>
            <div className="text-[10px] font-mono text-slate-400 mt-1">
              Port {telemetry?.port || 62031}/udp
            </div>
          </div>

          <div className="bg-[#0b121e]/70 border border-cyan-900/30 rounded-xl p-4">
            <div className="text-[11px] font-mono font-bold text-slate-400 flex items-center justify-between">
              <span>TOTAL RX BURSTS</span>
              <ArrowDownLeft className="w-4 h-4 text-cyan-400" />
            </div>
            <div className="text-2xl sm:text-3xl font-black font-mono text-cyan-400 mt-1">
              {telemetry?.total_rx_packets?.toLocaleString() || 0}
            </div>
            <div className="text-[10px] font-mono text-slate-400 mt-1">
              Inbound DMR Frames
            </div>
          </div>

          <div className="bg-[#0b121e]/70 border border-cyan-900/30 rounded-xl p-4">
            <div className="text-[11px] font-mono font-bold text-slate-400 flex items-center justify-between">
              <span>TOTAL TX BURSTS</span>
              <ArrowUpRight className="w-4 h-4 text-emerald-400" />
            </div>
            <div className="text-2xl sm:text-3xl font-black font-mono text-emerald-400 mt-1">
              {telemetry?.total_tx_packets?.toLocaleString() || 0}
            </div>
            <div className="text-[10px] font-mono text-slate-400 mt-1">
              Broadcasted DMR Frames
            </div>
          </div>
        </div>

        {/* Connected Hotspots Table */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Signal className="w-5 h-5 text-cyan-400" />
              <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                CONNECTED HOTSPOTS & CLIENTS
              </h2>
            </div>
            <span className="text-xs font-mono text-slate-400">
              {telemetry?.hotspots?.length || 0} ACTIVE CLIENT(S)
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">CALLSIGN</th>
                  <th className="p-3 font-bold">DMR ID</th>
                  <th className="p-3 font-bold">REMOTE ENDPOINT</th>
                  <th className="p-3 font-bold">SOFTWARE</th>
                  <th className="p-3 font-bold">SUBSCRIBED TG</th>
                  <th className="p-3 font-bold">STATUS</th>
                  <th className="p-3 font-bold">UPTIME</th>
                  <th className="p-3 font-bold">LAST SEEN</th>
                  <th className="p-3 font-bold text-right">RX / TX PKTS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {telemetry?.hotspots && telemetry.hotspots.length > 0 ? (
                  telemetry.hotspots.map((hs, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 font-bold text-white flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                        <span className="text-cyan-300 font-black text-sm">{hs.callsign}</span>
                      </td>
                      <td className="p-3 text-slate-300 font-bold">
                        {hs.dmr_id > 9999999 ? (
                          <div className="flex items-center gap-1.5">
                            <span>{Math.floor(hs.dmr_id / 100)}</span>
                            <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-cyan-950 text-cyan-400 border border-cyan-800" title={`Compound ID: ${hs.dmr_id}`}>
                              SSID {String(hs.dmr_id % 100).padStart(2, "0")}
                            </span>
                          </div>
                        ) : (
                          hs.dmr_id
                        )}
                      </td>
                      <td className="p-3 text-slate-400">{hs.ip}:{hs.port}</td>
                      <td className="p-3 text-slate-400">{hs.software}</td>
                      <td className="p-3">
                        <span className="text-cyan-400 font-bold">
                          {hs.last_tg ? `TG ${hs.last_tg} (TS${hs.last_slot || 2})` : (hs.options || "-")}
                        </span>
                      </td>
                      <td className="p-3">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                          {hs.state}
                        </span>
                      </td>
                      <td className="p-3 text-slate-400">{hs.uptime}</td>
                      <td className="p-3 text-slate-400">{hs.last_seen_seconds_ago}s ago</td>
                      <td className="p-3 text-right font-bold text-slate-300">
                        <span className="text-cyan-400">{hs.rx_packets}</span> / <span className="text-emerald-400">{hs.tx_packets}</span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={9} className="p-8 text-center text-slate-500">
                      <Radio className="w-8 h-8 mx-auto mb-2 opacity-40 text-cyan-400" />
                      <div>No hotspots currently connected.</div>
                      <div className="text-[11px] text-slate-600 mt-1">
                        Point your Pi-Star / MMDVMHost to this server IP on port 62031 (Password: {telemetry ? "ninjaradio_pass" : "..."})
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Upstream Peering Status */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Server className="w-5 h-5 text-emerald-400" />
              <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                UPSTREAM PEERS & OPENBRIDGE
              </h2>
            </div>
            <span className="text-xs font-mono text-slate-400">
              {telemetry?.upstream_peers?.length || 0} PEER(S)
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">PEER NAME</th>
                  <th className="p-3 font-bold">REMOTE MASTER</th>
                  <th className="p-3 font-bold">PEER ID / CALL</th>
                  <th className="p-3 font-bold">STATE</th>
                  <th className="p-3 font-bold">SUBSCRIBED TGS</th>
                  <th className="p-3 font-bold text-right">PACKETS RX/TX</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {telemetry?.upstream_peers && telemetry.upstream_peers.length > 0 ? (
                  telemetry.upstream_peers.map((peer, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 font-bold text-white">{peer.name}</td>
                      <td className="p-3 text-slate-400">{peer.host}:{peer.port}</td>
                      <td className="p-3 text-slate-300">{peer.dmr_id} ({peer.callsign})</td>
                      <td className="p-3">
                        <span
                          className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                            peer.is_connected
                              ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                              : peer.state === "STANDBY"
                              ? "bg-amber-950 text-amber-400 border border-amber-800"
                              : "bg-rose-950 text-rose-400 border border-rose-800"
                          }`}
                        >
                          {peer.state}
                        </span>
                      </td>
                      <td className="p-3 text-cyan-400 font-bold">{peer.subscribed_tgs}</td>
                      <td className="p-3 text-right font-bold text-slate-300">
                        {peer.packets_rx} / {peer.packets_tx}
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={6} className="p-6 text-center text-slate-500">
                      Running in Standalone DMR Master mode (no upstream OpenBridge peers configured).
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Last Heard QSO History */}
        <div className="bg-[#0b121e]/80 rounded-2xl border border-cyan-900/30 overflow-hidden shadow-xl">
          <div className="p-4 sm:p-5 border-b border-cyan-900/30 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Activity className="w-5 h-5 text-cyan-400" />
              <h2 className="font-heading font-bold text-base sm:text-lg text-white">
                LAST HEARD QSO LOG (RECENT TRANSMISSIONS)
              </h2>
            </div>
            <span className="text-xs font-mono text-slate-400">
              TOP {telemetry?.recent_qsos?.length || 0}
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
                  <th className="p-3 font-bold">TIME</th>
                  <th className="p-3 font-bold">CALLSIGN</th>
                  <th className="p-3 font-bold">DMR ID</th>
                  <th className="p-3 font-bold">TARGET TG</th>
                  <th className="p-3 font-bold">TIMESLOT</th>
                  <th className="p-3 font-bold">DURATION</th>
                  <th className="p-3 font-bold">PACKETS</th>
                  <th className="p-3 font-bold text-right">LOSS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {telemetry?.recent_qsos && telemetry.recent_qsos.length > 0 ? (
                  telemetry.recent_qsos.map((qso, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/40 transition-colors">
                      <td className="p-3 text-slate-400">{formatTime(qso.timestamp)}</td>
                      <td className="p-3 font-bold text-cyan-300 text-sm">{qso.callsign}</td>
                      <td className="p-3 text-slate-300 font-bold">{qso.src_id}</td>
                      <td className="p-3 text-emerald-400 font-bold">TG {qso.dst_tg}</td>
                      <td className="p-3 text-slate-400">TS {qso.slot}</td>
                      <td className="p-3 text-amber-400 font-bold">{qso.duration}s</td>
                      <td className="p-3 text-slate-300">{qso.packets}</td>
                      <td className="p-3 text-right text-emerald-400 font-bold">{qso.loss}</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={8} className="p-8 text-center text-slate-500">
                      No transmissions recorded yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </main>
      </AdminGuard>
    </div>
  );
}
