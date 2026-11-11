"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import {
  Waves,
  Radio,
  ExternalLink,
  Maximize2,
  RefreshCw,
  Sliders,
  Volume2,
  Signal,
  Info,
  Check,
  Copy,
  ChevronDown,
  ChevronUp,
  Cpu,
  Wifi,
  Sparkles,
} from "lucide-react";
import { beepPlayer } from "@/components/BeepPlayer";

interface BandPreset {
  id: string;
  name: string;
  band: string;
  freqHz: number;
  freqDisplay: string;
  mode: "lsb" | "usb" | "am" | "cw" | "nfm";
  desc: string;
  category: "HF" | "CB" | "VHF" | "AIR";
}

const BAND_PRESETS: BandPreset[] = [
  {
    id: "40m-call",
    name: "40M Call Freq",
    band: "40 Meter",
    freqHz: 7110000,
    freqDisplay: "7.110 MHz",
    mode: "lsb",
    desc: "National Calling Frequency Indonesia (ORARI)",
    category: "HF",
  },
  {
    id: "40m-net",
    name: "40M Nusantara Net",
    band: "40 Meter",
    freqHz: 7070000,
    freqDisplay: "7.070 MHz",
    mode: "lsb",
    desc: "Nusantara Net & Morning Ragchew QSO",
    category: "HF",
  },
  {
    id: "80m-net",
    name: "80M Nusantara Net",
    band: "80 Meter",
    freqHz: 3560000,
    freqDisplay: "3.560 MHz",
    mode: "lsb",
    desc: "Night-time Regional & Inter-Island Net",
    category: "HF",
  },
  {
    id: "20m-dx",
    name: "20M DX International",
    band: "20 Meter",
    freqHz: 14200000,
    freqDisplay: "14.200 MHz",
    mode: "usb",
    desc: "Worldwide DX Voice Calling Frequency",
    category: "HF",
  },
  {
    id: "11m-cb",
    name: "11M CB Ch 31",
    band: "11 Meter (CB)",
    freqHz: 27305000,
    freqDisplay: "27.305 MHz",
    mode: "usb",
    desc: "Indonesian Citizen Band Calling (RAPI / CB)",
    category: "CB",
  },
  {
    id: "2m-call",
    name: "2M VHF Simplex",
    band: "2 Meter (VHF)",
    freqHz: 145000000,
    freqDisplay: "145.000 MHz",
    mode: "nfm",
    desc: "National 2M FM Calling & Emergency Simplex",
    category: "VHF",
  },
  {
    id: "air-guard",
    name: "Aviation Guard / ATC",
    band: "Airband (VHF)",
    freqHz: 121500000,
    freqDisplay: "121.500 MHz",
    mode: "am",
    desc: "International Emergency & Airport ATC Ground",
    category: "AIR",
  },
];

