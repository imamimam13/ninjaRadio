"use client";

import React, { useState, useEffect, useRef } from "react";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { usePhysicalPTT } from "@/hooks/usePhysicalPTT";
import { AudioVisualizer } from "@/components/AudioVisualizer";
import { ProfileSwitcher, UserProfile } from "@/components/ProfileSwitcher";
import { beepPlayer } from "@/components/BeepPlayer";
import { 
  Radio, 
  Mic, 
  Volume2, 
  VolumeX, 
  Wifi, 
  ChevronLeft, 
  ChevronRight, 
  ShieldCheck, 
  Key,
  Layers,
  Sparkles,
  MessageSquare,
  Send,
  X,
  User,
  Users,
  Smartphone,
  RotateCw,
  PhoneCall,
  Sliders,
  Maximize2,
  Headphones,
  SlidersHorizontal,
  Activity,
  Cable,
  Settings2,
  SlidersVertical,
  CheckCircle2,
  Volume1
} from "lucide-react";
import Link from "next/link";

const ALL_TALKGROUPS = [
  { id: 510, name: "Indo National DMR", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5101, name: "Jawa Barat Regional", slot: 2, mode: "YSF", category: "Amatir" },
  { id: 5102, name: "Jawa Tengah Regional", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5103, name: "Jawa Timur Regional", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5109, name: "RAPI Nusantara ASL", slot: 1, mode: "ANALOG", category: "RAPI" },
  { id: 5108, name: "11M CB Digital Gateway", slot: 1, mode: "CB", category: "CB" },
  { id: 5100, name: "Emergency SAR 911", slot: 1, mode: "P25", category: "Komunitas" },
  { id: 9990, name: "Audio Test Echo / Parrot", slot: 2, mode: "DMR", category: "Amatir" },
];

interface SmsItem {
  id: string;
  sender: string;
  target: string;
  type: "group" | "private";
  text: string;
  time: string;
}

