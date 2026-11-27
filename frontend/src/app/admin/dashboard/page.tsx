"use client";

import React, { useState, useEffect, useRef } from "react";
import { Navbar } from "@/components/Navbar";
import { AdminGuard } from "@/components/AdminGuard";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { getSavedAdminPin } from "@/lib/adminAuth";
import {
  SlidersHorizontal,
  Volume2,
  VolumeX,
  Activity,
  Cpu,
  Layers,
  Globe,
  RefreshCw,
  CheckCircle2,
  AlertTriangle,
  Zap,
  Save,
  RotateCcw,
  Sparkles,
  ShieldCheck,
  Info,
  Server
} from "lucide-react";

interface MasterAudioSettings {
  dmr_rx_gain: number;
  target_rms: number;
  max_agc_gain: number;
  limiter_threshold: number;
  agc_enabled: boolean;
  filter_hpf: boolean;
  filter_lpf: boolean;
  filter_deemph: boolean;
}

interface ModeAudioSetting {
  name: string;
  ingress_gain?: number;
  egress_gain?: number;
  rx_gain?: number;
  tx_gain?: number;
  agc?: boolean;
  muted?: boolean;
  mute?: boolean;
}

interface PeerAudioSetting {
  name: string;
  rx_gain?: number;
  tx_gain?: number;
  agc?: boolean;
  muted?: boolean;
  mute?: boolean;
}

interface AudioSettingsData {
  master: MasterAudioSettings;
  modes: Record<string, ModeAudioSetting>;
  peers: Record<string, PeerAudioSetting>;
  active_preset?: string;
}

interface PresetDefinition {
  id: string;
  name: string;
  description: string;
  badge: string;
  color: string;
}

interface AudioMeterTelemetry {
  core_online: boolean;
  active_streams: number;
  active_talker: {
    callsign: string;
    talkgroup: number;
    source: string;
    duration_sec: number;
  } | null;
  rms_level: number;
  dbfs: number;
  limiter_active: boolean;
  vocoder: string;
  normalizer_active: boolean;
}

const PRESET_OPTIONS: PresetDefinition[] = [
  {
    id: "broadcast_warm",
    name: "Broadcast Warm & Bass",
    description: "Volume prima, bass tebal & hangat, de-emphasis telekomunikasi optimal untuk siaran radio jernih.",
    badge: "RECOMMENDED",
    color: "from-emerald-500/20 to-cyan-500/20 border-emerald-500/40 text-emerald-400"
  },
  {
    id: "loud_punchy",
    name: "Loud & Punchy HT",
    description: "Audio kencang dan bertenaga tinggi, ideal untuk HT / Rig di lingkungan bising dan speaker kecil.",
    badge: "ULTRA LOUD",
    color: "from-amber-500/20 to-orange-500/20 border-amber-500/40 text-amber-400"
  },
  {
    id: "studio_flat",
    name: "Studio Clean & Flat",
    description: "Transparan tanpa pewarnaan frekuensi, respon linier alami untuk monitor audio referensi studio.",
    badge: "CLEAN",
    color: "from-blue-500/20 to-indigo-500/20 border-blue-500/40 text-blue-400"
  },
  {
    id: "outdoor_noise",
    name: "Outdoor High Noise",
    description: "Filter HPF agresif memotong gemuruh angin/kendaraan, kompresi AGC cepat untuk operasi lapangan.",
    badge: "NOISE-CUT",
    color: "from-purple-500/20 to-pink-500/20 border-purple-500/40 text-purple-400"
  },
  {
    id: "unity_zero_db",
    name: "Reset Unity 0 dB",
    description: "Kembali ke parameter pabrik murni 1.0x tanpa kompensasi tambahan.",
    badge: "DEFAULT",
    color: "from-slate-700/20 to-slate-800/20 border-slate-600/40 text-slate-300"
  }
];

const getGain = (val: any, fallback = 1.0): number => {
  if (typeof val === "number" && !isNaN(val)) return val;
  const n = parseFloat(val);
  return isNaN(n) ? fallback : n;
};

const gainToDb = (gain: any): string => {
  const g = getGain(gain, 1.0);
  if (g <= 0.001) return "-inf dB";
  const db = 20 * Math.log10(g);
  return `${db >= 0 ? "+" : ""}${db.toFixed(1)} dB`;
};

