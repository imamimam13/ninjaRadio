"use client";

import React, { useState, useEffect, useRef } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor, registerAudioRxListener, AudioRxData } from "@/hooks/useLiveMonitor";
import { AudioVisualizer } from "@/components/AudioVisualizer";
import { 
  Radio, 
  Wifi, 
  Cpu, 
  Server, 
  Zap, 
  Layers, 
  Volume2, 
  VolumeX,
  Signal, 
  ShieldCheck, 
  Clock, 
  Search,
  SlidersHorizontal,
  Activity,
  Terminal,
  RefreshCw,
  Globe,
  Gauge,
  X
} from "lucide-react";

export default function DashboardPage() {
  const { lastHeard, activeTx, incomingAudio, isConnected } = useLiveMonitor();
  const [isMuted, setIsMuted] = useState<boolean>(false);
  const rxAudioCtxRef = useRef<AudioContext | null>(null);
  const rxInputNodeRef = useRef<AudioNode | null>(null);
  const rxNextTimeRef = useRef<number>(0);

  // Autoplay Policy: Unlock and prepare Web AudioContext on first user gesture
  const ensureAudioUnlocked = () => {
    try {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioCtx) return;

      if (!rxAudioCtxRef.current) {
        const ctx = new AudioCtx();

        // 1. Communications Low-pass filter (3400 Hz) to eliminate vocoder quantization hiss
        const lpf = ctx.createBiquadFilter();
        lpf.type = "lowpass";
        lpf.frequency.value = 3400;
        lpf.Q.value = 0.707;

        // 2. High-pass filter (250 Hz) to eliminate DC offset and sub-bass rumble
        const hpf = ctx.createBiquadFilter();
        hpf.type = "highpass";
        hpf.frequency.value = 250;
        hpf.Q.value = 0.707;

        // 3. Clear voice gain
        const gainNode = ctx.createGain();
        gainNode.gain.value = 1.8;

        // 4. Dynamics Compressor: prevents digital clipping & distortion
        const comp = ctx.createDynamicsCompressor();
        comp.threshold.value = -12;
        comp.knee.value = 6;
        comp.ratio.value = 4;
        comp.attack.value = 0.003;
        comp.release.value = 0.15;

        // Pipeline: hpf -> lpf -> gainNode -> comp -> destination
        hpf.connect(lpf);
        lpf.connect(gainNode);
        gainNode.connect(comp);
        comp.connect(ctx.destination);

        rxAudioCtxRef.current = ctx;
        rxInputNodeRef.current = hpf;
      }

      if (rxAudioCtxRef.current.state === "suspended") {
        rxAudioCtxRef.current.resume().catch(() => {});
      }
    } catch (e) {
      console.warn("Dashboard audio unlock note:", e);
    }
  };

  useEffect(() => {
    const handleGesture = () => {
      ensureAudioUnlocked();
    };
    window.addEventListener("click", handleGesture, { passive: true });
    window.addEventListener("touchstart", handleGesture, { passive: true });
    window.addEventListener("pointerdown", handleGesture, { passive: true });
    window.addEventListener("keydown", handleGesture, { passive: true });
    return () => {
      window.removeEventListener("click", handleGesture);
      window.removeEventListener("touchstart", handleGesture);
      window.removeEventListener("pointerdown", handleGesture);
      window.removeEventListener("keydown", handleGesture);
    };
  }, []);

  const isMutedRef = useRef(isMuted);
  useEffect(() => {
    isMutedRef.current = isMuted;
  }, [isMuted]);

  const activeTxRef = useRef(activeTx);
  useEffect(() => {
    activeTxRef.current = activeTx;
  }, [activeTx]);

  const lastChunkRef = useRef<string>("");

  // Real-time Audio Dispatcher (bypasses React state batching to receive 100% of packets)
  const playAudioChunk = (audioChunk: AudioRxData) => {
    if (!audioChunk || !audioChunk.pcm || isMutedRef.current || (activeTxRef.current as any)?.is_tx) return;
    // Selective Audio Gate: If a specific TG is selected on Dashboard, only play audio matching that TG!
    if (selectedTgFilterRef.current !== null && audioChunk.tg !== selectedTgFilterRef.current) return;
    if (lastChunkRef.current === audioChunk.pcm) return;
    lastChunkRef.current = audioChunk.pcm;

    try {
      ensureAudioUnlocked();
      const ctx = rxAudioCtxRef.current;
      if (!ctx) return;
      if (ctx.state === "suspended") {
        ctx.resume().catch(() => {});
        return;
      }

      const binaryStr = atob(audioChunk.pcm);
      const len = binaryStr.length;
      const safeLen = len - (len % 2);
      if (safeLen < 2) return;

      const bytes = new Uint8Array(safeLen);
      for (let i = 0; i < safeLen; i++) {
        bytes[i] = binaryStr.charCodeAt(i);
      }
      const int16 = new Int16Array(bytes.buffer, 0, safeLen / 2);

      const float32 = new Float32Array(int16.length);
      for (let i = 0; i < int16.length; i++) {
        float32[i] = int16[i] / 32768.0;
      }

      // Micro cosine envelope (4-6 samples fade-in & fade-out) to eliminate chunk boundary pops
      const fadeLen = Math.min(6, Math.floor(float32.length / 4));
      for (let i = 0; i < fadeLen; i++) {
        const factor = 0.5 * (1 - Math.cos((Math.PI * i) / fadeLen));
        float32[i] *= factor;
        float32[float32.length - 1 - i] *= factor;
      }

      const audioBuffer = ctx.createBuffer(1, float32.length, 8000);
      audioBuffer.copyToChannel(float32, 0);

      const source = ctx.createBufferSource();
      source.buffer = audioBuffer;
      if (rxInputNodeRef.current) {
        source.connect(rxInputNodeRef.current);
      } else {
        source.connect(ctx.destination);
      }

      const now = ctx.currentTime;
      if (rxNextTimeRef.current < now || rxNextTimeRef.current > now + 0.40) {
        rxNextTimeRef.current = now + 0.12;
      }
      source.start(rxNextTimeRef.current);
      rxNextTimeRef.current += audioBuffer.duration;
    } catch (e) {
      console.warn("Dashboard audio playback note:", e);
    }
  };

  useEffect(() => {
    const unsub = registerAudioRxListener(playAudioChunk);
    return () => unsub();
  }, []);

  const [searchTerm, setSearchTerm] = useState<string>("");
  const [filterMode, setFilterMode] = useState<string>("ALL");
  const [selectedTgFilter, setSelectedTgFilter] = useState<number | null>(null);
  const selectedTgFilterRef = useRef<number | null>(null);

  useEffect(() => {
    selectedTgFilterRef.current = selectedTgFilter;
    if (typeof window !== "undefined") {
      if (selectedTgFilter !== null) {
        localStorage.setItem("ninjaradio_dashboard_audio_tg", selectedTgFilter.toString());
      } else {
        localStorage.removeItem("ninjaradio_dashboard_audio_tg");
      }
    }
  }, [selectedTgFilter]);

  useEffect(() => {
    if (typeof window !== "undefined") {
      const savedTg = localStorage.getItem("ninjaradio_dashboard_audio_tg");
      if (savedTg && !isNaN(Number(savedTg))) {
        setSelectedTgFilter(Number(savedTg));
      }
    }
  }, []);
  const [vizMode, setVizMode] = useState<"oscilloscope" | "smeter" | "bars">("oscilloscope");
  const [stats, setStats] = useState({
    server_callsign: "ONLINE",
    server_id: 510,
    uptime: "0s",
    active_transmissions: 0,
    connected_hotspots: 0,
    connected_peers: 0,
    active_bridges: 0,
    cpu_usage: 0,
    ram_usage: 0
  });

  const [showClientsModal, setShowClientsModal] = useState(false);
  const [clientList, setClientList] = useState<any[]>([]);
  const [isLoadingClients, setIsLoadingClients] = useState(false);

  const fetchClients = () => {
    setIsLoadingClients(true);
    fetch("/api/clients")
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data)) setClientList(data);
        setIsLoadingClients(false);
      })
      .catch(() => setIsLoadingClients(false));
  };

  const handleOpenClientsModal = () => {
    setShowClientsModal(true);
    fetchClients();
  };

  useEffect(() => {
    const fetchStatus = () => {
      fetch("/api/status")
        .then((res) => res.json())
        .then((data) => setStats(data))
        .catch(() => {});
    };
    fetchStatus();
    const interval = setInterval(fetchStatus, 4000);
    return () => clearInterval(interval);
  }, []);

  const activeTgs = Array.from(new Set(lastHeard.map((item) => item.talkgroup))).filter(Boolean);

  const filteredLastHeard = lastHeard.filter((item) => {
    const matchesMode = filterMode === "ALL" || item.mode.toUpperCase() === filterMode.toUpperCase();
    const matchesTg = selectedTgFilter === null || item.talkgroup === selectedTgFilter;
    const matchesSearch =
      item.callsign.toLowerCase().includes(searchTerm.toLowerCase()) ||
      item.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      item.talkgroup.toString().includes(searchTerm);
    return matchesMode && matchesTg && matchesSearch;
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
                    <button
                      onClick={() => {
                        ensureAudioUnlocked();
                        setIsMuted(!isMuted);
                      }}
                      className={`flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[11px] font-mono font-bold transition-all ${
                        isMuted 
                          ? "bg-rose-950/80 border border-rose-500/50 text-rose-300 hover:bg-rose-900" 
                          : "bg-cyan-950/80 border border-cyan-500/50 text-cyan-300 hover:bg-cyan-900 shadow-[0_0_10px_rgba(6,182,212,0.3)]"
                      }`}
                      title="Klik untuk Mute / Unmute Audio Live RX di Speaker Browser"
                    >
                      {isMuted ? <VolumeX className="w-3 h-3 text-rose-400" /> : <Volume2 className="w-3 h-3 text-cyan-400 animate-pulse" />}
                      <span>{isMuted ? "SPEAKER: MUTE" : "SPEAKER: ON"}</span>
                    </button>
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
          <div 
            onClick={handleOpenClientsModal}
            className="bg-[#0c1322] border border-cyan-900/40 hover:border-cyan-400 rounded-2xl p-4 flex flex-col justify-between shadow-lg transition-all cursor-pointer group"
          >
            <div className="flex items-center justify-between text-slate-400 text-xs font-mono">
              <span className="font-bold group-hover:text-cyan-300 transition-colors">KLIEN WEB & HOTSPOT</span>
              <Wifi className="w-4 h-4 text-cyan-400 group-hover:animate-pulse" />
            </div>
            <div className="mt-3 flex items-baseline justify-between">
              <span className="text-3xl font-black text-white font-mono">{(stats as any).web_clients || stats.connected_hotspots || 1}</span>
              <span className="text-[10px] text-cyan-300 font-mono font-bold px-2 py-0.5 rounded bg-cyan-950/80 border border-cyan-800 flex items-center gap-1 group-hover:bg-cyan-900 transition-colors">
                🔍 Cek Detail
              </span>
            </div>
            <div className="text-[10px] text-slate-500 font-mono mt-1">
              {(stats as any).web_clients || 1} Sesi Terhubung (Klik untuk Lihat)
            </div>
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

          {/* Active Talkgroups Filter Chips Bar & Audio Speaker Gate */}
          <div className="px-4 py-2.5 bg-[#060a12] border-b border-cyan-900/30 flex items-center gap-2 overflow-x-auto text-xs font-mono">
            <span className="text-[10px] text-slate-400 uppercase font-bold shrink-0">
              Filter TG & Audio:
            </span>
            <button
              onClick={() => setSelectedTgFilter(null)}
              className={`px-2.5 py-1 rounded-lg text-[11px] font-bold shrink-0 transition-all ${
                selectedTgFilter === null
                  ? "bg-cyan-400 text-slate-950 font-black shadow-sm"
                  : "bg-slate-900 text-slate-400 hover:text-white border border-slate-800"
              }`}
            >
              Semua TG ({lastHeard.length})
            </button>
            {activeTgs.map((tg) => {
              const count = lastHeard.filter((i) => i.talkgroup === tg).length;
              const isSelected = selectedTgFilter === tg;
              return (
                <button
                  key={tg}
                  onClick={() => setSelectedTgFilter(isSelected ? null : tg)}
                  className={`px-2.5 py-1 rounded-lg text-[11px] font-bold shrink-0 flex items-center gap-1.5 transition-all ${
                    isSelected
                      ? "bg-cyan-400 text-slate-950 font-black shadow-sm"
                      : "bg-[#0b1322] border border-cyan-900/60 text-cyan-300 hover:border-cyan-400"
                  }`}
                  title={`Klik untuk memonitor hanya TG ${tg}`}
                >
                  <span>TG {tg}</span>
                  <span className={`px-1 py-0.2 rounded text-[9px] ${isSelected ? "bg-slate-900 text-cyan-300" : "bg-cyan-950 text-cyan-400"}`}>
                    {count}
                  </span>
                </button>
              );
            })}
            {selectedTgFilter !== null ? (
              <span className="ml-auto text-[10px] text-amber-300 font-bold bg-amber-950/60 border border-amber-800/80 px-2.5 py-0.5 rounded-full shrink-0 flex items-center gap-1 shadow-sm">
                <span>🔊</span>
                <span>Lock Audio: TG {selectedTgFilter}</span>
              </span>
            ) : (
              <span className="ml-auto text-[10px] text-emerald-400 font-bold bg-emerald-950/60 border border-emerald-800/80 px-2.5 py-0.5 rounded-full shrink-0 flex items-center gap-1 shadow-sm">
                <span>🔊</span>
                <span>Audio: Semua TG</span>
              </span>
            )}
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
                        <button
                          type="button"
                          onClick={() => setSelectedTgFilter(selectedTgFilter === item.talkgroup ? null : item.talkgroup)}
                          className={`px-2 py-0.5 rounded border transition-colors cursor-pointer ${
                            selectedTgFilter === item.talkgroup
                              ? "bg-cyan-500 text-slate-950 font-black border-cyan-400"
                              : "bg-slate-900 border-slate-700 text-cyan-300 hover:border-cyan-400"
                          }`}
                          title={`Klik untuk filter TG ${item.talkgroup}`}
                        >
                          TG {item.talkgroup}
                        </button>
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

      {/* CONNECTED CLIENTS & HOTSPOTS MODAL */}
      {showClientsModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1626] border-2 border-cyan-500/60 rounded-3xl max-w-2xl w-full p-5 sm:p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-cyan-900/40 pb-3">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/20 border border-cyan-500 flex items-center justify-center">
                  <Wifi className="w-5 h-5 text-cyan-400" />
                </div>
                <div>
                  <h3 className="font-heading font-black text-sm text-white">Daftar Klien & Perangkat Terhubung</h3>
                  <p className="text-[11px] font-mono text-slate-400">
                    {clientList.length} sesi browser & client aktif memantau server
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={fetchClients}
                  className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-400 transition-colors"
                  title="Refresh Daftar"
                >
                  <RefreshCw className={`w-4 h-4 ${isLoadingClients ? "animate-spin" : ""}`} />
                </button>
                <button
                  type="button"
                  onClick={() => setShowClientsModal(false)}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Client List Table */}
            <div className="overflow-x-auto max-h-72 overflow-y-auto">
              <table className="w-full text-left text-xs font-mono">
                <thead>
                  <tr className="border-b border-slate-800 text-slate-400 text-[11px]">
                    <th className="py-2 px-2">Aplikasi / Halaman</th>
                    <th className="py-2 px-2">Perangkat / OS</th>
                    <th className="py-2 px-2">IP Address</th>
                    <th className="py-2 px-2">Waktu Konek</th>
                    <th className="py-2 px-2">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60 text-slate-300">
                  {clientList.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="py-6 text-center text-slate-500">
                        Tidak ada sesi aktif saat ini.
                      </td>
                    </tr>
                  ) : (
                    clientList.map((client, idx) => (
                      <tr key={idx} className="hover:bg-slate-800/30">
                        <td className="py-2.5 px-2 font-bold text-cyan-300">
                          {client.app}
                        </td>
                        <td className="py-2.5 px-2 text-slate-300">
                          {client.device}
                        </td>
                        <td className="py-2.5 px-2 font-mono text-slate-400">
                          <code>{client.ip}</code>
                        </td>
                        <td className="py-2.5 px-2 text-slate-400">
                          {client.connected_at} UTC
                        </td>
                        <td className="py-2.5 px-2">
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-emerald-950 text-emerald-400 border border-emerald-800">
                            🟢 Online
                          </span>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="pt-2 border-t border-slate-800 flex items-center justify-between">
              <span className="text-[10px] font-mono text-slate-500">
                Data diperbarui secara real-time via WebSocket
              </span>
              <button
                type="button"
                onClick={() => setShowClientsModal(false)}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold rounded-xl"
              >
                Tutup
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