export default function SdrPage() {
  const { isConnected } = useLiveMonitor();
  const [sdrUrl, setSdrUrl] = useState<string>("");
  const [copiedPreset, setCopiedPreset] = useState<string | null>(null);
  const [selectedPreset, setSelectedPreset] = useState<BandPreset | null>(null);
  const [showGuide, setShowGuide] = useState<boolean>(false);
  const [iframeKey, setIframeKey] = useState<number>(0);
  const [activeTab, setActiveTab] = useState<"ALL" | "HF" | "CB" | "VHF" | "AIR">("ALL");

  useEffect(() => {
    if (typeof window !== "undefined") {
      const hostname = window.location.hostname;
      const port = window.location.port;

      if (hostname === "localhost" || hostname === "127.0.0.1") {
        setSdrUrl("http://localhost:8073");
      } else if (hostname.endsWith(".sslip.io")) {
        // Use the SSL subdomain proxy
        setSdrUrl(`https://sdr.${hostname}`);
      } else {
        // Fallback to direct port 8073 or current origin
        setSdrUrl(`http://${hostname}:8073`);
      }
    }
  }, []);

  const handleSelectPreset = (preset: BandPreset) => {
    setSelectedPreset(preset);
    beepPlayer?.playRogerBeep();

    // OpenWebRX URL hash format: #freq=7070000,mod=lsb
    const hashTarget = `#freq=${preset.freqHz},mod=${preset.mode}`;
    if (sdrUrl) {
      const baseUrl = sdrUrl.split("#")[0];
      setSdrUrl(`${baseUrl}${hashTarget}`);
      setIframeKey((prev) => prev + 1);
    }

    navigator.clipboard?.writeText(preset.freqDisplay);
    setCopiedPreset(preset.id);
    setTimeout(() => setCopiedPreset(null), 2500);
  };

  const handleRefreshIframe = () => {
    setIframeKey((prev) => prev + 1);
    beepPlayer?.playRogerBeep();
  };

  const handleOpenNewTab = () => {
    if (sdrUrl) {
      window.open(sdrUrl, "_blank", "noopener,noreferrer");
      beepPlayer?.playTalkGrant();
    }
  };

  const filteredPresets =
    activeTab === "ALL"
      ? BAND_PRESETS
      : BAND_PRESETS.filter((p) => p.category === activeTab);

  return (
    <div className="min-h-screen bg-[#070b12] text-slate-100 flex flex-col font-sans pb-16 md:pb-6">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-[1600px] w-full mx-auto px-3 sm:px-6 py-4 flex flex-col gap-4">
        {/* Top Header Card */}
        <div className="bg-[#0b121e]/90 border border-cyan-900/40 rounded-2xl p-4 sm:p-5 backdrop-blur-xl shadow-[0_10px_30px_rgba(0,0,0,0.5)]">
          <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-cyan-500/20 via-emerald-500/10 to-transparent border border-cyan-500/40 flex items-center justify-center text-cyan-400 shadow-[0_0_20px_rgba(6,182,212,0.25)]">
                <Waves className="w-6 h-6 animate-pulse" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 via-teal-300 to-emerald-400">
                    WEB SDR RECEIVER
                  </h1>
                  <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-emerald-950/80 text-emerald-400 border border-emerald-600/40">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping" />
                    LIVE SPECTRUM
                  </span>
                </div>
                <p className="text-xs font-mono text-slate-400 mt-0.5">
                  Multi-Band Software Defined Radio • Realtime Waterfall & Audio Demodulator (SSB / AM / CW / NFM)
                </p>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="flex flex-wrap items-center gap-2 w-full lg:w-auto">
              <button
                type="button"
                onClick={handleRefreshIframe}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-slate-900/80 hover:bg-slate-800 border border-cyan-900/50 text-cyan-300 text-xs font-mono transition-all"
                title="Reload Waterfall Stream"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                <span>Reload Stream</span>
              </button>

              <button
                type="button"
                onClick={handleOpenNewTab}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 text-xs font-mono font-bold shadow-md shadow-cyan-500/20 transition-all"
                title="Open OpenWebRX in Dedicated Window"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                <span>Pop-out Window</span>
              </button>

              <button
                type="button"
                onClick={() => setShowGuide(!showGuide)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-slate-900/80 hover:bg-slate-800 border border-slate-700 text-slate-300 text-xs font-mono transition-all ml-auto lg:ml-0"
              >
                <Info className="w-3.5 h-3.5 text-cyan-400" />
                <span>Hardware / Source Guide</span>
                {showGuide ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              </button>
            </div>
          </div>

          {/* Quick Frequency Tuning Bar */}
          <div className="mt-4 pt-4 border-t border-cyan-900/30 flex flex-col gap-2.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-[11px] font-mono text-cyan-400 font-bold uppercase tracking-wider flex items-center gap-1.5">
                <Sliders className="w-3 h-3" />
                Quick HF/VHF Presets & Band Tuning:
              </span>

              {/* Band Category Filter */}
              <div className="flex items-center gap-1 bg-slate-950/80 p-0.5 rounded-lg border border-cyan-900/30 text-[10px] font-mono">
                {(["ALL", "HF", "CB", "VHF", "AIR"] as const).map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    onClick={() => setActiveTab(tab)}
                    className={`px-2 py-0.5 rounded transition-all ${
                      activeTab === tab
                        ? "bg-cyan-500 text-slate-950 font-bold"
                        : "text-slate-400 hover:text-cyan-300"
                    }`}
                  >
                    {tab}
                  </button>
                ))}
              </div>
            </div>

            {/* Presets Grid */}
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-7 gap-2">
              {filteredPresets.map((preset) => {
                const isSelected = selectedPreset?.id === preset.id;
                const isCopied = copiedPreset === preset.id;

                return (
                  <button
                    key={preset.id}
                    type="button"
                    onClick={() => handleSelectPreset(preset)}
                    className={`flex flex-col text-left p-2 rounded-xl border transition-all relative overflow-hidden group ${
                      isSelected
                        ? "bg-gradient-to-br from-cyan-950/90 to-emerald-950/80 border-cyan-400 shadow-[0_0_15px_rgba(6,182,212,0.3)]"
                        : "bg-slate-950/60 hover:bg-slate-900/90 border-cyan-900/30 hover:border-cyan-700/60"
                    }`}
                  >
                    <div className="flex items-center justify-between w-full">
                      <span className="text-[10px] font-mono text-slate-400 font-semibold truncate">
                        {preset.band}
                      </span>
                      <span
                        className={`text-[9px] font-mono uppercase px-1 py-0.2 rounded font-bold ${
                          preset.mode === "lsb"
                            ? "bg-blue-950 text-blue-300 border border-blue-800/60"
                            : preset.mode === "usb"
                            ? "bg-purple-950 text-purple-300 border border-purple-800/60"
                            : preset.mode === "am"
                            ? "bg-amber-950 text-amber-300 border border-amber-800/60"
                            : "bg-emerald-950 text-emerald-300 border border-emerald-800/60"
                        }`}
                      >
                        {preset.mode}
                      </span>
                    </div>

                    <div className="text-xs sm:text-sm font-mono font-bold text-cyan-300 tracking-tight mt-1 flex items-center justify-between">
                      <span>{preset.freqDisplay}</span>
                      {isCopied ? (
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                      ) : (
                        <Copy className="w-3 h-3 text-slate-500 opacity-0 group-hover:opacity-100 transition-opacity" />
                      )}
                    </div>

                    <div className="text-[10px] text-slate-400 font-sans truncate mt-0.5">
                      {preset.name}
                    </div>
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        {/* Hardware & Network SDR Guide Accordion (Optional drop-down) */}
        {showGuide && (
          <div className="bg-[#0b121e]/90 border border-cyan-500/40 rounded-2xl p-4 sm:p-5 backdrop-blur-xl animate-in fade-in duration-200">
            <h3 className="text-sm font-mono font-bold text-cyan-300 uppercase tracking-wider flex items-center gap-2 mb-3">
              <Cpu className="w-4 h-4 text-cyan-400" />
              SDR Source Configuration & Hardware Peering
            </h3>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs font-mono">
              {/* Option 1: Network RTL_TCP */}
              <div className="bg-slate-950/80 p-3.5 rounded-xl border border-cyan-900/40 flex flex-col justify-between">
                <div>
                  <div className="flex items-center gap-2 text-cyan-400 font-bold mb-1">
                    <Wifi className="w-4 h-4" />
                    <span>1. Network SDR (rtl_tcp)</span>
                  </div>
                  <p className="text-slate-400 text-[11px] leading-relaxed mb-2">
                    Connect remote RTL-SDR dongles running on Raspberry Pi or home PC to this cloud OpenWebRX instance.
                  </p>
                  <code className="block bg-slate-900 p-2 rounded text-[10px] text-emerald-400 overflow-x-auto">
                    rtl_tcp -a 0.0.0.0 -p 1234
                  </code>
                </div>
                <div className="text-[10px] text-slate-500 mt-2">
                  Add via OpenWebRX Admin &gt; SDR Devices &gt; RTL-TCP.
                </div>
              </div>

              {/* Option 2: SpyServer */}
              <div className="bg-slate-950/80 p-3.5 rounded-xl border border-cyan-900/40 flex flex-col justify-between">
                <div>
                  <div className="flex items-center gap-2 text-purple-400 font-bold mb-1">
                    <Signal className="w-4 h-4" />
                    <span>2. SpyServer Client</span>
                  </div>
                  <p className="text-slate-400 text-[11px] leading-relaxed mb-2">
                    Bandwidth-efficient compressed SDR streaming for Airspy / RTL-SDR over low bandwidth connections.
                  </p>
                  <code className="block bg-slate-900 p-2 rounded text-[10px] text-purple-300 overflow-x-auto">
                    spyserver spyserver.config
                  </code>
                </div>
                <div className="text-[10px] text-slate-500 mt-2">
                  Supports remote KiwiSDR and Airspy network feeds.
                </div>
              </div>

              {/* Option 3: USB Dongle Direct */}
              <div className="bg-slate-950/80 p-3.5 rounded-xl border border-cyan-900/40 flex flex-col justify-between">
                <div>
                  <div className="flex items-center gap-2 text-emerald-400 font-bold mb-1">
                    <Cpu className="w-4 h-4" />
                    <span>3. Physical USB Dongle</span>
                  </div>
                  <p className="text-slate-400 text-[11px] leading-relaxed mb-2">
                    Plug RTL-SDR v4, HackRF, or SDRplay directly to server USB bus and pass through to container.
                  </p>
                  <code className="block bg-slate-900 p-2 rounded text-[10px] text-cyan-300 overflow-x-auto">
                    devices: - &quot;/dev/bus/usb&quot;
                  </code>
                </div>
                <div className="text-[10px] text-slate-500 mt-2">
                  Auto-detected on local bare-metal / SBC hosts.
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Live Waterfall & WebSDR Iframe Container */}
        <div className="flex-1 min-h-[680px] bg-[#05080f] border border-cyan-900/40 rounded-2xl overflow-hidden shadow-2xl relative flex flex-col">
          {/* Iframe Top Status Bar */}
          <div className="bg-[#0b121e] border-b border-cyan-900/40 px-3 py-1.5 flex items-center justify-between text-xs font-mono">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className="text-slate-300 font-bold">OpenWebRX Engine</span>
              <span className="text-slate-500">•</span>
              <span className="text-cyan-400 truncate max-w-xs sm:max-w-md">
                {sdrUrl || "Connecting to SDR service..."}
              </span>
            </div>

            <div className="flex items-center gap-2 text-[11px] text-slate-400">
              <span className="hidden sm:inline">Modulations: LSB | USB | AM | CW | NFM</span>
            </div>
          </div>

          {/* Embedded WebSDR Frame */}
          {sdrUrl ? (
            <iframe
              key={iframeKey}
              src={sdrUrl}
              className="w-full flex-1 border-0 min-h-[650px] bg-[#070b12]"
              title="OpenWebRX Waterfall Spectrum and Receiver"
              allow="autoplay; audio"
              sandbox="allow-same-origin allow-scripts allow-forms allow-popups"
            />
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-slate-400 font-mono">
              <Waves className="w-12 h-12 text-cyan-500/40 animate-pulse mb-3" />
              <p className="text-sm">Inisialisasi OpenWebRX WebSDR engine...</p>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