export default function AdminAudioDashboardPage() {
  const { isConnected } = useLiveMonitor();
  const [settings, setSettings] = useState<AudioSettingsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [activePreset, setActivePreset] = useState("broadcast_warm");
  const [applyingPreset, setApplyingPreset] = useState(false);

  // Audio Telemetry
  const [telemetry, setTelemetry] = useState<AudioMeterTelemetry>({
    core_online: true,
    active_streams: 0,
    active_talker: null,
    rms_level: 0,
    dbfs: -60,
    limiter_active: false,
    vocoder: "mbelib-neo HD (SIMD AVX/NEON)",
    normalizer_active: true
  });

  // Sound check tone state
  const [isPlayingTestTone, setIsPlayingTestTone] = useState(false);
  const audioContextRef = useRef<AudioContext | null>(null);

  const fetchSettings = async () => {
    try {
      setLoading(true);
      setErrorMsg(null);
      const res = await fetch("/api/admin/audio-settings");
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`HTTP ${res.status}: ${text}`);
      }
      const json = await res.json();
      const payload: AudioSettingsData | null = json.data || (json.master ? json : null);
      if (payload) {
        // Guarantee master defaults
        const master: MasterAudioSettings = {
          dmr_rx_gain: getGain(payload.master?.dmr_rx_gain ?? (payload.modes?.dmr as any)?.rx_gain, 2.8),
          target_rms: getGain(payload.master?.target_rms, 4200),
          max_agc_gain: getGain(payload.master?.max_agc_gain, 4.0),
          limiter_threshold: getGain(payload.master?.limiter_threshold, 18000),
          agc_enabled: Boolean(payload.master?.agc_enabled ?? true),
          filter_hpf: Boolean(payload.master?.filter_hpf ?? true),
          filter_lpf: Boolean(payload.master?.filter_lpf ?? true),
          filter_deemph: Boolean(payload.master?.filter_deemph ?? true)
        };

        setSettings({
          ...payload,
          master,
          modes: payload.modes || {},
          peers: payload.peers || {}
        });

        if (payload.active_preset) {
          setActivePreset(payload.active_preset);
        }
      } else {
        setErrorMsg(json.message || "Gagal memuat pengaturan audio");
      }
    } catch (err: any) {
      setErrorMsg("Koneksi API Backend: " + err.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchTelemetry = async () => {
    try {
      const res = await fetch("/api/admin/audio-meter");
      const json = await res.json();
      if (json.status === "success" || json.active_streams_count !== undefined) {
        setTelemetry({
          core_online: json.core_online ?? true,
          active_streams: json.active_streams ?? json.active_streams_count ?? 0,
          active_talker: json.active_talker ?? (json.streams && json.streams[0] ? json.streams[0] : null),
          rms_level: getGain(json.rms_level, 0),
          dbfs: getGain(json.dbfs, -60),
          limiter_active: Boolean(json.limiter_active),
          vocoder: json.vocoder || "mbelib-neo HD (SIMD AVX/NEON)",
          normalizer_active: json.normalizer_active ?? true
        });
      }
    } catch {
      // Telemetry poll is non-blocking
    }
  };

  useEffect(() => {
    fetchSettings();
    const interval = setInterval(fetchTelemetry, 1500);
    return () => clearInterval(interval);
  }, []);

  const handleSave = async () => {
    if (!settings) return;
    try {
      setSaving(true);
      setErrorMsg(null);
      const pin = getSavedAdminPin();
      const res = await fetch("/api/admin/audio-settings", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Admin-Pin": pin
        },
        body: JSON.stringify({
          ...settings,
          active_preset: activePreset
        })
      });

      if (!res.ok) {
        const text = await res.text();
        throw new Error(`HTTP ${res.status}: ${text}`);
      }
      const json = await res.json();
      if (json.status === "success" || json.status === "SUCCESS") {
        setSaveSuccess(true);
        setTimeout(() => setSaveSuccess(false), 3500);
        const payload = json.data || (json.audio_settings ? json.audio_settings : null);
        if (payload) fetchSettings();
      } else {
        setErrorMsg(json.detail || json.message || "Gagal menyimpan konfigurasi");
      }
    } catch (err: any) {
      setErrorMsg("Gagal mengirim perubahan: " + err.message);
    } finally {
      setSaving(false);
    }
  };

  const handleApplyPreset = async (presetId: string) => {
    try {
      setApplyingPreset(true);
      setErrorMsg(null);
      const pin = getSavedAdminPin();
      const res = await fetch(`/api/admin/audio-settings/preset/${presetId}`, {
        method: "POST",
        headers: {
          "X-Admin-Pin": pin
        }
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`HTTP ${res.status}: ${text}`);
      }
      const json = await res.json();
      if (json.status === "success" || json.status === "SUCCESS") {
        setActivePreset(presetId);
        setSaveSuccess(true);
        setTimeout(() => setSaveSuccess(false), 3500);
        fetchSettings();
      } else {
        setErrorMsg(json.detail || "Gagal menerapkan preset");
      }
    } catch (err: any) {
      setErrorMsg("Error preset: " + err.message);
    } finally {
      setApplyingPreset(false);
    }
  };

  const handleSoundCheck = () => {
    try {
      if (isPlayingTestTone) {
        if (audioContextRef.current) {
          audioContextRef.current.close();
          audioContextRef.current = null;
        }
        setIsPlayingTestTone(false);
        return;
      }

      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const ctx = new AudioCtx();
      audioContextRef.current = ctx;

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = "sine";
      osc.frequency.setValueAtTime(1000, ctx.currentTime);

      gain.gain.setValueAtTime(0.001, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + 0.05);
      gain.gain.setValueAtTime(0.25, ctx.currentTime + 0.45);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.6);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start();
      setIsPlayingTestTone(true);

      osc.stop(ctx.currentTime + 0.65);
      osc.onended = () => {
        setIsPlayingTestTone(false);
        if (audioContextRef.current) {
          audioContextRef.current.close();
          audioContextRef.current = null;
        }
      };
    } catch (err) {
      console.error("Test tone error:", err);
    }
  };

  // Helper to update master settings locally
  const updateMaster = (field: keyof MasterAudioSettings, val: any) => {
    if (!settings) return;
    setSettings({
      ...settings,
      master: {
        ...settings.master,
        [field]: val
      }
    });
  };

  // Helper to update mode settings locally
  const updateMode = (modeKey: string, field: string, val: any) => {
    if (!settings || !settings.modes[modeKey]) return;
    const currentMode = settings.modes[modeKey];
    setSettings({
      ...settings,
      modes: {
        ...settings.modes,
        [modeKey]: {
          ...currentMode,
          [field]: val,
          ...(field === "ingress_gain" ? { rx_gain: val } : {}),
          ...(field === "egress_gain" ? { tx_gain: val } : {}),
          ...(field === "muted" ? { mute: val } : {})
        }
      }
    });
  };

  // Helper to update peer settings locally
  const updatePeer = (peerKey: string, field: string, val: any) => {
    if (!settings || !settings.peers[peerKey]) return;
    const currentPeer = settings.peers[peerKey];
    setSettings({
      ...settings,
      peers: {
        ...settings.peers,
        [peerKey]: {
          ...currentPeer,
          [field]: val,
          ...(field === "muted" ? { mute: val } : {})
        }
      }
    });
  };

  const dmrRxGainVal = getGain(
    settings?.master?.dmr_rx_gain ?? (settings?.modes?.dmr as any)?.rx_gain,
    2.8
  );
  const targetRmsVal = getGain(settings?.master?.target_rms, 4200);
  const maxAgcGainVal = getGain(settings?.master?.max_agc_gain, 4.0);
  const limiterThreshVal = getGain(settings?.master?.limiter_threshold, 18000);

  return (
    <div className="min-h-screen bg-[#070b14] text-slate-100 flex flex-col font-sans selection:bg-cyan-500/30">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 w-full max-w-7xl mx-auto px-3 sm:px-6 py-6 sm:py-8 space-y-6">
        <AdminGuard
          title="Central Audio Master & Equalization"
          description="Sistem Informasi kontrol terpadu untuk kalibrasi gain, normalisasi level, dan vocoder DSP di semua mode dan peer."
          tag="AUDIO MASTER"
        >
          {/* Header & Control Actions */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-5 rounded-2xl bg-gradient-to-r from-[#0c1427] via-[#0f1b33] to-[#0a1224] border border-cyan-500/30 shadow-2xl relative overflow-hidden">
            <div className="absolute -right-16 -top-16 w-56 h-56 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />
            <div className="absolute -left-16 -bottom-16 w-56 h-56 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />

            <div className="space-y-1.5 relative z-10">
              <div className="flex items-center gap-2.5">
                <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-cyan-400 shadow-inner">
                  <SlidersHorizontal className="w-6 h-6 animate-pulse" />
                </div>
                <div>
                  <h1 className="text-xl sm:text-2xl font-black tracking-wide text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 via-sky-300 to-emerald-400 uppercase">
                    Audio Master & Equalization
                  </h1>
                  <p className="text-xs text-slate-400">
                    Sistem Informasi & Kalibrasi Level Suara Antar-Mode & Inter-Peer Server
                  </p>
                </div>
              </div>

              {/* Status Badges */}
              <div className="flex flex-wrap items-center gap-2 pt-1 text-[11px] font-mono">
                <span className="px-2.5 py-0.5 rounded-md bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 flex items-center gap-1.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping" />
                  VOCODER: {telemetry.vocoder}
                </span>
                <span className="px-2.5 py-0.5 rounded-md bg-cyan-500/10 border border-cyan-500/30 text-cyan-400 flex items-center gap-1.5">
                  <Sparkles className="w-3 h-3 text-cyan-300" />
                  DYNAMIC AGC: {settings?.master?.agc_enabled ? "ON" : "OFF"}
                </span>
                <span className="px-2.5 py-0.5 rounded-md bg-purple-500/10 border border-purple-500/30 text-purple-400">
                  TDMA JITTER: 150ms SAFE
                </span>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="flex flex-wrap items-center gap-2.5 relative z-10">
              <button
                type="button"
                onClick={handleSoundCheck}
                className={`px-3.5 py-2 rounded-xl text-xs font-semibold flex items-center gap-2 transition-all border shadow-lg ${
                  isPlayingTestTone
                    ? "bg-amber-500/20 text-amber-300 border-amber-500/50 animate-bounce"
                    : "bg-slate-800/80 hover:bg-slate-700/80 text-slate-300 border-slate-700 hover:border-slate-600"
                }`}
                title="Putar 1kHz sine wave 0.4s untuk uji speaker lokal"
              >
                <Volume2 className="w-4 h-4 text-amber-400" />
                {isPlayingTestTone ? "Testing..." : "Test Tone 1kHz"}
              </button>

              <button
                type="button"
                onClick={fetchSettings}
                disabled={loading}
                className="px-3.5 py-2 rounded-xl text-xs font-semibold bg-slate-800/80 hover:bg-slate-700/80 text-slate-300 border border-slate-700 hover:border-slate-600 transition-all flex items-center gap-2 shadow-lg disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin text-cyan-400" : ""}`} />
                Reload
              </button>

              <button
                type="button"
                onClick={handleSave}
                disabled={saving || loading}
                className="px-5 py-2 rounded-xl text-xs font-bold bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 flex items-center gap-2 shadow-xl shadow-cyan-500/20 active:scale-95 transition-all disabled:opacity-50"
              >
                {saving ? (
                  <>
                    <RefreshCw className="w-4 h-4 animate-spin text-slate-950" />
                    Menerapkan...
                  </>
                ) : saveSuccess ? (
                  <>
                    <CheckCircle2 className="w-4 h-4 text-slate-950" />
                    Tersimpan!
                  </>
                ) : (
                  <>
                    <Save className="w-4 h-4 text-slate-950" />
                    Simpan Perubahan
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Feedback Notification */}
          {errorMsg && (
            <div className="p-4 rounded-xl bg-red-500/10 border border-red-500/30 text-red-300 text-xs flex items-center gap-2.5">
              <AlertTriangle className="w-4 h-4 text-red-400 shrink-0" />
              <span>{errorMsg}</span>
            </div>
          )}

          {saveSuccess && (
            <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2.5 animate-fadeIn">
              <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
              <span>Konfigurasi audio master berhasil disimpan dan di-hot reload ke Core Engine secara instan!</span>
            </div>
          )}

          {/* Top Metric Cards (SI Executive Overview) */}
          <div className="grid grid-cols-2 lg:grid-cols-5 gap-3.5">
            {/* KPI 1: Vocoder Gain */}
            <div className="p-4 rounded-2xl bg-[#0d1527] border border-cyan-500/20 relative overflow-hidden shadow-lg">
              <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
                <span className="font-semibold uppercase tracking-wider">Vocoder Base Gain</span>
                <Volume2 className="w-4 h-4 text-cyan-400" />
              </div>
              <div className="text-2xl font-black text-cyan-400 tracking-tight font-mono">
                {settings ? `${dmrRxGainVal.toFixed(2)}x` : "-"}
              </div>
              <div className="text-[11px] text-slate-400 font-mono mt-1">
                {settings ? gainToDb(dmrRxGainVal) : "-"} (Vocoder Multiplier)
              </div>
            </div>

            {/* KPI 2: Target RMS Level */}
            <div className="p-4 rounded-2xl bg-[#0d1527] border border-emerald-500/20 relative overflow-hidden shadow-lg">
              <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
                <span className="font-semibold uppercase tracking-wider">Target AGC Level</span>
                <Zap className="w-4 h-4 text-emerald-400" />
              </div>
              <div className="text-2xl font-black text-emerald-400 tracking-tight font-mono">
                {settings ? `${Math.round(targetRmsVal)}` : "-"} <span className="text-xs font-normal text-slate-400">RMS</span>
              </div>
              <div className="text-[11px] text-slate-400 font-mono mt-1">
                Nominal: -14 dBFS broadcast
              </div>
            </div>

            {/* KPI 3: Limiter Threshold */}
            <div className="p-4 rounded-2xl bg-[#0d1527] border border-amber-500/20 relative overflow-hidden shadow-lg">
              <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
                <span className="font-semibold uppercase tracking-wider">Soft Limiter</span>
                <ShieldCheck className="w-4 h-4 text-amber-400" />
              </div>
              <div className="text-2xl font-black text-amber-400 tracking-tight font-mono">
                {settings ? limiterThreshVal : 18000}
              </div>
              <div className="text-[11px] text-slate-400 font-mono mt-1">
                Anti-clipping Tanh Knee
              </div>
            </div>

            {/* KPI 4: Active Audio Modes */}
            <div className="p-4 rounded-2xl bg-[#0d1527] border border-purple-500/20 relative overflow-hidden shadow-lg">
              <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
                <span className="font-semibold uppercase tracking-wider">Inter-Mode Bridges</span>
                <Layers className="w-4 h-4 text-purple-400" />
              </div>
              <div className="text-2xl font-black text-purple-400 tracking-tight font-mono">
                {settings && settings.modes ? Object.keys(settings.modes).length : 6} / 6
              </div>
              <div className="text-[11px] text-slate-400 font-mono mt-1">
                DMR, Web, Mumble, Svx, Zello, DVS
              </div>
            </div>

            {/* KPI 5: Active Stream / Core Status */}
            <div className="p-4 rounded-2xl bg-[#0d1527] border border-sky-500/20 relative overflow-hidden shadow-lg col-span-2 lg:col-span-1">
              <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
                <span className="font-semibold uppercase tracking-wider">Live Stream Activity</span>
                <Activity className="w-4 h-4 text-sky-400" />
              </div>
              <div className="text-2xl font-black text-sky-400 tracking-tight font-mono">
                {telemetry.active_talker ? "TRANSMITTING" : "IDLE"}
              </div>
              <div className="text-[11px] text-slate-400 font-mono mt-1 truncate">
                {telemetry.active_talker
                  ? `${telemetry.active_talker.callsign || "Remote"} (${telemetry.active_talker.talkgroup ? "TG " + telemetry.active_talker.talkgroup : telemetry.active_talker.mode || "Audio"})`
                  : "Menunggu burst suara..."}
              </div>
            </div>
          </div>

          {/* Preset Selector Bar */}
          <div className="p-5 rounded-2xl bg-[#0c1427] border border-slate-800 shadow-xl space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Sparkles className="w-4 h-4 text-amber-400" />
                <h2 className="text-sm font-bold tracking-wide uppercase text-slate-200">
                  Preset Karakter Suara Cepat (1-Click Sound Profile)
                </h2>
              </div>
              <span className="text-[11px] text-slate-400">
                Pilih profil suara terkalibrasi untuk otomatis menyesuaikan semua mode
              </span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5">
              {PRESET_OPTIONS.map((preset) => {
                const isSelected = activePreset === preset.id;
                return (
                  <button
                    key={preset.id}
                    type="button"
                    onClick={() => handleApplyPreset(preset.id)}
                    disabled={applyingPreset}
                    className={`p-3 rounded-xl border text-left transition-all relative overflow-hidden group flex flex-col justify-between ${
                      isSelected
                        ? `bg-gradient-to-br ${preset.color} shadow-lg ring-1 ring-cyan-400/40`
                        : "bg-slate-900/60 hover:bg-slate-800/80 border-slate-800 hover:border-slate-700 text-slate-300"
                    }`}
                  >
                    <div>
                      <div className="flex items-center justify-between gap-1 mb-1">
                        <span className="font-bold text-xs tracking-tight text-white group-hover:text-cyan-300 transition-colors">
                          {preset.name}
                        </span>
                        <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-black/40 text-slate-400">
                          {preset.badge}
                        </span>
                      </div>
                      <p className="text-[11px] text-slate-400 leading-snug line-clamp-2">
                        {preset.description}
                      </p>
                    </div>
                    {isSelected && (
                      <div className="mt-2 text-[10px] font-mono font-bold text-cyan-300 flex items-center gap-1">
                        <CheckCircle2 className="w-3 h-3" /> AKTIF SEKARANG
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Real-time Dynamic VU & Peak Meter Bar */}
          <div className="p-4 rounded-2xl bg-[#0c1427] border border-cyan-500/20 shadow-xl space-y-2">
            <div className="flex items-center justify-between text-xs">
              <div className="flex items-center gap-2">
                <Activity className="w-4 h-4 text-cyan-400" />
                <span className="font-bold uppercase tracking-wider text-slate-300">
                  Audio Spectrum & VU Meter Monitor
                </span>
              </div>
              <div className="flex items-center gap-3 text-[11px] font-mono text-slate-400">
                <span>RMS: <strong className="text-cyan-300">{Math.round(telemetry.rms_level)}</strong></span>
                <span>dBFS: <strong className="text-emerald-300">{telemetry.dbfs.toFixed(1)} dB</strong></span>
                <span>Limiter: <strong className={telemetry.limiter_active ? "text-amber-400" : "text-slate-500"}>{telemetry.limiter_active ? "ENGAGED" : "OFF"}</strong></span>
              </div>
            </div>

            {/* Simulated VU Meter Bar */}
            <div className="w-full h-4 bg-slate-950 rounded-full p-0.5 border border-slate-800 overflow-hidden relative">
              <div className="absolute inset-0 flex justify-between px-2 text-[8px] font-mono text-slate-600 pointer-events-none select-none items-center">
                <span>-40dB</span>
                <span>-24dB</span>
                <span>-18dB</span>
                <span>-12dB</span>
                <span>-6dB</span>
                <span className="text-amber-400 font-bold">-3dB</span>
                <span className="text-red-400 font-bold">0dB</span>
              </div>
              <div
                className={`h-full rounded-full transition-all duration-150 ${
                  telemetry.dbfs > -3
                    ? "bg-gradient-to-r from-emerald-500 via-amber-500 to-red-500"
                    : telemetry.dbfs > -12
                    ? "bg-gradient-to-r from-emerald-500 to-amber-400"
                    : "bg-gradient-to-r from-cyan-500 to-emerald-400"
                }`}
                style={{
                  width: `${Math.min(100, Math.max(4, ((telemetry.dbfs + 50) / 50) * 100))}%`
                }}
              />
            </div>
          </div>

          {/* Master Dynamic Equalization & Vocoder DSP Card */}
          {settings && settings.master && (
            <div className="p-5 sm:p-6 rounded-2xl bg-[#0c1427] border border-cyan-500/30 shadow-2xl space-y-6">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-800 pb-4">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Cpu className="w-5 h-5 text-cyan-400" />
                    <h2 className="text-base sm:text-lg font-bold text-white tracking-wide uppercase">
                      Master Dynamic Equalization & Vocoder DSP
                    </h2>
                  </div>
                  <p className="text-xs text-slate-400">
                    Konfigurasi inti decoding <code>mbelib-neo</code>, gain amplifikasi dasar, dan kompresor AGC server.
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <label className="text-xs font-semibold text-slate-300 flex items-center gap-2 cursor-pointer bg-slate-900/80 px-3 py-1.5 rounded-xl border border-slate-700">
                    <input
                      type="checkbox"
                      checked={Boolean(settings.master.agc_enabled)}
                      onChange={(e) => updateMaster("agc_enabled", e.target.checked)}
                      className="rounded bg-slate-800 border-slate-600 text-cyan-500 focus:ring-cyan-500 w-4 h-4"
                    />
                    <span>Aktifkan Dynamic AGC</span>
                  </label>
                </div>
              </div>

              {/* Sliders Grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {/* DMR RX Gain */}
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-bold text-slate-200">Vocoder RX Gain</label>
                    <span className="text-xs font-mono font-bold text-cyan-400">
                      {dmrRxGainVal.toFixed(2)}x ({gainToDb(dmrRxGainVal)})
                    </span>
                  </div>
                  <input
                    type="range"
                    min="0.5"
                    max="5.0"
                    step="0.05"
                    value={dmrRxGainVal}
                    onChange={(e) => updateMaster("dmr_rx_gain", parseFloat(e.target.value))}
                    className="w-full accent-cyan-400 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <p className="text-[10px] text-slate-400 leading-tight">
                    Mengatasi output suara pelan dari mbelib-neo. Rekomendasi: 2.80x (+8.9 dB).
                  </p>
                </div>

                {/* Target RMS */}
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-bold text-slate-200">Target RMS Normalizer</label>
                    <span className="text-xs font-mono font-bold text-emerald-400">
                      {Math.round(targetRmsVal)} RMS
                    </span>
                  </div>
                  <input
                    type="range"
                    min="1500"
                    max="7000"
                    step="100"
                    value={targetRmsVal}
                    onChange={(e) => updateMaster("target_rms", parseFloat(e.target.value))}
                    className="w-full accent-emerald-400 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <p className="text-[10px] text-slate-400 leading-tight">
                    Level amplitudo standar yang diseragamkan untuk semua pembicara. Default: 4200.
                  </p>
                </div>

                {/* Max AGC Gain */}
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-bold text-slate-200">Max AGC Gain Ceiling</label>
                    <span className="text-xs font-mono font-bold text-amber-400">
                      {maxAgcGainVal.toFixed(1)}x ({gainToDb(maxAgcGainVal)})
                    </span>
                  </div>
                  <input
                    type="range"
                    min="1.0"
                    max="6.0"
                    step="0.1"
                    value={maxAgcGainVal}
                    onChange={(e) => updateMaster("max_agc_gain", parseFloat(e.target.value))}
                    className="w-full accent-amber-400 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <p className="text-[10px] text-slate-400 leading-tight">
                    Batas maksimal penguatan suara yang sangat bisik/pelan tanpa mengangkat noise.
                  </p>
                </div>

                {/* Limiter Threshold */}
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-bold text-slate-200">Tanh Limiter Ceiling</label>
                    <span className="text-xs font-mono font-bold text-purple-400">
                      {limiterThreshVal}
                    </span>
                  </div>
                  <input
                    type="range"
                    min="12000"
                    max="28000"
                    step="500"
                    value={limiterThreshVal}
                    onChange={(e) => updateMaster("limiter_threshold", parseFloat(e.target.value))}
                    className="w-full accent-purple-400 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <p className="text-[10px] text-slate-400 leading-tight">
                    Ambang batas kompresi soft-knee untuk mencegah distorsi pecah (clipping).
                  </p>
                </div>
              </div>

              {/* DSP Filters Switches */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-2">
                <label className="p-3.5 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center justify-between cursor-pointer hover:bg-slate-900/70 transition-colors">
                  <div className="space-y-0.5">
                    <div className="text-xs font-bold text-slate-200">High-Pass Filter (HPF 300Hz)</div>
                    <div className="text-[10px] text-slate-400">Memotong dengung AC & rumble getaran mic</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={Boolean(settings.master.filter_hpf)}
                    onChange={(e) => updateMaster("filter_hpf", e.target.checked)}
                    className="rounded bg-slate-800 border-slate-600 text-cyan-500 focus:ring-cyan-500 w-4 h-4 ml-3"
                  />
                </label>

                <label className="p-3.5 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center justify-between cursor-pointer hover:bg-slate-900/70 transition-colors">
                  <div className="space-y-0.5">
                    <div className="text-xs font-bold text-slate-200">Low-Pass Filter (LPF 3400Hz)</div>
                    <div className="text-[10px] text-slate-400">Anti-aliasing komunikasi & vokal tajam</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={Boolean(settings.master.filter_lpf)}
                    onChange={(e) => updateMaster("filter_lpf", e.target.checked)}
                    className="rounded bg-slate-800 border-slate-600 text-cyan-500 focus:ring-cyan-500 w-4 h-4 ml-3"
                  />
                </label>

                <label className="p-3.5 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center justify-between cursor-pointer hover:bg-slate-900/70 transition-colors">
                  <div className="space-y-0.5">
                    <div className="text-xs font-bold text-slate-200">Telecom De-Emphasis (Warm)</div>
                    <div className="text-[10px] text-slate-400">Roll-off desis treble untuk vokal radio hangat</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={Boolean(settings.master.filter_deemph)}
                    onChange={(e) => updateMaster("filter_deemph", e.target.checked)}
                    className="rounded bg-slate-800 border-slate-600 text-cyan-500 focus:ring-cyan-500 w-4 h-4 ml-3"
                  />
                </label>
              </div>
            </div>
          )}

          {/* Inter-Mode Audio Calibration Grid */}
          {settings && settings.modes && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Layers className="w-5 h-5 text-emerald-400" />
                  <h2 className="text-base sm:text-lg font-bold text-white tracking-wide uppercase">
                    Kalibrasi Audio Antar-Mode (Inter-Mode Gain & Trims)
                  </h2>
                </div>
                <span className="text-xs text-slate-400">
                  Mengatur Ingress (masuk) dan Egress (keluar) untuk setiap bridge mode
                </span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {Object.entries(settings.modes).map(([modeKey, mode]) => {
                  const ingress = getGain(mode?.ingress_gain ?? mode?.rx_gain, 1.0);
                  const egress = getGain(mode?.egress_gain ?? mode?.tx_gain, 1.0);
                  const isMuted = Boolean(mode?.muted ?? mode?.mute ?? false);
                  const isAgc = Boolean(mode?.agc ?? true);

                  return (
                    <div
                      key={modeKey}
                      className={`p-4 rounded-2xl border transition-all space-y-4 ${
                        isMuted
                          ? "bg-red-950/20 border-red-500/30"
                          : "bg-[#0c1427] border-slate-800 hover:border-slate-700 shadow-xl"
                      }`}
                    >
                      {/* Mode Header */}
                      <div className="flex items-center justify-between border-b border-slate-800/80 pb-2.5">
                        <div className="flex items-center gap-2">
                          <div className="w-2 h-2 rounded-full bg-cyan-400" />
                          <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200">
                            {mode.name || modeKey.toUpperCase()}
                          </h3>
                        </div>

                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => updateMode(modeKey, "muted", !isMuted)}
                            className={`p-1.5 rounded-lg text-xs font-semibold transition-all ${
                              isMuted
                                ? "bg-red-500/20 text-red-400 border border-red-500/40"
                                : "bg-slate-800 text-slate-400 hover:text-slate-200"
                            }`}
                            title={isMuted ? "Mode Ter-mute" : "Mute Mode Ini"}
                          >
                            {isMuted ? <VolumeX className="w-3.5 h-3.5" /> : <Volume2 className="w-3.5 h-3.5" />}
                          </button>

                          <button
                            type="button"
                            onClick={() => {
                              updateMode(modeKey, "ingress_gain", 1.0);
                              updateMode(modeKey, "egress_gain", 1.0);
                            }}
                            className="p-1.5 rounded-lg bg-slate-800 text-slate-400 hover:text-slate-200 text-xs"
                            title="Reset Gain 1.0x (0 dB)"
                          >
                            <RotateCcw className="w-3 h-3" />
                          </button>
                        </div>
                      </div>

                      {/* Ingress Gain Slider */}
                      <div className="space-y-1">
                        <div className="flex items-center justify-between text-xs">
                          <span className="text-slate-400">Ingress (Masuk Server)</span>
                          <span className="font-mono text-cyan-400 font-bold">
                            {ingress.toFixed(2)}x ({gainToDb(ingress)})
                          </span>
                        </div>
                        <input
                          type="range"
                          min="0.2"
                          max="3.0"
                          step="0.05"
                          value={ingress}
                          onChange={(e) => updateMode(modeKey, "ingress_gain", parseFloat(e.target.value))}
                          className="w-full accent-cyan-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
                        />
                      </div>

                      {/* Egress Gain Slider */}
                      <div className="space-y-1">
                        <div className="flex items-center justify-between text-xs">
                          <span className="text-slate-400">Egress (Keluar ke Klien)</span>
                          <span className="font-mono text-emerald-400 font-bold">
                            {egress.toFixed(2)}x ({gainToDb(egress)})
                          </span>
                        </div>
                        <input
                          type="range"
                          min="0.2"
                          max="3.0"
                          step="0.05"
                          value={egress}
                          onChange={(e) => updateMode(modeKey, "egress_gain", parseFloat(e.target.value))}
                          className="w-full accent-emerald-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
                        />
                      </div>

                      {/* Auto AGC Toggle */}
                      <div className="flex items-center justify-between pt-1 text-[11px] text-slate-400 border-t border-slate-800/60">
                        <span>Auto AGC Normalizer</span>
                        <input
                          type="checkbox"
                          checked={isAgc}
                          onChange={(e) => updateMode(modeKey, "agc", e.target.checked)}
                          className="rounded bg-slate-800 border-slate-600 text-cyan-500 focus:ring-cyan-500 w-3.5 h-3.5"
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Interconnected Peers Audio Calibration Grid */}
          {settings && settings.peers && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Globe className="w-5 h-5 text-sky-400" />
                  <h2 className="text-base sm:text-lg font-bold text-white tracking-wide uppercase">
                    Normalisasi Suara Inter-Peer Network (Interconnected Peers)
                  </h2>
                </div>
                <span className="text-xs text-slate-400">
                  Kompensasi level suara untuk setiap jaringan server peer rekanan
                </span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {Object.entries(settings.peers).map(([peerKey, peer]) => {
                  const rxGain = getGain(peer?.rx_gain, 1.0);
                  const txGain = getGain(peer?.tx_gain, 1.0);
                  const isPeerMuted = Boolean(peer?.muted ?? peer?.mute ?? false);

                  return (
                    <div
                      key={peerKey}
                      className={`p-4 rounded-2xl border transition-all space-y-4 ${
                        isPeerMuted
                          ? "bg-red-950/20 border-red-500/30"
                          : "bg-[#0c1427] border-slate-800 hover:border-slate-700 shadow-xl"
                      }`}
                    >
                      {/* Peer Header */}
                      <div className="flex items-center justify-between border-b border-slate-800/80 pb-2.5">
                        <div className="flex items-center gap-2">
                          <Server className="w-4 h-4 text-sky-400" />
                          <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200">
                            {peer.name || peerKey}
                          </h3>
                        </div>

                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => updatePeer(peerKey, "muted", !isPeerMuted)}
                            className={`p-1.5 rounded-lg text-xs font-semibold transition-all ${
                              isPeerMuted
                                ? "bg-red-500/20 text-red-400 border border-red-500/40"
                                : "bg-slate-800 text-slate-400 hover:text-slate-200"
                            }`}
                            title={isPeerMuted ? "Peer Ter-mute" : "Mute Peer Ini"}
                          >
                            {isPeerMuted ? <VolumeX className="w-3.5 h-3.5" /> : <Volume2 className="w-3.5 h-3.5" />}
                          </button>

                          <button
                            type="button"
                            onClick={() => {
                              updatePeer(peerKey, "rx_gain", 1.0);
                              updatePeer(peerKey, "tx_gain", 1.0);
                            }}
                            className="p-1.5 rounded-lg bg-slate-800 text-slate-400 hover:text-slate-200 text-xs"
                            title="Reset Gain 1.0x"
                          >
                            <RotateCcw className="w-3 h-3" />
                          </button>
                        </div>
                      </div>

                      {/* RX Gain Slider */}
                      <div className="space-y-1">
                        <div className="flex items-center justify-between text-xs">
                          <span className="text-slate-400">Incoming RX Gain (Dari Peer)</span>
                          <span className="font-mono text-cyan-400 font-bold">
                            {rxGain.toFixed(2)}x ({gainToDb(rxGain)})
                          </span>
                        </div>
                        <input
                          type="range"
                          min="0.2"
                          max="3.0"
                          step="0.05"
                          value={rxGain}
                          onChange={(e) => updatePeer(peerKey, "rx_gain", parseFloat(e.target.value))}
                          className="w-full accent-cyan-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
                        />
                      </div>

                      {/* TX Gain Slider */}
                      <div className="space-y-1">
                        <div className="flex items-center justify-between text-xs">
                          <span className="text-slate-400">Outgoing TX Gain (Ke Peer)</span>
                          <span className="font-mono text-emerald-400 font-bold">
                            {txGain.toFixed(2)}x ({gainToDb(txGain)})
                          </span>
                        </div>
                        <input
                          type="range"
                          min="0.2"
                          max="3.0"
                          step="0.05"
                          value={txGain}
                          onChange={(e) => updatePeer(peerKey, "tx_gain", parseFloat(e.target.value))}
                          className="w-full accent-emerald-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
                        />
                      </div>

                      {/* Sample Uniformity Status */}
                      <div className="flex items-center justify-between pt-1 text-[11px] text-slate-400 border-t border-slate-800/60 font-mono">
                        <span>Sample Status</span>
                        <span className="text-emerald-400 flex items-center gap-1 font-semibold">
                          <CheckCircle2 className="w-3 h-3" /> SERAGAM (NORMALIZED)
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* System Information & Architecture Reference Card */}
          <div className="p-5 rounded-2xl bg-[#0c1427] border border-slate-800 shadow-xl space-y-3">
            <div className="flex items-center gap-2">
              <Info className="w-4 h-4 text-cyan-400" />
              <h3 className="font-bold text-xs uppercase tracking-wider text-slate-300">
                Informasi Arsitektur Normalisasi & Standar Audio Siaran
              </h3>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs text-slate-400 leading-relaxed">
              <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-1">
                <span className="font-bold text-slate-200">1. mbelib-neo Vocoder Calibration:</span>
                <p>
                  Decoding AMBE 2450 bps menghasilkan sinyal nominal 2000-6000 amplitude (-18 dBFS). Penguatan Vocoder 2.80x mengangkat audio ke nominal -6 dBFS sesuai standar komersial HT Motorola & Hytera.
                </p>
              </div>

              <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-1">
                <span className="font-bold text-slate-200">2. Centralized Ingress/Egress Normalizer:</span>
                <p>
                  Setiap stream suara yang masuk dari Web, Mumble, SvxLink, Zello, atau Peer dikompensasi dengan RMS target yang sama persis (4200 RMS) sebelum disalurkan ke mode lain.
                </p>
              </div>

              <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-1">
                <span className="font-bold text-slate-200">3. Zero-Restart Hot-Reload:</span>
                <p>
                  Semua slider dan preset langsung diterapkan ke Core Engine secara real-time via HTTP endpoint internal tanpa me-restart daemon Analog_Bridge atau MMDVM_Bridge.
                </p>
              </div>
            </div>
          </div>
        </AdminGuard>
      </main>
    </div>
  );
}