export default function RadioPocketMode() {
  const { activeTx, isConnected } = useLiveMonitor();
  
  // UI Display Mode: Standard 2.4" Touchscreen vs 1.8" Rugged PoC Radio (Zello Chinese style)
  const [deviceSkin, setDeviceSkin] = useState<"standard" | "poc18">("standard");

  const [activeProfile, setActiveProfile] = useState<UserProfile>({
    id: "prof_default",
    category: "Amatir",
    callsign: "N1NJA",
    displayName: "Radio Operator",
    dmrId: 5109999,
    allowedTalkgroups: [510, 5101, 5102, 5103, 5109, 5108, 5100, 9990],
    badgeColor: "bg-cyan-500 text-slate-950",
    networkTag: "NinjaRadio Master"
  });

  const [selectedTgIndex, setSelectedTgIndex] = useState(0);
  const [isMuted, setIsMuted] = useState(false);

  // VOX (Voice-Operated Exchange / Hands-Free) State
  const [isVoxEnabled, setIsVoxEnabled] = useState(false);
  const [voxThreshold, setVoxThreshold] = useState(-35); // dB threshold
  const [currentMicDb, setCurrentMicDb] = useState(-100);
  const [showVoxModal, setShowVoxModal] = useState(false);

  // ========================================================
  // RADIO AUDIO CABLE GATEWAY STATE (LINE-IN / TRRS / CM108)
  // ========================================================
  const [isCableGatewayActive, setIsCableGatewayActive] = useState(false);
  const [showCableModal, setShowCableModal] = useState(false);
  const [audioDevices, setAudioDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedAudioDeviceId, setSelectedAudioDeviceId] = useState<string>("");
  const [cableNoiseGate, setCableNoiseGate] = useState(-32); // dB Squelch gate
  const [cableGain, setCableGain] = useState(1.0); // 0.5x - 3.0x
  const [enableBandpassFilter, setEnableBandpassFilter] = useState(true); // 300Hz-3.4kHz
  const [isCableRxActive, setIsCableRxActive] = useState(false);
  const [isPlayingTestTone, setIsPlayingTestTone] = useState(false);

  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const voxHangTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isTxRef = useRef(false);
  const testToneOscRef = useRef<OscillatorNode | null>(null);

  // Private Call / Direct Dialing state
  const [callType, setCallType] = useState<"group" | "private">("group");
  const [privateId, setPrivateId] = useState("5101002");
  const [showDialer, setShowDialer] = useState(false);
  const [dialInput, setDialInput] = useState("");

  // SMS Modal State
  const [showSmsModal, setShowSmsModal] = useState(false);
  const [smsType, setSmsType] = useState<"group" | "private">("group");
  const [smsTarget, setSmsTarget] = useState("510");
  const [smsContent, setSmsContent] = useState("");
  const [smsHistory, setSmsHistory] = useState<SmsItem[]>([
    { id: "1", sender: "YB0AZ", target: "TG 510", type: "group", text: "Selamat pagi rekan Nusantara, monitor standby 73.", time: "09:30" },
    { id: "2", sender: "JZ10XYZ", target: "TG 5109", type: "group", text: "Posko 1 Jakarta Timur 10-28 aman terkendali.", time: "09:15" },
  ]);

  // Filter talkgroups strictly allowed for the active profile
  const availableTgs = ALL_TALKGROUPS.filter((tg) =>
    activeProfile.allowedTalkgroups.includes(tg.id)
  );

  const currentTg = availableTgs[selectedTgIndex % (availableTgs.length || 1)] || ALL_TALKGROUPS[0];

  // Hardware Physical PTT Listener (handles side PTT key, F-keys, space, PoC keycode 138/226)
  const { isTransmitting, startTx, stopTx, pttKey } = usePhysicalPTT({
    onTransmitStart: () => {
      beepPlayer?.playTalkGrant();
      fetch("/api/ptt/transmit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
          tg: callType === "private" ? Number(privateId) : currentTg.id,
          slot: currentTg.slot,
          call_type: callType,
          category: activeProfile.category,
          is_transmitting: true,
        }),
      }).catch(() => {});
    },
    onTransmitEnd: () => {
      beepPlayer?.playRogerBeep();
      fetch("/api/ptt/transmit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
          tg: callType === "private" ? Number(privateId) : currentTg.id,
          slot: currentTg.slot,
          call_type: callType,
          category: activeProfile.category,
          is_transmitting: false,
        }),
      }).catch(() => {});
    },
  });

  useEffect(() => {
    isTxRef.current = isTransmitting;
  }, [isTransmitting]);

  // Enumerate Audio Devices (Line-In, External TRRS, USB Soundcard)
  useEffect(() => {
    if (typeof navigator !== "undefined" && navigator.mediaDevices?.enumerateDevices) {
      navigator.mediaDevices.enumerateDevices().then((devices) => {
        const audioInputs = devices.filter((d) => d.kind === "audioinput");
        setAudioDevices(audioInputs);
        if (audioInputs.length > 0 && !selectedAudioDeviceId) {
          setSelectedAudioDeviceId(audioInputs[0].deviceId);
        }
      }).catch(() => {});
    }
  }, [selectedAudioDeviceId]);

  // ========================================================
  // AUDIO ENGINE: VOX & ANALOG/DMR CABLE INGEST LISTENER
  // ========================================================
  useEffect(() => {
    const shouldListen = isVoxEnabled || isCableGatewayActive;

    if (!shouldListen) {
      if (micStreamRef.current) {
        micStreamRef.current.getTracks().forEach((t) => t.stop());
        micStreamRef.current = null;
      }
      if (audioContextRef.current) {
        audioContextRef.current.close();
        audioContextRef.current = null;
      }
      if (voxHangTimerRef.current) {
        clearTimeout(voxHangTimerRef.current);
      }
      if (isTxRef.current && isVoxEnabled) stopTx();
      setCurrentMicDb(-100);
      setIsCableRxActive(false);
      return;
    }

    let animationFrameId: number;

    const initAudioStream = async () => {
      try {
        const constraints: MediaStreamConstraints = {
          audio: selectedAudioDeviceId
            ? {
                deviceId: { exact: selectedAudioDeviceId },
                echoCancellation: !isCableGatewayActive, // Disable echo cancellation for raw radio line-in
                noiseSuppression: !isCableGatewayActive,
                autoGainControl: false, // Let user control gain manually
              }
            : true,
        };

        const stream = await navigator.mediaDevices.getUserMedia(constraints);
        micStreamRef.current = stream;

        const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
        const ctx = new AudioCtx();
        audioContextRef.current = ctx;

        const source = ctx.createMediaStreamSource(stream);
        let lastNode: AudioNode = source;

        // Apply Gain Booster
        if (isCableGatewayActive && cableGain !== 1.0) {
          const gainNode = ctx.createGain();
          gainNode.gain.value = cableGain;
          lastNode.connect(gainNode);
          lastNode = gainNode;
        }

        // Apply 300Hz - 3400Hz Vocal Bandpass Filter (Cuts sub-audible CTCSS tones and squelch hiss)
        if (isCableGatewayActive && enableBandpassFilter) {
          const highpass = ctx.createBiquadFilter();
          highpass.type = "highpass";
          highpass.frequency.value = 300; // Cut CTCSS 67-254 Hz

          const lowpass = ctx.createBiquadFilter();
          lowpass.type = "lowpass";
          lowpass.frequency.value = 3400; // Cut high frequency static

          lastNode.connect(highpass);
          highpass.connect(lowpass);
          lastNode = lowpass;
        }

        const analyser = ctx.createAnalyser();
        analyser.fftSize = 512;
        lastNode.connect(analyser);
        analyserRef.current = analyser;

        const buffer = new Uint8Array(analyser.frequencyBinCount);

        const processAudioLevel = () => {
          if (!isVoxEnabled && !isCableGatewayActive) return;
          analyser.getByteFrequencyData(buffer);

          let sum = 0;
          for (let i = 0; i < buffer.length; i++) {
            sum += buffer[i] * buffer[i];
          }
          const rms = Math.sqrt(sum / buffer.length);
          const db = rms > 0 ? 20 * Math.log10(rms / 255) : -100;
          setCurrentMicDb(Math.round(db));

          // 1. CABLE GATEWAY CARRIER (COS/SQUELCH DETECT)
          if (isCableGatewayActive) {
            const hasCarrier = db > cableNoiseGate;
            setIsCableRxActive(hasCarrier);
          }

          // 2. VOX HANDS-FREE PTT TRIGGER
          if (isVoxEnabled) {
            if (db > voxThreshold) {
              if (voxHangTimerRef.current) {
                clearTimeout(voxHangTimerRef.current);
                voxHangTimerRef.current = null;
              }
              if (!isTxRef.current) {
                startTx();
              }
            } else {
              if (isTxRef.current && !voxHangTimerRef.current) {
                voxHangTimerRef.current = setTimeout(() => {
                  if (isTxRef.current) {
                    stopTx();
                  }
                  voxHangTimerRef.current = null;
                }, 650);
              }
            }
          }

          animationFrameId = requestAnimationFrame(processAudioLevel);
        };

        processAudioLevel();
      } catch (err) {
        console.error("Audio Ingest Stream Error:", err);
        setIsVoxEnabled(false);
        setIsCableGatewayActive(false);
      }
    };

    initAudioStream();

    return () => {
      cancelAnimationFrame(animationFrameId);
      if (micStreamRef.current) {
        micStreamRef.current.getTracks().forEach((t) => t.stop());
      }
      if (audioContextRef.current) {
        audioContextRef.current.close();
      }
      if (voxHangTimerRef.current) {
        clearTimeout(voxHangTimerRef.current);
      }
    };
  }, [
    isVoxEnabled,
    isCableGatewayActive,
    selectedAudioDeviceId,
    cableGain,
    enableBandpassFilter,
    cableNoiseGate,
    voxThreshold,
    startTx,
    stopTx,
  ]);

  // 1000 Hz Line-Out Calibration Tone Generator
  const toggleTestTone = () => {
    if (isPlayingTestTone) {
      if (testToneOscRef.current) {
        testToneOscRef.current.stop();
        testToneOscRef.current.disconnect();
        testToneOscRef.current = null;
      }
      setIsPlayingTestTone(false);
    } else {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const ctx = new AudioCtx();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = 1000; // 1kHz standard RF test tone
      gain.gain.value = 0.3;
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      testToneOscRef.current = osc;
      setIsPlayingTestTone(true);
    }
  };

  // Physical T9 Keyboard Shortcut Listener for 1.8" Radio Mode (keys 1-9 switch channels directly)
  useEffect(() => {
    const handleT9 = (e: KeyboardEvent) => {
      if (showSmsModal || showDialer || showVoxModal || showCableModal) return;
      const keyNum = parseInt(e.key);
      if (!isNaN(keyNum) && keyNum >= 1 && keyNum <= availableTgs.length) {
        setSelectedTgIndex(keyNum - 1);
        beepPlayer?.playTalkGrant();
      }
    };
    window.addEventListener("keydown", handleT9);
    return () => window.removeEventListener("keydown", handleT9);
  }, [availableTgs.length, showSmsModal, showDialer, showVoxModal, showCableModal]);

  const nextChannel = () => {
    setSelectedTgIndex((prev) => (prev + 1) % availableTgs.length);
    beepPlayer?.playRogerBeep();
  };

  const prevChannel = () => {
    setSelectedTgIndex((prev) => (prev - 1 + availableTgs.length) % availableTgs.length);
    beepPlayer?.playRogerBeep();
  };

  const handleSendSms = (e: React.FormEvent) => {
    e.preventDefault();
    if (!smsContent.trim()) return;

    const newSms: SmsItem = {
      id: Date.now().toString(),
      sender: activeProfile.callsign,
      target: smsType === "private" ? `ID ${smsTarget}` : `TG ${smsTarget}`,
      type: smsType,
      text: smsContent.trim(),
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    };

    setSmsHistory([newSms, ...smsHistory]);
    setSmsContent("");
    beepPlayer?.playTalkGrant();
  };

  const handleApplyDial = (e: React.FormEvent) => {
    e.preventDefault();
    if (!dialInput.trim()) return;
    if (callType === "private") {
      setPrivateId(dialInput);
    } else {
      const customId = Number(dialInput);
      const idx = availableTgs.findIndex(t => t.id === customId);
      if (idx >= 0) setSelectedTgIndex(idx);
    }
    setShowDialer(false);
    setDialInput("");
    beepPlayer?.playTalkGrant();
  };

  return (
    <div className="min-h-screen bg-[#05070c] text-slate-100 flex flex-col justify-between select-none p-2 sm:p-4 max-w-lg mx-auto">
      
      {/* Top Device Header & Skin Switcher */}
      <div className="flex items-center justify-between border-b border-cyan-900/40 pb-2">
        <Link
          href="/"
          className="flex items-center gap-1 text-[11px] font-mono font-bold text-slate-400 hover:text-cyan-400 transition-colors"
        >
          <ChevronLeft className="w-4 h-4 text-cyan-400" />
          <span>NOC DASHBOARD</span>
        </Link>

        {/* Radio Device Skin Selector */}
        <div className="flex items-center gap-1 bg-[#0b121e] p-0.5 rounded-lg border border-cyan-900/50 text-[10px] font-mono">
          <button
            onClick={() => setDeviceSkin("standard")}
            className={`px-2 py-1 rounded font-bold transition-all ${
              deviceSkin === "standard"
                ? "bg-cyan-400 text-slate-950 shadow-sm"
                : "text-slate-400 hover:text-slate-200"
            }`}
          >
            📱 2.4" Touch
          </button>
          <button
            onClick={() => setDeviceSkin("poc18")}
            className={`px-2 py-1 rounded font-bold transition-all ${
              deviceSkin === "poc18"
                ? "bg-amber-400 text-slate-950 shadow-sm"
                : "text-slate-400 hover:text-slate-200"
            }`}
          >
            📻 1.8" Rugged Zello
          </button>
        </div>

        <div className="flex items-center gap-1.5">
          {/* Audio Cable Gateway Toggle */}
          <button
            onClick={() => setShowCableModal(true)}
            className={`px-2 py-1 rounded-lg text-[10px] font-mono font-black border transition-all flex items-center gap-1 ${
              isCableGatewayActive
                ? isCableRxActive
                  ? "bg-amber-400 text-slate-950 border-amber-300 shadow-[0_0_12px_rgba(251,191,36,0.6)] animate-pulse"
                  : "bg-cyan-950 text-cyan-300 border-cyan-700 shadow-sm"
                : "bg-[#0b121e] border-cyan-900/40 text-slate-400 hover:text-cyan-400"
            }`}
            title="Kabel Audio Radio Gateway (TRRS Line-In / USB Soundcard)"
          >
            <Cable className="w-3 h-3" />
            <span>KABEL {isCableGatewayActive ? (isCableRxActive ? "RX" : "ON") : "OFF"}</span>
          </button>

          {/* VOX Quick Toggle Pill */}
          <button
            onClick={() => {
              if (!isVoxEnabled) {
                setIsVoxEnabled(true);
                beepPlayer?.playTalkGrant();
              } else {
                setIsVoxEnabled(false);
                beepPlayer?.playRogerBeep();
              }
            }}
            className={`px-2 py-1 rounded-lg text-[10px] font-mono font-black border transition-all flex items-center gap-1 ${
              isVoxEnabled
                ? "bg-emerald-500 text-slate-950 border-emerald-300 shadow-[0_0_10px_rgba(52,211,153,0.5)] animate-pulse"
                : "bg-[#0b121e] border-cyan-900/40 text-slate-400 hover:text-cyan-400"
            }`}
            title="Aktivasi Suara Otomatis (Hands-Free VOX)"
          >
            <Headphones className="w-3 h-3" />
            <span>VOX</span>
          </button>

          <button
            onClick={() => setIsMuted(!isMuted)}
            className="p-1.5 rounded-lg bg-[#0b121e] border border-cyan-900/40 text-slate-300 hover:text-white"
          >
            {isMuted ? <VolumeX className="w-3.5 h-3.5 text-rose-400" /> : <Volume2 className="w-3.5 h-3.5 text-cyan-400" />}
          </button>
        </div>
      </div>

      {/* Profile Bar & Live Cable / VOX Telemetry Bar */}
      <div className="my-1 space-y-1">
        <div className="flex items-center justify-between">
          <ProfileSwitcher
            activeProfile={activeProfile}
            onProfileChange={(newProf) => {
              setActiveProfile(newProf);
              setSelectedTgIndex(0);
            }}
          />
          
          {/* Call Type Toggle: Group vs Private */}
          <div className="flex items-center gap-1 bg-[#0b121e] p-0.5 rounded-xl border border-cyan-900/40 text-[10px] font-mono">
            <button
              onClick={() => setCallType("group")}
              className={`px-2 py-1 rounded-lg font-bold transition-all ${
                callType === "group" ? "bg-cyan-950 text-cyan-300 border border-cyan-700" : "text-slate-400"
              }`}
            >
              👥 Group
            </button>
            <button
              onClick={() => setCallType("private")}
              className={`px-2 py-1 rounded-lg font-bold transition-all ${
                callType === "private" ? "bg-purple-950 text-purple-300 border border-purple-700" : "text-slate-400"
              }`}
            >
              🔒 Private
            </button>
          </div>
        </div>

        {/* Live Audio Ingest Status Banner */}
        {isCableGatewayActive && (
          <div className={`rounded-xl px-3 py-1.5 flex items-center justify-between text-[10px] font-mono transition-all border ${
            isCableRxActive 
              ? "bg-[#181305] border-amber-500 text-amber-300 animate-pulse shadow-[0_0_10px_rgba(251,191,36,0.3)]"
              : "bg-[#08120e] border-cyan-900/50 text-slate-400"
          }`}>
            <div className="flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${isCableRxActive ? "bg-amber-400 animate-ping" : "bg-cyan-500"}`} />
              <span>
                {isCableRxActive ? "📡 KABEL AUDIO RX AKTIF (SQUELCH BUKA)" : "🔌 KABEL TERHUBUNG (SQUELCH STANDBY)"}: <strong>{currentMicDb} dB</strong>
              </span>
            </div>
            <button 
              onClick={() => setShowCableModal(true)} 
              className="text-[9px] underline text-cyan-400 font-bold"
            >
              Kalibrasi
            </button>
          </div>
        )}

        {/* Live VOX Mic Level Telemetry Bar */}
        {isVoxEnabled && !isCableGatewayActive && (
          <div className="bg-[#08120e] border border-emerald-500/50 rounded-xl px-3 py-1.5 flex items-center justify-between text-[10px] font-mono text-emerald-400">
            <div className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
              <span>VOX HANDS-FREE: <strong>{currentMicDb} dB</strong> (Sens: {voxThreshold} dB)</span>
            </div>
            <button 
              onClick={() => setShowVoxModal(true)} 
              className="text-[9px] underline text-cyan-400 font-bold"
            >
              Sensitivitas
            </button>
          </div>
        )}
      </div>

      {/* ======================================================== */}
      {/* 1.8" RUGGED ZELLO POC RADIO HARDWARE VIEW (128x160 px) */}
      {/* ======================================================== */}
      {deviceSkin === "poc18" ? (
        <div className="my-2 bg-[#121620] border-4 border-slate-700 rounded-3xl p-3 shadow-2xl relative overflow-hidden flex flex-col items-center">
          
          {/* Top Physical Rotary Knob & Antenna */}
          <div className="w-full flex justify-between items-end px-4 mb-2">
            <div className="w-4 h-10 bg-slate-900 border-2 border-slate-700 rounded-t-lg shadow-md flex items-center justify-center">
              <div className="w-1 h-8 bg-slate-700 rounded-full" />
            </div>
            
            {/* Interactive Rotary Channel Knob */}
            <div className="flex flex-col items-center">
              <span className="text-[9px] font-mono text-amber-400 font-bold mb-0.5">ROTARY CH</span>
              <div className="flex items-center gap-1 bg-slate-900 p-1 rounded-xl border border-slate-700">
                <button 
                  onClick={prevChannel} 
                  className="w-7 h-7 rounded-lg bg-slate-800 hover:bg-slate-700 text-amber-400 flex items-center justify-center font-bold text-xs"
                >
                  ◀
                </button>
                <span className="text-xs font-mono font-black text-white px-2">CH {selectedTgIndex + 1}</span>
                <button 
                  onClick={nextChannel} 
                  className="w-7 h-7 rounded-lg bg-slate-800 hover:bg-slate-700 text-amber-400 flex items-center justify-center font-bold text-xs"
                >
                  ▶
                </button>
              </div>
            </div>

            <div className="w-6 h-6 rounded-full bg-slate-900 border border-slate-700 flex items-center justify-center text-[10px] text-gray-400 font-bold">
              VOL
            </div>
          </div>

          {/* 1.8" Mini Screen (Strict 128x160 Aspect Ratio Look) */}
          <div className="w-[200px] h-[220px] bg-[#061410] border-4 border-slate-800 rounded-2xl p-2.5 flex flex-col justify-between font-mono shadow-inner shadow-black relative overflow-hidden">
            <div className="absolute inset-0 bg-scanlines pointer-events-none opacity-20" />

            {/* Screen Header */}
            <div className="flex items-center justify-between text-[9px] text-emerald-400 border-b border-emerald-900/60 pb-1 font-bold">
              <span className="truncate max-w-[70px]">{activeProfile.callsign}</span>
              <div className="flex items-center gap-1">
                {isCableGatewayActive && (
                  <span className={`px-1 rounded text-[8px] font-black ${isCableRxActive ? "bg-amber-400 text-slate-950 animate-pulse" : "bg-cyan-900 text-cyan-300"}`}>
                    CABLE
                  </span>
                )}
                {isVoxEnabled && <span className="bg-emerald-900 text-emerald-300 px-1 rounded text-[8px]">VOX</span>}
                <span>TS{currentTg.slot}</span>
                <span className="bg-emerald-950 px-1 rounded text-[8px]">{currentTg.mode}</span>
              </div>
            </div>

            {/* Screen Core Display */}
            <div className="text-center py-1">
              {isTransmitting ? (
                <div className="animate-pulse">
                  <div className="bg-rose-600 text-white text-[9px] font-black uppercase rounded py-0.5 mb-1">
                    🔴 TX {isVoxEnabled ? "VOX ON AIR" : "ACTIVE"}
                  </div>
                  <div className="text-xl font-black text-rose-400 tracking-wider">
                    {activeProfile.callsign}
                  </div>
                  <div className="text-[10px] text-rose-300 font-bold">
                    {callType === "private" ? `PC ID: ${privateId}` : `TG ${currentTg.id}`}
                  </div>
                </div>
              ) : isCableRxActive ? (
                <div className="animate-pulse">
                  <div className="bg-amber-400 text-slate-950 text-[9px] font-black uppercase rounded py-0.5 mb-1">
                    📻 CABLE RF INGEST (RX)
                  </div>
                  <div className="text-xl font-black text-amber-300 tracking-wider">
                    RADIO FISIK
                  </div>
                  <div className="text-[10px] text-amber-400 font-bold">
                    Line-In: {currentMicDb} dB
                  </div>
                </div>
              ) : activeTx ? (
                <div>
                  <div className="bg-emerald-500 text-slate-950 text-[9px] font-black uppercase rounded py-0.5 mb-1">
                    🟢 RX ACTIVE
                  </div>
                  <div className="text-xl font-black text-emerald-300 tracking-wider">
                    {activeTx.callsign}
                  </div>
                  <div className="text-[10px] text-emerald-400 truncate">
                    TG {activeTx.talkgroup} ({activeTx.tg_name})
                  </div>
                </div>
              ) : (
                <div>
                  <div className="text-[9px] text-emerald-600 uppercase font-bold tracking-wider">
                    CH {selectedTgIndex + 1} • {callType === "private" ? "PRIVATE" : "GROUP"}
                  </div>
                  <div className="text-2xl font-black text-emerald-400 tracking-wider">
                    {callType === "private" ? `ID ${privateId}` : `TG ${currentTg.id}`}
                  </div>
                  <div className="text-[10px] text-emerald-500 font-bold truncate">
                    {currentTg.name}
                  </div>
                </div>
              )}
            </div>

            {/* Screen Footer S-Meter */}
            <div className="border-t border-emerald-900/60 pt-1 flex items-center justify-between text-[9px] text-emerald-400 font-bold">
              <span>{isCableRxActive ? "CABLE RX" : "S9+20dB"}</span>
              <AudioVisualizer 
                isActive={isTransmitting || !!activeTx || isCableRxActive} 
                mode="bars" 
                color={isTransmitting ? "bg-rose-500" : isCableRxActive ? "bg-amber-400" : "bg-emerald-400"} 
                barsCount={8} 
              />
            </div>
          </div>

          {/* Physical T9 Keypad on Radio Body */}
          <div className="w-full mt-3 grid grid-cols-3 gap-1.5 px-2">
            {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((num) => {
              const tg = availableTgs[num - 1];
              return (
                <button
                  key={num}
                  onClick={() => {
                    if (num <= availableTgs.length) {
                      setSelectedTgIndex(num - 1);
                      beepPlayer?.playTalkGrant();
                    }
                  }}
                  disabled={!tg}
                  className={`py-1.5 rounded-lg border text-center font-mono transition-all ${
                    tg
                      ? selectedTgIndex === num - 1
                        ? "bg-amber-400 text-slate-950 font-black border-amber-300 shadow-md"
                        : "bg-slate-900 hover:bg-slate-800 text-slate-200 border-slate-700"
                      : "bg-slate-950 text-slate-600 border-slate-900 opacity-40 cursor-not-allowed"
                  }`}
                >
                  <div className="text-xs font-black">{num}</div>
                  <div className="text-[8px] truncate max-w-[60px] mx-auto">{tg ? `TG${tg.id}` : "-"}</div>
                </button>
              );
            })}
          </div>

          {/* Large Side / Bottom Physical PTT Rocker */}
          <div className="w-full mt-3 px-2">
            <button
              onMouseDown={startTx}
              onMouseUp={stopTx}
              onTouchStart={(e) => { e.preventDefault(); startTx(); }}
              onTouchEnd={(e) => { e.preventDefault(); stopTx(); }}
              className={`w-full py-4 rounded-2xl font-heading font-black text-sm tracking-wider flex items-center justify-center gap-2 shadow-2xl transition-all ${
                isTransmitting
                  ? "bg-rose-600 text-white shadow-rose-600/50 border-2 border-rose-300 ring-4 ring-rose-900/50"
                  : "bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-slate-950 shadow-amber-500/20"
              }`}
            >
              <Mic className="w-5 h-5" />
              <span>{isTransmitting ? "🔴 TRANSMITTING..." : isVoxEnabled ? "🎙️ VOX ACTIVE (AUTO TALK)" : `HOLD PTT [ ${pttKey} ]`}</span>
            </button>
          </div>
        </div>
      ) : (
        /* ======================================================== */
        /* STANDARD 2.4" - 2.8" TOUCHSCREEN HT MODE                 */
        /* ======================================================== */
        <div className="my-1 flex-1 flex flex-col justify-between space-y-2">
          
          {/* Cyberdeck Phosphor LCD Radio Screen */}
          <div className="bg-[#08120e] border-2 border-emerald-500/60 rounded-2xl p-3 shadow-inner shadow-black font-lcd relative overflow-hidden">
            <div className="absolute inset-0 bg-scanlines pointer-events-none opacity-25" />

            {/* LCD Header */}
            <div className="flex items-center justify-between border-b border-emerald-900/60 pb-1 text-xs text-emerald-400 font-bold">
              <div className="flex items-center gap-1.5">
                <Radio className="w-3.5 h-3.5" />
                <span>NINJA-DMR CORE</span>
                {isCableGatewayActive && (
                  <span className={`px-1.5 py-0.2 rounded text-[9px] font-black ${
                    isCableRxActive ? "bg-amber-400 text-slate-950 animate-pulse" : "bg-cyan-900 text-cyan-300"
                  }`}>
                    CABLE {isCableRxActive ? "RX" : "STBY"}
                  </span>
                )}
                {isVoxEnabled && (
                  <span className="px-1.5 py-0.2 rounded bg-emerald-500 text-slate-950 text-[9px] font-black animate-pulse">
                    VOX
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2 font-mono text-[11px]">
                <span>TS{currentTg.slot}</span>
                <span className="px-1.5 py-0.2 bg-emerald-950 rounded text-emerald-300">{currentTg.mode}</span>
              </div>
            </div>

            {/* LCD Body: Active Signal / Callsign / Talkgroup */}
            <div className="py-2.5 text-center">
              {isTransmitting ? (
                <div className="animate-pulse">
                  <div className="inline-block px-2.5 py-0.5 bg-rose-600 text-white font-black text-xs rounded tracking-widest uppercase mb-1">
                    🔴 TX {isVoxEnabled ? "VOX ON AIR" : "TRANSMITTING"}
                  </div>
                  <div className="text-3xl font-black text-rose-400 tracking-wider">
                    {activeProfile.callsign}
                  </div>
                  <div className="text-xs text-rose-300 font-mono mt-0.5">
                    {callType === "private" ? `DIRECT PRIVATE CALL: ID ${privateId}` : `TALKGROUP: TG ${currentTg.id}`}
                  </div>
                </div>
              ) : isCableRxActive ? (
                <div className="animate-pulse">
                  <div className="inline-block px-2.5 py-0.5 bg-amber-400 text-slate-950 font-black text-xs rounded tracking-widest uppercase mb-1">
                    📻 CABLE INGEST (AUDIO RADIO MASUK)
                  </div>
                  <div className="text-3xl font-black text-amber-300 tracking-wider">
                    ANALOG / DMR RF
                  </div>
                  <div className="text-xs text-amber-400 font-mono mt-0.5">
                    Meneruskan audio kabel ke TG {currentTg.id} ({currentMicDb} dB)
                  </div>
                </div>
              ) : activeTx ? (
                <div>
                  <div className="inline-block px-2.5 py-0.5 bg-emerald-500 text-slate-950 font-black text-xs rounded tracking-widest uppercase mb-1">
                    🟢 RX RECEIVING
                  </div>
                  <div className="text-3xl font-black text-emerald-300 tracking-wider">
                    {activeTx.callsign}
                  </div>
                  <div className="text-xs text-emerald-400 font-medium truncate mt-0.5">
                    {activeTx.name} (TG {activeTx.talkgroup} • {activeTx.tg_name})
                  </div>
                </div>
              ) : (
                <div>
                  <div className="text-[10px] text-emerald-600 uppercase font-bold tracking-widest mb-0.5">
                    {callType === "private" ? "DIRECT PRIVATE TARGET" : `CURRENT CHANNEL [${activeProfile.category.toUpperCase()}]`}
                  </div>
                  <div className="text-3xl font-black text-emerald-400 tracking-wider">
                    {callType === "private" ? `ID ${privateId}` : `TG ${currentTg.id}`}
                  </div>
                  <div className="text-xs text-emerald-500 font-bold mt-0.5">
                    {callType === "private" ? "Direct Peer-to-Peer DMR Call" : currentTg.name}
                  </div>
                </div>
              )}
            </div>

            {/* LCD Scope & S-Meter */}
            <div className="mt-1 pt-1.5 border-t border-emerald-900/60 flex items-center justify-between text-xs text-emerald-400 font-mono">
              <div>
                S-METER: <span className="font-bold text-emerald-300">{isCableRxActive ? "RF CABLE" : "S9+20dB"}</span>
              </div>
              <AudioVisualizer 
                isActive={isTransmitting || !!activeTx || isCableRxActive} 
                mode="oscilloscope" 
                color={isTransmitting ? "bg-rose-500" : isCableRxActive ? "bg-amber-400" : "bg-emerald-400"} 
                barsCount={10} 
              />
            </div>
          </div>

          {/* 3-Column Control Row: CH PREV | QUICK SMS | CH NEXT */}
          <div className="grid grid-cols-3 gap-2">
            <button
              onClick={prevChannel}
              className="flex items-center justify-center gap-1 py-2.5 bg-[#0b121e] border border-cyan-900/50 hover:border-cyan-400 active:bg-slate-800 rounded-xl text-xs font-mono font-bold text-slate-200 transition-all"
            >
              <ChevronLeft className="w-4 h-4 text-cyan-400" />
              <span>CH PREV</span>
            </button>
            <button
              onClick={() => setShowSmsModal(true)}
              className="flex items-center justify-center gap-1.5 py-2.5 bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 active:scale-95 text-white text-xs font-heading font-black rounded-xl shadow-lg shadow-purple-600/30 transition-all"
            >
              <MessageSquare className="w-4 h-4" />
              <span>SMS</span>
            </button>
            <button
              onClick={nextChannel}
              className="flex items-center justify-center gap-1 py-2.5 bg-[#0b121e] border border-cyan-900/50 hover:border-cyan-400 active:bg-slate-800 rounded-xl text-xs font-mono font-bold text-slate-200 transition-all"
            >
              <span>CH NEXT</span>
              <ChevronRight className="w-4 h-4 text-cyan-400" />
            </button>
          </div>

          {/* Quick Setup Bar: Keypad | Cable Setup | VOX */}
          <div className="flex justify-center items-center gap-2 flex-wrap">
            <button
              onClick={() => setShowDialer(!showDialer)}
              className="text-[11px] font-mono text-cyan-400 hover:text-cyan-300 flex items-center gap-1 py-1 px-2.5 rounded-lg bg-[#0b121e] border border-cyan-900/30"
            >
              <PhoneCall className="w-3.5 h-3.5" />
              <span>{showDialer ? "Tutup Keypad" : "Dial Keypad"}</span>
            </button>

            <button
              onClick={() => setShowCableModal(true)}
              className="text-[11px] font-mono text-amber-400 hover:text-amber-300 flex items-center gap-1 py-1 px-2.5 rounded-lg bg-[#0b121e] border border-amber-900/40"
            >
              <Cable className="w-3.5 h-3.5" />
              <span>Setup Kabel Radio</span>
            </button>

            <button
              onClick={() => setShowVoxModal(true)}
              className="text-[11px] font-mono text-emerald-400 hover:text-emerald-300 flex items-center gap-1 py-1 px-2.5 rounded-lg bg-[#0b121e] border border-cyan-900/30"
            >
              <SlidersHorizontal className="w-3.5 h-3.5" />
              <span>Sensitivitas VOX</span>
            </button>
          </div>

          {/* Direct Number Dialer Form */}
          {showDialer && (
            <form onSubmit={handleApplyDial} className="bg-[#0b121e] border border-cyan-900/60 rounded-xl p-3 space-y-2">
              <div className="flex gap-2">
                <input
                  type="number"
                  placeholder={callType === "private" ? "Masukkan DMR ID (misal: 5101002)" : "Masukkan TG (misal: 510)"}
                  value={dialInput}
                  onChange={(e) => setDialInput(e.target.value)}
                  className="flex-1 px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-xs text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
                <button
                  type="submit"
                  className="px-4 py-2 bg-cyan-400 text-slate-950 font-black text-xs rounded-xl shadow-md"
                >
                  Set Target
                </button>
              </div>
            </form>
          )}

          {/* Massive Thumb PTT Button */}
          <div className="my-1 flex-1 flex flex-col justify-center items-center">
            <button
              onMouseDown={startTx}
              onMouseUp={stopTx}
              onTouchStart={(e) => { e.preventDefault(); startTx(); }}
              onTouchEnd={(e) => { e.preventDefault(); stopTx(); }}
              className={`w-full max-w-[320px] h-36 rounded-3xl font-heading font-black text-xl flex flex-col items-center justify-center gap-2 shadow-2xl transition-all cursor-pointer active:scale-95 ${
                isTransmitting
                  ? "bg-gradient-to-b from-rose-500 to-red-700 text-white shadow-[0_0_30px_rgba(244,63,94,0.6)] border-4 border-rose-300 ring-8 ring-rose-900/40"
                  : isCableRxActive
                  ? "bg-gradient-to-b from-amber-600 to-amber-900 text-white border-2 border-amber-400 shadow-[0_0_20px_rgba(251,191,36,0.3)]"
                  : isVoxEnabled
                  ? "bg-gradient-to-b from-emerald-950 to-[#0b121e] text-emerald-400 border-2 border-emerald-500/80 shadow-[0_0_20px_rgba(52,211,153,0.2)]"
                  : "bg-gradient-to-b from-[#111827] to-[#0b121e] text-cyan-400 border-2 border-cyan-900/60 hover:border-cyan-400 shadow-[0_0_20px_rgba(6,182,212,0.15)]"
              }`}
            >
              <Mic className={`w-9 h-9 ${isTransmitting ? "animate-bounce text-white" : isCableRxActive ? "text-amber-300" : isVoxEnabled ? "text-emerald-400 glow-emerald" : "text-cyan-400 glow-cyan"}`} />
              <span>
                {isTransmitting 
                  ? "TRANSMITTING..." 
                  : isCableRxActive 
                  ? "CABLE RF RX (INGEST AUDIO)" 
                  : isVoxEnabled 
                  ? "VOX ACTIVE (BERBICARA UNTUK TX)" 
                  : "PRESS & HOLD PTT"}
              </span>
              <span className="text-[11px] font-mono text-slate-400">
                {isTransmitting 
                  ? `TX as ${activeProfile.callsign}` 
                  : isCableGatewayActive 
                  ? `Kabel: ${currentMicDb} dB (Squelch: ${cableNoiseGate} dB)` 
                  : isVoxEnabled 
                  ? `VOX Level: ${currentMicDb} dB` 
                  : `Tombol Fisik: [ ${pttKey} ]`}
              </span>
            </button>
          </div>

          {/* Network Guard Info */}
          <div className="bg-[#0b121e] border border-cyan-900/40 rounded-xl p-2.5 text-xs text-slate-400 flex items-center justify-between font-mono">
            <div className="flex items-center gap-1.5">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <span className="text-slate-300">Net Firewall:</span>
            </div>
            <span className="text-emerald-400 font-bold truncate max-w-[220px]">
              {activeProfile.networkTag}
            </span>
          </div>

        </div>
      )}

      {/* ======================================================== */}
      {/* MODAL 1: AUDIO CABLE GATEWAY CONFIGURATION               */}
      {/* ======================================================== */}
      {showCableModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-3">
          <div className="bg-[#0e1422] border-2 border-amber-500/70 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-amber-900/60 pb-2">
              <h3 className="font-heading font-black text-sm text-white flex items-center gap-2">
                <Cable className="w-4 h-4 text-amber-400" />
                Kabel Audio Radio Gateway (TRRS / Soundcard)
              </h3>
              <button onClick={() => setShowCableModal(false)} className="text-slate-400 hover:text-white p-1">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3.5 text-xs font-mono">
              
              {/* Power Toggle */}
              <div className="flex items-center justify-between bg-[#05080f] p-3 rounded-xl border border-amber-900/40">
                <div>
                  <div className="font-bold text-white">Audio Ingest Gateway:</div>
                  <div className="text-[11px] text-slate-400">Baca suara dari kabel jack radio HT/Rig</div>
                </div>
                <button
                  onClick={() => setIsCableGatewayActive(!isCableGatewayActive)}
                  className={`px-3 py-1.5 rounded-xl font-bold font-heading text-xs transition-all ${
                    isCableGatewayActive ? "bg-amber-400 text-slate-950 shadow-md" : "bg-slate-800 text-slate-400"
                  }`}
                >
                  {isCableGatewayActive ? "ON (AKTIF)" : "OFF"}
                </button>
              </div>

              {/* Input Device Selection */}
              <div>
                <label className="block text-slate-300 font-bold mb-1">Port Input Audio (Line-In / Mic):</label>
                <select
                  value={selectedAudioDeviceId}
                  onChange={(e) => setSelectedAudioDeviceId(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-amber-900/60 rounded-xl text-amber-300 text-xs outline-none"
                >
                  {audioDevices.length === 0 ? (
                    <option value="">Default Audio Input / Headset Jack</option>
                  ) : (
                    audioDevices.map((d, idx) => (
                      <option key={d.deviceId || idx} value={d.deviceId}>
                        {d.label || `Audio Input ${idx + 1} (TRRS / USB Soundcard)`}
                      </option>
                    ))
                  )}
                </select>
                <p className="text-[10px] text-slate-500 mt-1">
                  Pilih "Headset Microphone / External Mic" atau Soundcard USB CM108.
                </p>
              </div>

              {/* Squelch / Noise Gate Threshold */}
              <div>
                <div className="flex justify-between font-bold mb-1">
                  <span className="text-slate-300">Ambang Squelch (Noise Gate):</span>
                  <span className="text-amber-400">{cableNoiseGate} dB</span>
                </div>
                <input
                  type="range"
                  min="-60"
                  max="-10"
                  value={cableNoiseGate}
                  onChange={(e) => setCableNoiseGate(Number(e.target.value))}
                  className="w-full accent-amber-400"
                />
                <div className="flex justify-between text-[9px] text-slate-500">
                  <span>-60 dB (Sensitif)</span>
                  <span>-32 dB (Normal)</span>
                  <span>-10 dB (Ketat)</span>
                </div>
              </div>

              {/* Live Signal Meter */}
              <div className="bg-[#05080f] p-3 rounded-xl border border-amber-900/40 space-y-1">
                <div className="flex justify-between text-[11px] text-slate-400">
                  <span>Level Audio Kabel Saat Ini:</span>
                  <strong className={isCableRxActive ? "text-amber-400" : "text-slate-400"}>
                    {currentMicDb} dB {isCableRxActive ? "(SQUELCH BUKA)" : "(TERKUNCI)"}
                  </strong>
                </div>
                <div className="w-full bg-slate-900 rounded-full h-2 overflow-hidden border border-slate-800">
                  <div 
                    className={`h-full transition-all duration-75 ${isCableRxActive ? "bg-amber-400" : "bg-cyan-500"}`}
                    style={{ width: `${Math.min(100, Math.max(0, (currentMicDb + 100)))}%` }}
                  />
                </div>
              </div>

              {/* DSP Voice Filter Toggle */}
              <div className="flex items-center justify-between p-2.5 bg-[#05080f] rounded-xl border border-amber-900/30">
                <div>
                  <div className="text-white font-bold">Filter Vokal 300Hz - 3.4kHz</div>
                  <div className="text-[10px] text-slate-400">Memotong nada CTCSS & desis noise</div>
                </div>
                <input
                  type="checkbox"
                  checked={enableBandpassFilter}
                  onChange={(e) => setEnableBandpassFilter(e.target.checked)}
                  className="w-4 h-4 accent-amber-400"
                />
              </div>

              {/* 1000 Hz Tone Generator for Radio Calibration */}
              <button
                type="button"
                onClick={toggleTestTone}
                className={`w-full py-2 rounded-xl font-bold flex items-center justify-center gap-1.5 transition-all ${
                  isPlayingTestTone ? "bg-rose-600 text-white animate-pulse" : "bg-[#05080f] border border-amber-900/60 text-amber-300 hover:bg-slate-800"
                }`}
              >
                <Volume1 className="w-4 h-4" />
                <span>{isPlayingTestTone ? "⏹️ Hentikan Nada Uji 1kHz" : "🔊 Putar Nada Uji 1000Hz (Kalibrasi TX)"}</span>
              </button>

              <button
                onClick={() => setShowCableModal(false)}
                className="w-full py-2.5 bg-amber-400 hover:bg-amber-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg"
              >
                Simpan & Aktifkan
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ======================================================== */}
      {/* MODAL 2: VOX SENSITIVITY CONFIGURATION                   */}
      {/* ======================================================== */}
      {showVoxModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-3">
          <div className="bg-[#0e1422] border-2 border-emerald-500/70 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-emerald-900/60 pb-2">
              <h3 className="font-heading font-black text-sm text-white flex items-center gap-1.5">
                <Headphones className="w-4 h-4 text-emerald-400" />
                Pengaturan VOX (Voice Activity Detection)
              </h3>
              <button onClick={() => setShowVoxModal(false)} className="text-slate-400 hover:text-white p-1">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-4 text-xs font-mono">
              <div className="flex items-center justify-between bg-[#05080f] p-3 rounded-xl border border-emerald-900/40">
                <div>
                  <div className="font-bold text-white">Status VOX:</div>
                  <div className="text-[11px] text-slate-400">Aktivasi PTT otomatis saat bersuara</div>
                </div>
                <button
                  onClick={() => setIsVoxEnabled(!isVoxEnabled)}
                  className={`px-3 py-1.5 rounded-xl font-bold font-heading text-xs transition-all ${
                    isVoxEnabled ? "bg-emerald-500 text-slate-950 shadow-md" : "bg-slate-800 text-slate-400"
                  }`}
                >
                  {isVoxEnabled ? "ON (AKTIF)" : "OFF"}
                </button>
              </div>

              {/* Sensitivity Preset Buttons */}
              <div className="space-y-1.5">
                <label className="block text-slate-300 font-bold">Tingkat Sensitivitas Mic:</label>
                <div className="grid grid-cols-3 gap-1.5">
                  <button
                    type="button"
                    onClick={() => setVoxThreshold(-45)}
                    className={`p-2 rounded-xl border text-center transition-all ${
                      voxThreshold === -45 ? "bg-emerald-950 border-emerald-400 text-emerald-300 font-bold" : "bg-[#05080f] border-slate-800 text-slate-400"
                    }`}
                  >
                    <div>Tinggi</div>
                    <div className="text-[9px] text-slate-500">-45 dB (Bisikan)</div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setVoxThreshold(-35)}
                    className={`p-2 rounded-xl border text-center transition-all ${
                      voxThreshold === -35 ? "bg-emerald-950 border-emerald-400 text-emerald-300 font-bold" : "bg-[#05080f] border-slate-800 text-slate-400"
                    }`}
                  >
                    <div>Sedang</div>
                    <div className="text-[9px] text-slate-500">-35 dB (Normal)</div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setVoxThreshold(-25)}
                    className={`p-2 rounded-xl border text-center transition-all ${
                      voxThreshold === -25 ? "bg-emerald-950 border-emerald-400 text-emerald-300 font-bold" : "bg-[#05080f] border-slate-800 text-slate-400"
                    }`}
                  >
                    <div>Rendah</div>
                    <div className="text-[9px] text-slate-500">-25 dB (Bising)</div>
                  </button>
                </div>
              </div>

              {/* Real-time Level Gauge */}
              <div className="bg-[#05080f] p-3 rounded-xl border border-emerald-900/40 space-y-1">
                <div className="flex justify-between text-[11px] text-slate-400">
                  <span>Level Mic Saat Ini:</span>
                  <strong className={currentMicDb > voxThreshold ? "text-rose-400" : "text-emerald-400"}>{currentMicDb} dB</strong>
                </div>
                <div className="w-full bg-slate-900 rounded-full h-2 overflow-hidden border border-slate-800">
                  <div 
                    className={`h-full transition-all duration-75 ${currentMicDb > voxThreshold ? "bg-rose-500" : "bg-emerald-400"}`}
                    style={{ width: `${Math.min(100, Math.max(0, (currentMicDb + 100)))}%` }}
                  />
                </div>
                <div className="text-[9px] text-slate-500 flex justify-between">
                  <span>-100 dB</span>
                  <span className="text-amber-400">Ambang: {voxThreshold} dB</span>
                  <span>0 dB</span>
                </div>
              </div>

              <button
                onClick={() => setShowVoxModal(false)}
                className="w-full py-2.5 bg-emerald-400 hover:bg-emerald-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg"
              >
                Simpan & Tutup
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ======================================================== */}
      {/* MODAL 3: DMR SMS MESSENGER                               */}
      {/* ======================================================== */}
      {showSmsModal && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-3">
          <div className="bg-[#0e1422] border-2 border-purple-500/70 rounded-3xl max-w-sm w-full p-4 space-y-3 shadow-2xl">
            <div className="flex items-center justify-between border-b border-purple-900/60 pb-2">
              <h3 className="font-heading font-black text-sm text-white flex items-center gap-1.5">
                <MessageSquare className="w-4 h-4 text-purple-400" />
                DMR SMS & Quick Dispatch
              </h3>
              <button onClick={() => setShowSmsModal(false)} className="text-slate-400 hover:text-white p-1">
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSendSms} className="space-y-3 text-xs">
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 mb-1 font-mono font-semibold">Tipe SMS:</label>
                  <div className="grid grid-cols-2 gap-1 p-0.5 bg-[#07090e] border border-purple-900/60 rounded-xl">
                    <button
                      type="button"
                      onClick={() => { setSmsType("group"); setSmsTarget("510"); }}
                      className={`py-1 rounded-lg text-[10px] font-mono font-bold ${smsType === "group" ? "bg-cyan-400 text-slate-950" : "text-slate-400"}`}
                    >
                      Group
                    </button>
                    <button
                      type="button"
                      onClick={() => { setSmsType("private"); setSmsTarget("5101001"); }}
                      className={`py-1 rounded-lg text-[10px] font-mono font-bold ${smsType === "private" ? "bg-purple-500 text-white" : "text-slate-400"}`}
                    >
                      Private
                    </button>
                  </div>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1 font-mono font-semibold">Target {smsType === "group" ? "TG" : "ID"}:</label>
                  <input
                    type="number"
                    value={smsTarget}
                    onChange={(e) => setSmsTarget(e.target.value)}
                    className="w-full px-2.5 py-1.5 bg-[#07090e] border border-purple-900/60 rounded-xl text-cyan-400 font-mono font-bold outline-none"
                  />
                </div>
              </div>

              {/* Quick Template Presets */}
              <div>
                <label className="block text-[11px] font-mono text-slate-400 mb-1">⚡ Presets Pesan Cepat:</label>
                <div className="grid grid-cols-2 gap-1 text-[10px]">
                  <button
                    type="button"
                    onClick={() => setSmsContent("🚨 Posko Siaga! Butuh bantuan personel di lokasi.")}
                    className="p-2 bg-[#07090e] hover:bg-purple-950/60 border border-purple-900/40 rounded-xl text-rose-300 text-left truncate font-medium"
                  >
                    🚨 Butuh Bantuan
                  </button>
                  <button
                    type="button"
                    onClick={() => setSmsContent("📍 Situasi posko aman dan terkendali.")}
                    className="p-2 bg-[#07090e] hover:bg-purple-950/60 border border-purple-900/40 rounded-xl text-emerald-300 text-left truncate font-medium"
                  >
                    📍 Posko Aman
                  </button>
                  <button
                    type="button"
                    onClick={() => setSmsContent("📶 Uji modulasi teks DMR. Signal S9+20dB.")}
                    className="p-2 bg-[#07090e] hover:bg-purple-950/60 border border-purple-900/40 rounded-xl text-cyan-300 text-left truncate font-medium"
                  >
                    📶 Cek Signal Teks
                  </button>
                  <button
                    type="button"
                    onClick={() => setSmsContent("⏳ Standby monitor frekuensi. 10-28 clear.")}
                    className="p-2 bg-[#07090e] hover:bg-purple-950/60 border border-purple-900/40 rounded-xl text-amber-300 text-left truncate font-medium"
                  >
                    ⏳ Standby Monitor
                  </button>
                </div>
              </div>

              <div>
                <textarea
                  rows={2}
                  value={smsContent}
                  onChange={(e) => setSmsContent(e.target.value)}
                  placeholder="Ketik pesan teks untuk dikirim ke radio..."
                  className="w-full px-3 py-2 bg-[#07090e] border border-purple-900/60 rounded-xl text-white text-xs font-mono focus:border-purple-400 outline-none"
                />
              </div>

              <button
                type="submit"
                className="w-full py-2.5 bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white font-heading font-black text-xs rounded-xl shadow-lg flex items-center justify-center gap-1.5"
              >
                <Send className="w-3.5 h-3.5" />
                <span>KIRIM PESAN KE RADIO</span>
              </button>
            </form>

            {/* Inbox */}
            <div className="border-t border-purple-900/60 pt-2 space-y-1">
              <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono">
                <span>📥 Riwayat Pesan:</span>
                <span className="text-[9px] text-emerald-400 font-bold">● LIVE INBOX</span>
              </div>
              <div className="max-h-24 overflow-y-auto space-y-1 text-[10px] font-mono pr-1">
                {smsHistory.map((s) => (
                  <div key={s.id} className="bg-[#07090e] p-2 rounded-xl border border-purple-900/40">
                    <span className="text-purple-400 font-bold">{s.sender} [{s.target}]:</span> <span className="text-slate-300">{s.text}</span>
                  </div>
                ))}
              </div>
            </div>

          </div>
        </div>
      )}

    </div>
  );
}
