"use client";

import React, { useState, useEffect, useRef } from "react";
import { useLiveMonitor, registerAudioRxListener, AudioRxData } from "@/hooks/useLiveMonitor";
import { usePhysicalPTT } from "@/hooks/usePhysicalPTT";
import { AudioVisualizer } from "@/components/AudioVisualizer";
import { ProfileSwitcher, UserProfile, DEFAULT_MONITOR_PROFILE } from "@/components/ProfileSwitcher";
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

export interface RadioTalkgroup {
  id: number;
  name: string;
  slot: number;
  mode: string;
  category: string;
}

export const ALL_TALKGROUPS: RadioTalkgroup[] = [
  { id: 510, name: "Indo National DMR", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5101, name: "Jawa Barat Regional", slot: 2, mode: "YSF", category: "Amatir" },
  { id: 5102, name: "Jawa Tengah Regional", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5103, name: "Jawa Timur Regional", slot: 1, mode: "DMR", category: "Amatir" },
  { id: 5109, name: "RAPI Nusantara ASL", slot: 1, mode: "ANALOG", category: "RAPI" },
  { id: 5108, name: "11M CB Digital Gateway", slot: 1, mode: "CB", category: "CB" },
  { id: 5100, name: "Emergency SAR 911", slot: 1, mode: "P25", category: "Komunitas" },
  { id: 9990, name: "Audio Test Echo / Parrot", slot: 2, mode: "DMR", category: "Amatir" },
  { id: 91, name: "BrandMeister WorldWide", slot: 1, mode: "DMR", category: "Amatir" },
];

export function resolveTalkgroup(tgId: number, category: string = "Amatir"): RadioTalkgroup {
  const found = ALL_TALKGROUPS.find((t) => t.id === tgId);
  if (found) return found;
  return {
    id: tgId,
    name: `Talkgroup ${tgId}`,
    slot: 1,
    mode: "DMR",
    category,
  };
}

interface SmsItem {
  id: string;
  sender: string;
  target: string;
  type: "group" | "private";
  text: string;
  time: string;
}

export default function RadioPocketMode() {
  const { activeTx, isConnected, incomingAudio, sendJson } = useLiveMonitor();

  // UI Display Mode: Standard 2.4" Cyberdeck Touchscreen vs 1.8" Rugged PoC Radio
  const [deviceSkin, setDeviceSkin] = useState<"standard" | "poc18">("standard");

  const saveDeviceSkin = (skin: "standard" | "poc18") => {
    setDeviceSkin(skin);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_device_skin", skin);
    }
  };

  // Auto-detect device dimensions on mount and resize if not manually set
  useEffect(() => {
    if (typeof window !== "undefined") {
      const savedSkin = localStorage.getItem("ninjaradio_device_skin");
      if (savedSkin === "standard" || savedSkin === "poc18") {
        setDeviceSkin(savedSkin);
        return;
      }
    }

    const handleScreenSize = () => {
      if (typeof window !== "undefined") {
        const savedSkin = localStorage.getItem("ninjaradio_device_skin");
        if (savedSkin) return;

        // Specialized micro-screen hardware radios have tiny viewports (<= 240x320)
        // Standard smartphones (even 5-6" screens) have CSS pixel width of 360px - 430px
        if (window.innerWidth <= 240 && window.innerHeight <= 320) {
          setDeviceSkin("poc18");
        } else {
          setDeviceSkin("standard");
        }
      }
    };
    handleScreenSize();
    window.addEventListener("resize", handleScreenSize);
    return () => window.removeEventListener("resize", handleScreenSize);
  }, []);

  // PTT Physical Key & Trigger Settings Modal State
  const [showPttSettingsModal, setShowPttSettingsModal] = useState(false);

  // Frequency & Tuner State
  const [radioTxFreq, setRadioTxFreq] = useState<string>("438.8000");
  const [radioRxFreq, setRadioRxFreq] = useState<string>("438.8000");
  const [radioColorCode, setRadioColorCode] = useState<number>(1);
  const [radioTxPwr, setRadioTxPwr] = useState<number>(25);
  const [showFreqModal, setShowFreqModal] = useState<boolean>(false);
  const [isTuningSaving, setIsTuningSaving] = useState<boolean>(false);
  const [tempTxFreq, setTempTxFreq] = useState<string>("438.8000");
  const [tempRxFreq, setTempRxFreq] = useState<string>("438.8000");
  const [isSimplexLocked, setIsSimplexLocked] = useState<boolean>(true);

  useEffect(() => {
    fetch("/api/config")
      .then((r) => r.json())
      .then((d) => {
        if (d.tx_freq) {
          setRadioTxFreq(d.tx_freq);
          setTempTxFreq(d.tx_freq);
        }
        if (d.rx_freq) {
          setRadioRxFreq(d.rx_freq);
          setTempRxFreq(d.rx_freq);
        }
        if (d.color_code !== undefined) setRadioColorCode(Number(d.color_code));
        if (d.tx_pwr !== undefined) setRadioTxPwr(Number(d.tx_pwr));
      })
      .catch(() => {});
  }, []);

  const adjustFreqStep = (deltaMhz: number) => {
    const curTx = parseFloat(tempTxFreq) || 438.8000;
    const nextTx = (curTx + deltaMhz).toFixed(4);
    setTempTxFreq(nextTx);
    if (isSimplexLocked) {
      setTempRxFreq(nextTx);
    }
  };

  const handleSaveFrequency = async () => {
    setIsTuningSaving(true);
    try {
      const res = await fetch("/api/config/frequency", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          tx_freq: tempTxFreq,
          rx_freq: tempRxFreq,
          color_code: radioColorCode,
          tx_pwr: radioTxPwr,
        }),
      });
      if (res.ok) {
        setRadioTxFreq(tempTxFreq);
        setRadioRxFreq(tempRxFreq);
        beepPlayer?.playTalkGrant();
        setShowFreqModal(false);
      }
    } catch (e) {
      console.warn("Failed to tune frequency:", e);
    } finally {
      setIsTuningSaving(false);
    }
  };

  // PWA Install Prompt State
  const [deferredInstallPrompt, setDeferredInstallPrompt] = useState<any>(null);
  const [isPwaInstalled, setIsPwaInstalled] = useState(false);

  useEffect(() => {
    const handleBeforeInstall = (e: any) => {
      e.preventDefault();
      setDeferredInstallPrompt(e);
    };
    window.addEventListener("beforeinstallprompt", handleBeforeInstall);

    if (typeof window !== "undefined" && window.matchMedia("(display-mode: standalone)").matches) {
      setIsPwaInstalled(true);
    }

    return () => window.removeEventListener("beforeinstallprompt", handleBeforeInstall);
  }, []);

  const handleInstallPwa = async () => {
    if (!deferredInstallPrompt) {
      alert("Catatan Instalasi PWA Android:\n1. Pastikan mengakses via domain HTTPS (bukan http IP mentah).\n2. Di menu Chrome (titik 3 di kanan atas), klik 'Pasang Aplikasi' / 'Install App'.");
      return;
    }
    deferredInstallPrompt.prompt();
    const choice = await deferredInstallPrompt.userChoice;
    if (choice && choice.outcome === "accepted") {
      setIsPwaInstalled(true);
      setDeferredInstallPrompt(null);
    }
  };

  // Default identity is Monitor Mode (TX locked) until user creates profile or enters PIN
  const [activeProfile, setActiveProfile] = useState<UserProfile>(DEFAULT_MONITOR_PROFILE);

  // Sync saved active operator profile to backend/core on load
  useEffect(() => {
    if (typeof window !== "undefined") {
      try {
        const saved = localStorage.getItem("ninjaradio_active_profile");
        if (saved) {
          const p = JSON.parse(saved);
          if (p && p.dmrId > 0) {
            setActiveProfile(p);
            fetch("/api/operator/switch", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ callsign: p.callsign, dmr_id: p.dmrId }),
            }).catch(() => {});
          }
        }
      } catch {}
    }
  }, []);

  // Anti-Abuse / Operator Auth Modal State
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [authModeTab, setAuthModeTab] = useState<"custom" | "pin">("custom");
  const [authPinInput, setAuthPinInput] = useState("");
  const [authPinError, setAuthPinError] = useState("");
  const [isVerifyingAuthPin, setIsVerifyingAuthPin] = useState(false);
  const [authCategory, setAuthCategory] = useState<"Amatir" | "RAPI" | "CB" | "Komunitas">("Amatir");
  const [authCallsign, setAuthCallsign] = useState("");
  const [authName, setAuthName] = useState("");
  const [authDmrId, setAuthDmrId] = useState<number>(5104001);
  const [authTargetTg, setAuthTargetTg] = useState<number>(510);
  const [isAuthCustomTg, setIsAuthCustomTg] = useState(false);
  const [authCustomTgInput, setAuthCustomTgInput] = useState("");

  // TalkGroup Active Object State (Locks TG and avoids reverting to 510)
  const [currentTg, setCurrentTg] = useState<RadioTalkgroup>(ALL_TALKGROUPS[0]);
  const [currentTimeSlot, setCurrentTimeSlot] = useState<1 | 2>(1);

  const toggleTimeSlot = () => {
    const nextSlot = (currentTimeSlot === 1 ? 2 : 1) as 1 | 2;
    setCurrentTimeSlot(nextSlot);
    beepPlayer?.playRogerBeep();
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_locked_ts", String(nextSlot));
    }
  };

  const tuneToTalkgroup = (tg: RadioTalkgroup) => {
    setCurrentTg(tg);
    if (tg.slot === 1 || tg.slot === 2) {
      setCurrentTimeSlot(tg.slot as 1 | 2);
    }
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_locked_tg", String(tg.id));
      localStorage.setItem("ninjaradio_locked_ts", String(tg.slot || 1));
    }
  };

  // Load saved profile and locked TG on mount, purging legacy N1NJA
  useEffect(() => {
    if (typeof window !== "undefined") {
      let initialProf = DEFAULT_MONITOR_PROFILE;
      const saved = localStorage.getItem("ninjaradio_active_profile");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (parsed && parsed.callsign !== "N1NJA" && parsed.id !== "prof_default" && parsed.callsign !== "BD62AA") {
            initialProf = parsed;
            setActiveProfile(parsed);
          }
        } catch {}
      }

      // Check saved locked TimeSlot
      const savedTs = localStorage.getItem("ninjaradio_locked_ts");
      if (savedTs === "1" || savedTs === "2") {
        setCurrentTimeSlot(Number(savedTs) as 1 | 2);
      }

      // Check saved locked TG first
      const savedTg = localStorage.getItem("ninjaradio_locked_tg");
      if (savedTg) {
        const tgNum = Number(savedTg);
        if (!isNaN(tgNum) && tgNum > 0) {
          const resolved = resolveTalkgroup(tgNum, initialProf.category);
          setCurrentTg(resolved);
          if (!savedTs && (resolved.slot === 1 || resolved.slot === 2)) {
            setCurrentTimeSlot(resolved.slot as 1 | 2);
          }
          return;
        }
      }

      // Otherwise fall back to profile default TG if exists
      if (initialProf.defaultTg) {
        const resolved = resolveTalkgroup(initialProf.defaultTg, initialProf.category);
        setCurrentTg(resolved);
        if (!savedTs && (resolved.slot === 1 || resolved.slot === 2)) {
          setCurrentTimeSlot(resolved.slot as 1 | 2);
        }
      }
    }
  }, []);

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

  // Filter talkgroups strictly allowed for the active profile (TG 9990 Echo is always permitted, and current tuned TG is guaranteed)
  const availableTgs = React.useMemo(() => {
    const list = ALL_TALKGROUPS.filter((tg) =>
      activeProfile.allowedTalkgroups.includes(tg.id) || tg.id === 9990 || tg.id === currentTg.id
    );
    if (!list.some((t) => t.id === currentTg.id)) {
      list.unshift(currentTg);
    }
    return list;
  }, [activeProfile.allowedTalkgroups, currentTg]);

  const selectedTgIndex = Math.max(0, availableTgs.findIndex((t) => t.id === currentTg.id));

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordedAudioChunksRef = useRef<Blob[]>([]);
  const [isEchoPlaying, setIsEchoPlaying] = useState(false);

  // Live RX Audio Player from DMR Network WebSocket stream
  const rxAudioCtxRef = useRef<AudioContext | null>(null);
  const rxGainNodeRef = useRef<GainNode | null>(null);
  const rxInputNodeRef = useRef<AudioNode | null>(null);
  const rxNextTimeRef = useRef<number>(0);
  const lastPlayedChunkRef = useRef<string>("");
  const [audioUnlocked, setAudioUnlocked] = useState<boolean>(false);

  const ensureAudioUnlocked = () => {
    try {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioCtx) return;

      if (!rxAudioCtxRef.current) {
        const ctx = new AudioCtx();

        // 1. Communications Low-pass filter (3400 Hz) to eliminate vocoder quantization hiss/scratch ("kresek-kresek")
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
        gainNode.gain.value = 1.6;

        // 4. Dynamics Compressor: prevents digital clipping & distortion on loud voice peaks
        const comp = ctx.createDynamicsCompressor();
        comp.threshold.value = -12;
        comp.knee.value = 6;
        comp.ratio.value = 4;
        comp.attack.value = 0.003;
        comp.release.value = 0.15;

        // Pipeline: source -> hpf -> lpf -> gainNode -> comp -> destination
        hpf.connect(lpf);
        lpf.connect(gainNode);
        gainNode.connect(comp);
        comp.connect(ctx.destination);

        rxAudioCtxRef.current = ctx;
        rxGainNodeRef.current = gainNode;
        rxInputNodeRef.current = hpf;
      }

      if (rxAudioCtxRef.current.state === "suspended") {
        rxAudioCtxRef.current.resume().then(() => {
          setAudioUnlocked(true);
        }).catch(() => {});
      } else if (rxAudioCtxRef.current.state === "running") {
        setAudioUnlocked(true);
      }
    } catch (e) {
      console.warn("Audio unlock note:", e);
    }
  };

  // Autoplay Policy: Unlock Web AudioContext on any user interaction
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

  const activeProfileRef = useRef(activeProfile);
  useEffect(() => {
    activeProfileRef.current = activeProfile;
  }, [activeProfile]);

  const playAudioChunk = (audioChunk: AudioRxData) => {
    // Half-duplex lockout: Do not play incoming audio while user is transmitting or muted
    if (!audioChunk || !audioChunk.pcm) return;
    if (isMutedRef.current || isTxRef.current) return;
    // Suppress self-echo audio ONLY if NOT on Echo / Parrot test (TG 9990)
    if (audioChunk.tg !== 9990 && audioChunk.src_id && audioChunk.src_id === activeProfileRef.current.dmrId) return;

    // Deduplicate identical chunks if triggered by both listener & incomingAudio
    if (lastPlayedChunkRef.current === audioChunk.pcm) return;
    lastPlayedChunkRef.current = audioChunk.pcm;

    try {
      ensureAudioUnlocked();
      const ctx = rxAudioCtxRef.current;
      if (!ctx) return;

      if (ctx.state === "suspended") {
        ctx.resume().then(() => setAudioUnlocked(true)).catch(() => {});
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

      // Micro cosine envelope (4 samples fade-in & fade-out) to eliminate chunk boundary pops ("klutuk-klutuk")
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
      } else if (rxGainNodeRef.current) {
        source.connect(rxGainNodeRef.current);
      } else {
        source.connect(ctx.destination);
      }

      const now = ctx.currentTime;
      // DMR 3-frame TDMA bursts arrive in 60-80ms cycles.
      // Use 150ms cushion so TDMA inter-burst gaps (>70ms) never under-run and cause clicks.
      if (rxNextTimeRef.current < now || rxNextTimeRef.current > now + 0.45) {
        rxNextTimeRef.current = now + 0.15;
      }

      source.start(rxNextTimeRef.current);
      rxNextTimeRef.current += audioBuffer.duration;
    } catch (e) {
      console.warn("Audio playback error:", e);
    }
  };

  useEffect(() => {
    const unsubscribe = registerAudioRxListener(playAudioChunk);
    return () => {
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    if (incomingAudio) {
      playAudioChunk(incomingAudio);
    }
  }, [incomingAudio]);

  // Live TX Microphone Streamer to USRP / DMR Network
  // Persistent refs prevent Chromium/V8 Garbage Collector from killing the audio pipeline after 2 seconds
  const txProcessorRef = useRef<ScriptProcessorNode | null>(null);
  const txSourceNodeRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const txMuteGainRef = useRef<GainNode | null>(null);
  const txMicStreamRef = useRef<MediaStream | null>(null);
  const txAudioCtxRef = useRef<AudioContext | null>(null);
  const txHeartbeatIntervalRef = useRef<any>(null);

  const startLiveMicrophoneStream = async (tgNum: number) => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        }
      });
      txMicStreamRef.current = stream;

      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const ctx = new AudioCtx();
      txAudioCtxRef.current = ctx;

      const source = ctx.createMediaStreamSource(stream);
      txSourceNodeRef.current = source;

      const bufferSize = 2048;
      const processor = ctx.createScriptProcessor(bufferSize, 1, 1);
      txProcessorRef.current = processor;

      processor.onaudioprocess = (e) => {
        if (!isTxRef.current) return;
        const inputData = e.inputBuffer.getChannelData(0);
        const sampleRateRatio = ctx.sampleRate / 8000;
        const targetLen = Math.floor(inputData.length / sampleRateRatio);
        const pcm16 = new Int16Array(targetLen);

        for (let i = 0; i < targetLen; i++) {
          const origIdx = Math.floor(i * sampleRateRatio);
          const s = Math.max(-1, Math.min(1, inputData[origIdx]));
          pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }

        const uint8 = new Uint8Array(pcm16.buffer);
        let binary = "";
        for (let i = 0; i < uint8.byteLength; i++) {
          binary += String.fromCharCode(uint8[i]);
        }
        const b64 = btoa(binary);

        sendJson({
          type: "AUDIO_TX",
          pcm: b64,
          is_tx: true,
          tg: tgNum,
          slot: currentTimeSlot,
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
        });
      };

      source.connect(processor);
      // Route through MediaStreamDestination to keep Chromium audio thread actively running
      try {
        const streamDest = ctx.createMediaStreamDestination();
        processor.connect(streamDest);
      } catch {
        const muteGain = ctx.createGain();
        muteGain.gain.value = 0.00001;
        txMuteGainRef.current = muteGain;
        processor.connect(muteGain);
        muteGain.connect(ctx.destination);
      }
    } catch (err) {
      console.warn("Could not start Web TX microphone stream:", err);
    }
  };

  const stopLiveMicrophoneStream = () => {
    if (txProcessorRef.current) {
      try { txProcessorRef.current.disconnect(); } catch {}
      txProcessorRef.current = null;
    }
    if (txSourceNodeRef.current) {
      try { txSourceNodeRef.current.disconnect(); } catch {}
      txSourceNodeRef.current = null;
    }
    if (txMuteGainRef.current) {
      try { txMuteGainRef.current.disconnect(); } catch {}
      txMuteGainRef.current = null;
    }
    if (txAudioCtxRef.current) {
      try { txAudioCtxRef.current.close(); } catch {}
      txAudioCtxRef.current = null;
    }
    if (txMicStreamRef.current) {
      try { txMicStreamRef.current.getTracks().forEach((t) => t.stop()); } catch {}
      txMicStreamRef.current = null;
    }
  };

  // Hardware Physical PTT Listener (handles phone volume buttons, bluetooth, spacebar, PoC keycodes)
  const {
    isTransmitting,
    startTx,
    stopTx,
    toggleTx,
    pttKey,
    pttMode,
    saveCustomKey,
    savePttMode,
    lastKeyPressed,
  } = usePhysicalPTT({
    onTransmitStart: async () => {
      ensureAudioUnlocked();
      // Anti-abuse: if monitor mode, lock PTT and prompt authentication modal
      if (activeProfile.isMonitorOnly || activeProfile.callsign === "SWL-MONITOR" || activeProfile.dmrId === 0) {
        setShowAuthModal(true);
        beepPlayer?.playRogerBeep();
        return;
      }

      const targetTgNum = callType === "private" ? Number(privateId) : currentTg.id;

      // Immediately flag TX active for mic audio processor
      isTxRef.current = true;

      // Start periodic PTT keep-alive heartbeat to prevent server timeout
      if (txHeartbeatIntervalRef.current) clearInterval(txHeartbeatIntervalRef.current);
      txHeartbeatIntervalRef.current = setInterval(() => {
        if (!isTxRef.current) return;
        sendJson({
          type: "PTT_HEARTBEAT",
          tg: targetTgNum,
          slot: currentTimeSlot,
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
        });
      }, 250);

      // Start real microphone audio streaming through USRP & DMR network
      startLiveMicrophoneStream(targetTgNum);

      fetch("/api/ptt/transmit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
          tg: targetTgNum,
          slot: currentTimeSlot,
          call_type: callType,
          category: activeProfile.category,
          is_transmitting: true,
        }),
      }).catch(() => {});

      // Record microphone for TG 9990 Echo / Parrot test
      if (targetTgNum === 9990) {
        try {
          recordedAudioChunksRef.current = [];
          if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const recorder = new MediaRecorder(stream);
            recorder.ondataavailable = (e) => {
              if (e.data && e.data.size > 0) recordedAudioChunksRef.current.push(e.data);
            };
            recorder.start(100);
            mediaRecorderRef.current = recorder;
          }
        } catch (err) {
          console.warn("Mic permission for Echo test:", err);
        }
      }
    },
    onTransmitEnd: () => {
      ensureAudioUnlocked();
      const targetTgNum = callType === "private" ? Number(privateId) : currentTg.id;

      isTxRef.current = false;

      // Stop periodic PTT keep-alive heartbeat
      if (txHeartbeatIntervalRef.current) {
        clearInterval(txHeartbeatIntervalRef.current);
        txHeartbeatIntervalRef.current = null;
      }

      // Stop real microphone audio streaming
      stopLiveMicrophoneStream();

      // Send unkey event to WebSocket audio streamer
      sendJson({
        type: "AUDIO_TX",
        pcm: null,
        is_tx: false,
        tg: targetTgNum,
        slot: currentTimeSlot,
        callsign: activeProfile.callsign,
        dmr_id: activeProfile.dmrId,
      });

      fetch("/api/ptt/transmit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: activeProfile.callsign,
          dmr_id: activeProfile.dmrId,
          tg: targetTgNum,
          slot: currentTimeSlot,
          call_type: callType,
          category: activeProfile.category,
          is_transmitting: false,
        }),
      }).catch(() => {});

      // Replay recorded voice on TG 9990 Echo test after 1.2s
      if (targetTgNum === 9990 && mediaRecorderRef.current) {
        const recorder = mediaRecorderRef.current;
        if (recorder.state !== "inactive") {
          recorder.onstop = () => {
            const blob = new Blob(recordedAudioChunksRef.current, { type: "audio/webm" });
            if (blob.size > 300) {
              const url = URL.createObjectURL(blob);
              setTimeout(() => {
                setIsEchoPlaying(true);
                const audio = new Audio(url);
                audio.play().catch(() => {});
                audio.onended = () => {
                  setIsEchoPlaying(false);
                  beepPlayer?.playRogerBeep();
                };
              }, 1200);
            }
          };
          recorder.stop();
          recorder.stream.getTracks().forEach((t) => t.stop());
        }
      }
    },
  });

  useEffect(() => {
    isTxRef.current = isTransmitting;
  }, [isTransmitting]);

  useEffect(() => {
    if (activeTx?.callsign === "PARROT" && !isEchoPlaying) {
      beepPlayer?.playTalkGrant();
    }
  }, [activeTx?.callsign, isEchoPlaying]);

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
      if (showSmsModal || showDialer || showVoxModal || showCableModal || showAuthModal) return;
      const keyNum = parseInt(e.key);
      if (!isNaN(keyNum) && keyNum >= 1 && keyNum <= availableTgs.length) {
        tuneToTalkgroup(availableTgs[keyNum - 1]);
        beepPlayer?.playTalkGrant();
      }
    };
    window.addEventListener("keydown", handleT9);
    return () => window.removeEventListener("keydown", handleT9);
  }, [availableTgs, showSmsModal, showDialer, showVoxModal, showCableModal, showAuthModal]);

  const nextChannel = () => {
    if (availableTgs.length === 0) return;
    const nextIdx = (selectedTgIndex + 1) % availableTgs.length;
    tuneToTalkgroup(availableTgs[nextIdx]);
    beepPlayer?.playRogerBeep();
  };

  const prevChannel = () => {
    if (availableTgs.length === 0) return;
    const prevIdx = (selectedTgIndex - 1 + availableTgs.length) % availableTgs.length;
    tuneToTalkgroup(availableTgs[prevIdx]);
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
      setPrivateId(dialInput.trim());
    } else {
      const customId = Number(dialInput.trim());
      if (!isNaN(customId) && customId > 0) {
        const resolved = resolveTalkgroup(customId, activeProfile.category);
        tuneToTalkgroup(resolved);
      }
    }
    setShowDialer(false);
    setDialInput("");
    beepPlayer?.playTalkGrant();
  };

  const handleQuickDial = (tgId: number) => {
    const resolved = resolveTalkgroup(tgId, activeProfile.category);
    tuneToTalkgroup(resolved);
    setShowDialer(false);
    beepPlayer?.playTalkGrant();
  };

  return (
    <div className="min-h-screen bg-[#05070c] text-slate-100 flex flex-col justify-between select-none p-2 sm:p-4 max-w-lg mx-auto">
      
      {/* Top Device Header */}
      <div className="flex items-center justify-between border-b border-cyan-900/40 pb-2">
        <Link
          href="/"
          className="flex items-center gap-1 text-[11px] font-mono font-bold text-slate-400 hover:text-cyan-400 transition-colors"
        >
          <ChevronLeft className="w-4 h-4 text-cyan-400" />
          <span>NOC DASHBOARD</span>
        </Link>

        <div className="flex items-center gap-1.5">
          {/* PWA Install Button (Displays when install prompt is available or user wants to install) */}
          {deferredInstallPrompt && !isPwaInstalled && (
            <button
              onClick={handleInstallPwa}
              className="px-2 py-1 rounded-lg text-[10px] font-mono font-black bg-cyan-400 hover:bg-cyan-300 text-slate-950 flex items-center gap-1 shadow transition-all animate-pulse"
              title="Pasang NinjaRadio PWA ke Layar Utama Hape"
            >
              <Smartphone className="w-3 h-3" />
              <span>Install PWA</span>
            </button>
          )}

          {/* Quick Frequency & Tuner Button */}
          <button
            onClick={() => {
              setTempTxFreq(radioTxFreq);
              setTempRxFreq(radioRxFreq);
              setShowFreqModal(true);
            }}
            className="px-2 py-1 rounded-lg text-[10px] font-mono font-black border border-cyan-900/50 bg-[#0b121e] text-cyan-300 hover:border-cyan-400 flex items-center gap-1 transition-all"
            title="Pengaturan Frekuensi TX/RX & Tuner VFO"
          >
            <Radio className="w-3 h-3 text-cyan-400" />
            <span>{radioTxFreq} MHz</span>
          </button>

          {/* Quick PTT Mode & Key Setting Button */}
          <button
            onClick={() => setShowPttSettingsModal(true)}
            className="px-2 py-1 rounded-lg text-[10px] font-mono font-black border border-cyan-900/50 bg-[#0b121e] text-cyan-300 hover:border-cyan-400 flex items-center gap-1 transition-all"
            title="Pengaturan Tombol Fisik PTT (Volume Hape / Spasi / Headset)"
          >
            <SlidersVertical className="w-3 h-3 text-cyan-400" />
            <span>PTT: {pttMode === "hold" ? "HOLD" : "TOGGLE"}</span>
          </button>
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
            onClick={() => {
              ensureAudioUnlocked();
              setIsMuted(!isMuted);
            }}
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
              if (typeof window !== "undefined") {
                localStorage.setItem("ninjaradio_active_profile", JSON.stringify(newProf));
              }
              if (newProf.dmrId && newProf.dmrId > 0) {
                fetch("/api/operator/switch", {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({ callsign: newProf.callsign, dmr_id: newProf.dmrId }),
                }).catch(() => {});
              }
              if (newProf.defaultTg) {
                tuneToTalkgroup(resolveTalkgroup(newProf.defaultTg, newProf.category));
              } else if (!newProf.allowedTalkgroups.includes(currentTg.id)) {
                const fallbackId = newProf.allowedTalkgroups[0] || 510;
                tuneToTalkgroup(resolveTalkgroup(fallbackId, newProf.category));
              }
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

      {/* Browser Autoplay Audio Unlock Alert */}
      {!audioUnlocked && (
        <button
          onClick={() => ensureAudioUnlocked()}
          className="w-full py-2 px-3 my-1.5 rounded-xl bg-gradient-to-r from-amber-500/20 via-cyan-500/20 to-amber-500/20 border border-amber-400/50 flex items-center justify-between text-amber-300 text-xs font-mono animate-pulse hover:bg-amber-500/30 transition shadow-lg"
        >
          <div className="flex items-center gap-2">
            <Volume2 className="w-4 h-4 text-amber-400" />
            <span>Speaker belum aktif. Ketuk untuk mengaktifkan suara audio</span>
          </div>
          <span className="text-[10px] bg-amber-500 text-slate-950 font-bold px-2 py-0.5 rounded-lg">
            AKTIFKAN
          </span>
        </button>
      )}

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
                <button
                  type="button"
                  onClick={toggleTimeSlot}
                  className="bg-emerald-950 hover:bg-emerald-900 px-1.5 py-0.2 rounded text-[8px] font-bold text-emerald-300 border border-emerald-800 transition-all cursor-pointer active:scale-95"
                  title="Klik untuk ganti TimeSlot (TS1 ↔ TS2)"
                >
                  TS{currentTimeSlot} ⇄
                </button>
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
              ) : isEchoPlaying ? (
                <div className="animate-pulse">
                  <div className="bg-emerald-500 text-slate-950 text-[9px] font-black uppercase rounded py-0.5 mb-1">
                    🟢 RX ECHO TEST
                  </div>
                  <div className="text-xl font-black text-emerald-300 tracking-wider">
                    {activeProfile.callsign}
                  </div>
                  <div className="text-[10px] text-emerald-400 truncate">
                    Echo Parrot Audio Playback
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
                <div
                  onClick={() => setShowDialer(true)}
                  className="cursor-pointer group hover:bg-emerald-950/50 p-1 rounded-lg transition-all"
                  title="Klik untuk ganti atau dial TalkGroup tujuan"
                >
                  <div className="text-[9px] text-emerald-600 group-hover:text-cyan-400 uppercase font-bold tracking-wider flex items-center justify-center gap-1">
                    <span>CH {selectedTgIndex + 1} • {callType === "private" ? "PRIVATE" : "GROUP"}</span>
                    <span className="text-[8px] bg-emerald-900/60 group-hover:bg-cyan-900/80 px-1 rounded text-emerald-300 group-hover:text-cyan-300">✎ DIAL</span>
                  </div>
                  <div className="text-2xl font-black text-emerald-400 group-hover:text-cyan-300 tracking-wider">
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
                isActive={isTransmitting || !!activeTx || isCableRxActive || isEchoPlaying} 
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
                      tuneToTalkgroup(availableTgs[num - 1]);
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
            <div className="flex items-center justify-between px-1 mb-1 text-[9px] font-mono text-slate-400">
              <button
                type="button"
                onClick={() => savePttMode(pttMode === "hold" ? "toggle" : "hold")}
                className="text-amber-400 font-bold hover:underline"
              >
                PTT: {pttMode === "hold" ? "🖐️ HOLD" : "🔒 TOGGLE"}
              </button>
              <button
                type="button"
                onClick={() => setShowPttSettingsModal(true)}
                className="text-cyan-400 font-bold hover:underline"
              >
                ⚙️ {pttKey === "AUTO" ? "VOL/SPASI" : pttKey}
              </button>
            </div>
            <button
              onClick={pttMode === "toggle" ? toggleTx : undefined}
              onMouseDown={pttMode === "hold" ? startTx : undefined}
              onMouseUp={pttMode === "hold" ? stopTx : undefined}
              onTouchStart={pttMode === "hold" ? (e) => { e.preventDefault(); startTx(); } : undefined}
              onTouchEnd={pttMode === "hold" ? (e) => { e.preventDefault(); stopTx(); } : undefined}
              className={`w-full py-4 rounded-2xl font-heading font-black text-sm tracking-wider flex items-center justify-center gap-2 shadow-2xl transition-all ${
                isTransmitting
                  ? "bg-rose-600 text-white shadow-rose-600/50 border-2 border-rose-300 ring-4 ring-rose-900/50"
                  : activeProfile.isMonitorOnly
                  ? "bg-slate-900 text-amber-300 border-2 border-amber-500/60 hover:border-amber-400"
                  : "bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-slate-950 shadow-amber-500/20"
              }`}
            >
              {activeProfile.isMonitorOnly ? (
                <span>🔒 PTT TERKUNCI (KLIK UNTUK PIN / CALLSIGN)</span>
              ) : (
                <>
                  <Mic className="w-5 h-5" />
                  <span>
                    {isTransmitting 
                      ? pttMode === "toggle" ? "🔴 ON AIR (TAP STOP)" : "🔴 TRANSMITTING..." 
                      : isVoxEnabled 
                      ? "🎙️ VOX ACTIVE (AUTO TALK)" 
                      : pttMode === "toggle" ? "🎙️ TAP TO TALK" : `HOLD PTT [ VOL / ${pttKey === "AUTO" ? "SPASI" : pttKey} ]`}
                  </span>
                </>
              )}
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
              <div className="flex items-center gap-1.5 font-mono text-[11px]">
                <button
                  type="button"
                  onClick={() => {
                    setTempTxFreq(radioTxFreq);
                    setTempRxFreq(radioRxFreq);
                    setShowFreqModal(true);
                  }}
                  className="px-2 py-0.5 rounded font-mono font-black text-[11px] bg-cyan-950/60 hover:bg-cyan-900/80 text-cyan-300 border border-cyan-700/60 transition-all cursor-pointer shadow-sm"
                  title="Klik untuk membuka Tuner Frekuensi VFO"
                >
                  📡 {radioTxFreq} MHz ✎
                </button>
                <button
                  type="button"
                  onClick={toggleTimeSlot}
                  className={`px-2 py-0.5 rounded font-mono font-black text-[11px] transition-all flex items-center gap-1 cursor-pointer active:scale-95 border ${
                    currentTimeSlot === 1
                      ? "bg-emerald-500/20 text-emerald-300 border-emerald-500/60 hover:bg-emerald-500/30 shadow-sm"
                      : "bg-cyan-500/20 text-cyan-300 border-cyan-500/60 hover:bg-cyan-500/30 shadow-sm"
                  }`}
                  title="Klik untuk ganti TimeSlot (TS1 ↔ TS2)"
                >
                  <span>TS{currentTimeSlot}</span>
                  <span className="text-[9px] opacity-80">⇄</span>
                </button>
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
              ) : isEchoPlaying ? (
                <div className="animate-pulse">
                  <div className="inline-block px-2.5 py-0.5 bg-emerald-500 text-slate-950 font-black text-xs rounded tracking-widest uppercase mb-1">
                    🟢 RX ECHO PARROT PLAYBACK
                  </div>
                  <div className="text-3xl font-black text-emerald-300 tracking-wider">
                    {activeProfile.callsign}
                  </div>
                  <div className="text-xs text-emerald-400 font-mono mt-0.5">
                    TG 9990 • Memutar Ulang Rekaman Suara Anda
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
                <div
                  onClick={() => setShowDialer(true)}
                  className="cursor-pointer group hover:bg-emerald-950/40 p-1.5 rounded-xl transition-all"
                  title="Klik untuk ganti atau dial TalkGroup tujuan"
                >
                  <div className="text-[10px] text-emerald-600 group-hover:text-cyan-400 uppercase font-bold tracking-widest mb-0.5 flex items-center justify-center gap-1.5">
                    <span>{callType === "private" ? "DIRECT PRIVATE TARGET" : `CURRENT CHANNEL [${activeProfile.category.toUpperCase()}]`}</span>
                    <span className="text-[9px] bg-emerald-900/60 group-hover:bg-cyan-900/80 px-1.5 py-0.2 rounded text-emerald-300 group-hover:text-cyan-300 font-mono">
                      Ganti TG ✎
                    </span>
                  </div>
                  <div className="text-3xl font-black text-emerald-400 group-hover:text-cyan-300 tracking-wider">
                    {callType === "private" ? `ID ${privateId}` : `TG ${currentTg.id}`}
                  </div>
                  <div className="text-xs text-emerald-500 group-hover:text-cyan-400 font-bold mt-0.5">
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
                isActive={isTransmitting || !!activeTx || isCableRxActive || isEchoPlaying} 
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
            <div className="bg-[#0b121e] border-2 border-cyan-700/60 rounded-2xl p-3 space-y-2.5 shadow-xl">
              <div className="flex items-center justify-between text-xs border-b border-cyan-900/50 pb-1.5">
                <span className="font-bold text-cyan-300 flex items-center gap-1">
                  <PhoneCall className="w-3.5 h-3.5 text-cyan-400" />
                  {callType === "private" ? "Dial Private DMR ID" : "Pilih atau Masukkan TalkGroup Tujuan"}
                </span>
                <button
                  type="button"
                  onClick={() => setShowDialer(false)}
                  className="text-slate-400 hover:text-white text-xs font-bold px-1.5 py-0.5 rounded"
                >
                  ✕ Tutup
                </button>
              </div>

              {callType === "group" && (
                <div>
                  <div className="text-[10px] text-slate-400 font-semibold mb-1">Preset TalkGroup Populer (Klik untuk Lock):</div>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      { id: 510, label: "510 (Nasional)" },
                      { id: 5101, label: "5101 (Jabar)" },
                      { id: 5102, label: "5102 (Jateng)" },
                      { id: 5103, label: "5103 (Jatim)" },
                      { id: 5109, label: "5109 (RAPI)" },
                      { id: 5108, label: "5108 (CB 11M)" },
                      { id: 5100, label: "5100 (SAR)" },
                      { id: 9990, label: "9990 (Echo Test)" },
                      { id: 91, label: "91 (WorldWide)" },
                    ].map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleQuickDial(preset.id)}
                        className={`text-[10px] font-mono font-bold px-2 py-1 rounded-lg border transition-all ${
                          currentTg.id === preset.id
                            ? "bg-cyan-400 text-slate-950 border-cyan-300 shadow-sm"
                            : "bg-slate-900 border-slate-700 text-slate-300 hover:border-cyan-400 hover:text-cyan-300"
                        }`}
                      >
                        TG {preset.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              <form onSubmit={handleApplyDial} className="space-y-2">
                <div className="text-[10px] text-slate-400 font-semibold">
                  {callType === "private" ? "Ketik DMR ID Tujuan:" : "Atau Masukkan Nomor TG Manual:"}
                </div>
                <div className="flex gap-2">
                  <input
                    type="number"
                    autoFocus
                    placeholder={callType === "private" ? "Contoh: 5101002" : "Contoh: 5105, 510, 91, 5104"}
                    value={dialInput}
                    onChange={(e) => setDialInput(e.target.value)}
                    className="flex-1 px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-xs text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                  />
                  <button
                    type="submit"
                    className="px-4 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-black text-xs rounded-xl shadow-md transition-colors"
                  >
                    Set & Kunci TG
                  </button>
                </div>
              </form>
            </div>
          )}

          {/* Quick PTT Trigger Mode & Hardware Keybar */}
          <div className="flex items-center justify-between w-full max-w-[320px] px-2 text-[10px] font-mono text-slate-400">
            <div className="flex items-center gap-1.5">
              <span>Mode:</span>
              <button
                type="button"
                onClick={() => savePttMode(pttMode === "hold" ? "toggle" : "hold")}
                className={`px-2 py-0.5 rounded-lg font-bold border transition-all ${
                  pttMode === "toggle"
                    ? "bg-amber-400 text-slate-950 border-amber-300 shadow-sm"
                    : "bg-slate-900 text-cyan-300 border-slate-700"
                }`}
                title="Ganti mode PTT: Tekan-Tahan vs Tekan-Sekali (Lock)"
              >
                {pttMode === "toggle" ? "🔒 KLIK SEKALI (TOGGLE)" : "🖐️ TEKAN TAHAN (HOLD)"}
              </button>
            </div>
            <button
              type="button"
              onClick={() => setShowPttSettingsModal(true)}
              className="hover:text-cyan-300 flex items-center gap-1 font-bold bg-slate-900/60 px-2 py-0.5 rounded-lg border border-slate-800"
            >
              <span>⚙️ {pttKey === "AUTO" ? "Volume / Spasi" : pttKey}</span>
            </button>
          </div>

          {/* Massive Thumb PTT Button */}
          <div className="my-1 flex-1 flex flex-col justify-center items-center">
            <button
              onClick={pttMode === "toggle" ? toggleTx : undefined}
              onMouseDown={pttMode === "hold" ? startTx : undefined}
              onMouseUp={pttMode === "hold" ? stopTx : undefined}
              onTouchStart={pttMode === "hold" ? (e) => { e.preventDefault(); startTx(); } : undefined}
              onTouchEnd={pttMode === "hold" ? (e) => { e.preventDefault(); stopTx(); } : undefined}
              className={`w-full max-w-[320px] h-36 rounded-3xl font-heading font-black text-xl flex flex-col items-center justify-center gap-2 shadow-2xl transition-all cursor-pointer active:scale-95 ${
                isTransmitting
                  ? "bg-gradient-to-b from-rose-500 to-red-700 text-white shadow-[0_0_30px_rgba(244,63,94,0.6)] border-4 border-rose-300 ring-8 ring-rose-900/40"
                  : isCableRxActive
                  ? "bg-gradient-to-b from-amber-600 to-amber-900 text-white border-2 border-amber-400 shadow-[0_0_20px_rgba(251,191,36,0.3)]"
                  : isVoxEnabled
                  ? "bg-gradient-to-b from-emerald-950 to-[#0b121e] text-emerald-400 border-2 border-emerald-500/80 shadow-[0_0_20px_rgba(52,211,153,0.2)]"
                  : activeProfile.isMonitorOnly
                  ? "bg-slate-900 border-2 border-amber-500/60 hover:border-amber-400 text-amber-300 shadow-[0_0_25px_rgba(245,158,11,0.15)]"
                  : "bg-gradient-to-b from-[#111827] to-[#0b121e] text-cyan-400 border-2 border-cyan-900/60 hover:border-cyan-400 shadow-[0_0_20px_rgba(6,182,212,0.15)]"
              }`}
            >
              {activeProfile.isMonitorOnly ? (
                <>
                  <div className="w-11 h-11 rounded-full bg-amber-500/20 border border-amber-500 flex items-center justify-center text-amber-400 text-xl font-bold">
                    🔒
                  </div>
                  <span className="text-amber-300 text-base">PTT TERKUNCI (MONITOR)</span>
                  <span className="text-[11px] font-mono text-slate-400 text-center px-4">
                    Klik untuk Buka Kunci dengan PIN atau Callsign
                  </span>
                </>
              ) : (
                <>
                  <Mic className={`w-9 h-9 ${isTransmitting ? "animate-bounce text-white" : isCableRxActive ? "text-amber-300" : isVoxEnabled ? "text-emerald-400 glow-emerald" : "text-cyan-400 glow-cyan"}`} />
                  <span>
                    {isTransmitting 
                      ? pttMode === "toggle" ? "🔴 ON AIR (TAP STOP)" : "TRANSMITTING..." 
                      : isCableRxActive 
                      ? "CABLE RF RX (INGEST AUDIO)" 
                      : isVoxEnabled 
                      ? "VOX ACTIVE (BERBICARA UNTUK TX)" 
                      : pttMode === "toggle" ? "🎙️ TAP TO TALK (KLIK ON/OFF)" : "PRESS & HOLD PTT"}
                  </span>
                  <span className="text-[11px] font-mono text-slate-400">
                    {isTransmitting 
                      ? `TX as ${activeProfile.callsign}` 
                      : isCableGatewayActive 
                      ? `Kabel: ${currentMicDb} dB (Squelch: ${cableNoiseGate} dB)` 
                      : isVoxEnabled 
                      ? `VOX Level: ${currentMicDb} dB` 
                      : `Tombol Hape: Volume Down / [ ${pttKey === "AUTO" ? "VOL/SPASI" : pttKey} ]`}
                  </span>
                </>
              )}
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

      {/* ======================================================== */}
      {/* ANTI-ABUSE PTT AUTHENTICATION & PROFILE CREATION MODAL   */}
      {/* ======================================================== */}
      {showAuthModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0b121e] border-2 border-amber-500/80 rounded-3xl max-w-md w-full p-5 sm:p-6 space-y-4 shadow-2xl">
            
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-amber-500/20 border border-amber-500 flex items-center justify-center text-amber-400 text-lg">
                  🔒
                </div>
                <div>
                  <h3 className="font-heading font-black text-sm text-white">Akses PTT Terkunci (Anti-Abuse)</h3>
                  <p className="text-[11px] font-mono text-slate-400">Gunakan Callsign pribadi atau buka dengan PIN</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setShowAuthModal(false); setAuthPinError(""); }}
                className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Mode Tabs */}
            <div className="grid grid-cols-2 p-1 bg-slate-900 border border-slate-800 rounded-xl text-xs font-bold font-mono">
              <button
                type="button"
                onClick={() => setAuthModeTab("custom")}
                className={`py-2 rounded-lg transition-all ${
                  authModeTab === "custom"
                    ? "bg-cyan-500 text-slate-950 font-black shadow"
                    : "text-slate-400 hover:text-white"
                }`}
              >
                📝 Callsign Sendiri
              </button>
              <button
                type="button"
                onClick={() => setAuthModeTab("pin")}
                className={`py-2 rounded-lg transition-all ${
                  authModeTab === "pin"
                    ? "bg-amber-400 text-slate-950 font-black shadow"
                    : "text-slate-400 hover:text-white"
                }`}
              >
                🔑 PIN Operator (Test)
              </button>
            </div>

            {/* Tab 1: Buat Profil Sendiri */}
            {authModeTab === "custom" && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  if (!authCallsign.trim() || !authName.trim()) return;

                  let badgeColor = "bg-emerald-500 text-slate-950";
                  let networkTag = "ORARI / BrandMeister";
                  let allowedTalkgroups = [510, 5101, 5102, 5103, 9990, 91];

                  if (authCategory === "RAPI") {
                    badgeColor = "bg-blue-500 text-white";
                    networkTag = "RAPI KRAP Gateway";
                    allowedTalkgroups = [5109, 9990];
                  } else if (authCategory === "CB") {
                    badgeColor = "bg-amber-500 text-slate-950";
                    networkTag = "11 Meter Citizens Band";
                    allowedTalkgroups = [5108, 9990];
                  } else if (authCategory === "Komunitas") {
                    badgeColor = "bg-purple-500 text-white";
                    networkTag = "Posko Relawan & SAR";
                    allowedTalkgroups = [5100, 9990];
                  }

                  const chosenTg = isAuthCustomTg ? (Number(authCustomTgInput) || 510) : Number(authTargetTg);
                  const finalAllowedTgs = Array.from(new Set([...allowedTalkgroups, chosenTg, 9990]));

                  const newProf: UserProfile = {
                    id: `prof_${Date.now()}`,
                    category: authCategory,
                    callsign: authCallsign.toUpperCase().trim(),
                    displayName: authName.trim(),
                    dmrId: Number(authDmrId) || (5100000 + Math.floor(Math.random() * 9000)),
                    allowedTalkgroups: finalAllowedTgs,
                    badgeColor,
                    networkTag,
                    isMonitorOnly: false,
                    defaultTg: chosenTg,
                  };

                  if (typeof window !== "undefined") {
                    const saved = localStorage.getItem("ninjaradio_user_profiles");
                    const parsed = saved ? JSON.parse(saved) : [];
                    const updated = [...parsed.filter((p: any) => p.callsign !== "N1NJA" && p.id !== "prof_default"), newProf];
                    localStorage.setItem("ninjaradio_user_profiles", JSON.stringify(updated));
                    localStorage.setItem("ninjaradio_active_profile", JSON.stringify(newProf));
                    localStorage.setItem("ninjaradio_locked_tg", String(chosenTg));
                  }
                  setActiveProfile(newProf);
                  tuneToTalkgroup(resolveTalkgroup(chosenTg, newProf.category));
                  setShowAuthModal(false);
                }}
                className="space-y-3 text-xs"
              >
                <div>
                  <label className="block text-slate-300 mb-1 font-semibold">Kategori Organisasi:</label>
                  <select
                    value={authCategory}
                    onChange={(e) => {
                      const cat = e.target.value as any;
                      setAuthCategory(cat);
                      setIsAuthCustomTg(false);
                      if (cat === "Amatir") {
                        setAuthCallsign("YB");
                        setAuthTargetTg(510);
                      } else if (cat === "RAPI") {
                        setAuthCallsign("JZ");
                        setAuthTargetTg(5109);
                      } else if (cat === "CB") {
                        setAuthCallsign("91-DX-");
                        setAuthTargetTg(5108);
                      } else {
                        setAuthCallsign("RESCUE-");
                        setAuthTargetTg(5100);
                      }
                    }}
                    className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white font-bold focus:border-cyan-400 outline-none"
                  >
                    <option value="Amatir">🟢 Radio Amatir (ORARI / IARU)</option>
                    <option value="RAPI">🔵 RAPI (10-28 / KRAP)</option>
                    <option value="CB">🟡 CB 11-Meter Citizens Band</option>
                    <option value="Komunitas">🔴 Relawan SAR / Komunitas</option>
                  </select>
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-semibold">Callsign Resmi Anda:</label>
                  <input
                    type="text"
                    required
                    placeholder="Contoh: YB1ABC / JZ10XYZ"
                    value={authCallsign}
                    onChange={(e) => setAuthCallsign(e.target.value.toUpperCase())}
                    className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white font-mono uppercase font-bold focus:border-cyan-400 outline-none"
                  />
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-semibold">Nama Operator:</label>
                  <input
                    type="text"
                    required
                    placeholder="Nama Lengkap Operator"
                    value={authName}
                    onChange={(e) => setAuthName(e.target.value)}
                    className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white focus:border-cyan-400 outline-none"
                  />
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-semibold">DMR ID / Radio ID:</label>
                  <input
                    type="number"
                    value={authDmrId}
                    onChange={(e) => setAuthDmrId(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white font-mono focus:border-cyan-400 outline-none"
                  />
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-semibold">Target TalkGroup (TG Pilihan / Terkunci):</label>
                  <select
                    value={isAuthCustomTg ? "custom" : String(authTargetTg)}
                    onChange={(e) => {
                      if (e.target.value === "custom") {
                        setIsAuthCustomTg(true);
                      } else {
                        setIsAuthCustomTg(false);
                        setAuthTargetTg(Number(e.target.value));
                      }
                    }}
                    className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white font-bold focus:border-cyan-400 outline-none"
                  >
                    <option value="510">TG 510 - DMR Indonesia Nasional</option>
                    <option value="5101">TG 5101 - Jabar Regional (YSF)</option>
                    <option value="5102">TG 5102 - Jateng Regional</option>
                    <option value="5103">TG 5103 - Jatim Regional</option>
                    <option value="5109">TG 5109 - RAPI Nusantara Gateway</option>
                    <option value="5108">TG 5108 - 11M CB Digital Gateway</option>
                    <option value="5100">TG 5100 - Emergency SAR 911</option>
                    <option value="9990">TG 9990 - Audio Echo Parrot Test</option>
                    <option value="91">TG 91 - BrandMeister WorldWide</option>
                    <option value="custom">✏️ Masukkan TG Manual / Lainnya...</option>
                  </select>
                  {isAuthCustomTg && (
                    <input
                      type="number"
                      required
                      placeholder="Masukkan Nomor TG Tujuan (misal: 5104)"
                      value={authCustomTgInput}
                      onChange={(e) => setAuthCustomTgInput(e.target.value)}
                      className="mt-1.5 w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-xl text-white font-mono focus:border-cyan-400 outline-none"
                    />
                  )}
                </div>

                <div className="pt-2 flex gap-2">
                  <button
                    type="button"
                    onClick={() => setShowAuthModal(false)}
                    className="flex-1 py-2.5 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                  >
                    Batal
                  </button>
                  <button
                    type="submit"
                    className="flex-1 py-2.5 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-black rounded-xl shadow-lg shadow-cyan-500/20"
                  >
                    Simpan & Buka PTT
                  </button>
                </div>
              </form>
            )}

            {/* Tab 2: Masukkan PIN Operator */}
            {authModeTab === "pin" && (
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  if (!authPinInput.trim()) return;
                  setIsVerifyingAuthPin(true);
                  setAuthPinError("");
                  try {
                    const res = await fetch("/api/auth/verify-pin", {
                      method: "POST",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify({ pin: authPinInput.trim() }),
                    });
                    const data = await res.json();
                    if (data && data.valid === true) {
                      const testOp: UserProfile = {
                        id: "prof_test_op",
                        category: "Amatir",
                        callsign: "TEST-OP",
                        displayName: "Operator Test (PIN Verified)",
                        dmrId: 5109999,
                        allowedTalkgroups: [510, 5101, 5102, 5103, 5109, 5108, 5100, 9990, 91],
                        badgeColor: "bg-amber-400 text-slate-950 font-black",
                        networkTag: "Verified Test Operator",
                        isMonitorOnly: false
                      };
                      if (typeof window !== "undefined") {
                        localStorage.setItem("ninjaradio_active_profile", JSON.stringify(testOp));
                      }
                      setActiveProfile(testOp);
                      setShowAuthModal(false);
                      setAuthPinInput("");
                    } else {
                      setAuthPinError("PIN Salah! Transmisi ditolak.");
                    }
                  } catch {
                    setAuthPinError("Gagal memverifikasi PIN ke server.");
                  } finally {
                    setIsVerifyingAuthPin(false);
                  }
                }}
                className="space-y-3"
              >
                <div>
                  <label className="block text-slate-300 text-xs mb-1 font-semibold">
                    Masukkan PIN Operator Server:
                  </label>
                  <input
                    type="password"
                    required
                    autoFocus
                    maxLength={12}
                    placeholder="PIN 4 Digit"
                    value={authPinInput}
                    onChange={(e) => { setAuthPinInput(e.target.value); setAuthPinError(""); }}
                    className="w-full px-3 py-2.5 bg-slate-900 border border-slate-700 focus:border-amber-400 rounded-xl text-center text-lg tracking-widest font-mono text-white font-bold"
                  />
                  {authPinError && (
                    <p className="text-red-400 text-[11px] font-bold mt-1 text-center">{authPinError}</p>
                  )}
                </div>

                <div className="flex gap-2 pt-1">
                  <button
                    type="button"
                    onClick={() => setShowAuthModal(false)}
                    className="flex-1 py-2.5 bg-slate-800 text-slate-300 rounded-xl text-xs font-bold hover:bg-slate-700"
                  >
                    Batal
                  </button>
                  <button
                    type="submit"
                    disabled={isVerifyingAuthPin}
                    className="flex-1 py-2.5 bg-amber-400 hover:bg-amber-300 text-slate-950 rounded-xl text-xs font-black shadow-lg shadow-amber-500/20"
                  >
                    {isVerifyingAuthPin ? "Memverifikasi..." : "Buka Kunci PTT"}
                  </button>
                </div>
              </form>
            )}

          </div>
        </div>
      )}

      {/* MODAL: PTT PHYSICAL BUTTON & TRIGGER MODE SETTINGS */}
      {showPttSettingsModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0b121e] border-2 border-cyan-500/70 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-2">
                <SlidersVertical className="w-5 h-5 text-cyan-400" />
                <h3 className="font-bold text-sm text-white">Pengaturan Tombol PTT Hape & PC</h3>
              </div>
              <button
                type="button"
                onClick={() => setShowPttSettingsModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3 text-xs">
              <div>
                <label className="block text-slate-300 mb-1 font-semibold">1. Cara Kerja Transmisi (PTT Mode):</label>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => savePttMode("hold")}
                    className={`p-2.5 rounded-xl border text-center transition-all ${
                      pttMode === "hold"
                        ? "bg-cyan-500 text-slate-950 font-black border-cyan-400 shadow"
                        : "bg-slate-900 text-slate-300 border-slate-800 hover:border-slate-700"
                    }`}
                  >
                    <div className="font-bold">🖐️ Tekan-Tahan</div>
                    <div className="text-[10px] opacity-80">Hold to Talk</div>
                  </button>
                  <button
                    type="button"
                    onClick={() => savePttMode("toggle")}
                    className={`p-2.5 rounded-xl border text-center transition-all ${
                      pttMode === "toggle"
                        ? "bg-amber-400 text-slate-950 font-black border-amber-300 shadow"
                        : "bg-slate-900 text-slate-300 border-slate-800 hover:border-slate-700"
                    }`}
                  >
                    <div className="font-bold">🔒 Tekan-Sekali</div>
                    <div className="text-[10px] opacity-80">Click/Toggle PTT</div>
                  </button>
                </div>
                <p className="text-[10px] text-slate-400 mt-1">
                  *Mode Tekan-Sekali sangat cocok untuk hape agar tidak perlu menahan tombol volume terus-menerus.
                </p>
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-semibold">2. Pemilihan Tombol Fisik PTT:</label>
                <select
                  value={pttKey}
                  onChange={(e) => saveCustomKey(e.target.value)}
                  className="w-full px-3 py-2.5 bg-slate-900 border border-slate-700 rounded-xl text-white font-mono font-bold focus:border-cyan-400 outline-none"
                >
                  <option value="AUTO">📱 Otomatis (Volume Hape / Bluetooth / Spasi PC)</option>
                  <option value="VolumeDown">🔉 Tombol Volume Down (Hape Android)</option>
                  <option value="VolumeUp">🔊 Tombol Volume Up (Hape Android)</option>
                  <option value="HeadsetHook">🎧 Tombol Headset / Mic Bluetooth PTT</option>
                  <option value="Space">⌨️ Tombol Spasi (PC / Laptop)</option>
                </select>
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-semibold">3. Tampilan Layar Radio (Skin):</label>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => saveDeviceSkin("standard")}
                    className={`p-2.5 rounded-xl border text-center transition-all ${
                      deviceSkin === "standard"
                        ? "bg-cyan-500 text-slate-950 font-black border-cyan-400 shadow"
                        : "bg-slate-900 text-slate-300 border-slate-800 hover:border-slate-700"
                    }`}
                  >
                    <div className="font-bold">📱 Layar 2.4" Cyberdeck</div>
                    <div className="text-[10px] opacity-80">Smartphone / Touchscreen</div>
                  </button>
                  <button
                    type="button"
                    onClick={() => saveDeviceSkin("poc18")}
                    className={`p-2.5 rounded-xl border text-center transition-all ${
                      deviceSkin === "poc18"
                        ? "bg-amber-400 text-slate-950 font-black border-amber-300 shadow"
                        : "bg-slate-900 text-slate-300 border-slate-800 hover:border-slate-700"
                    }`}
                  >
                    <div className="font-bold">📻 Layar 1.8" Rugged</div>
                    <div className="text-[10px] opacity-80">Zello PoC / Layar Mini</div>
                  </button>
                </div>
              </div>

              {/* Live Hardware Key Tester */}
              <div className="bg-[#05070c] border border-cyan-900/50 rounded-xl p-3 text-center space-y-1">
                <div className="text-[10px] text-slate-400 font-semibold">Live Key Tester (Coba tekan tombol fisik di hape Anda):</div>
                <div className="text-xs font-mono font-bold text-amber-300 truncate">
                  {lastKeyPressed || "Menunggu tombol ditekan..."}
                </div>
              </div>

              <div className="pt-1">
                <button
                  type="button"
                  onClick={() => setShowPttSettingsModal(false)}
                  className="w-full py-2.5 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-black rounded-xl shadow transition-colors"
                >
                  Simpan Pengaturan
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ======================================================== */}
      {/* FREQUENCY TUNER (VFO) & BER CALIBRATION MODAL            */}
      {/* ======================================================== */}
      {showFreqModal && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center p-3 z-50 animate-in fade-in duration-150">
          <div className="bg-[#0b121e] border-2 border-cyan-500/80 rounded-2xl p-4 max-w-sm w-full space-y-4 shadow-2xl relative font-mono">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-cyan-900/60 pb-2">
              <div className="flex items-center gap-2">
                <Radio className="w-5 h-5 text-cyan-400" />
                <h3 className="font-heading font-black text-sm text-white">VFO / FREQUENCY TUNER</h3>
              </div>
              <button
                type="button"
                onClick={() => setShowFreqModal(false)}
                className="w-7 h-7 rounded-lg bg-slate-900 border border-slate-700 text-slate-400 hover:text-white flex items-center justify-center font-bold"
              >
                ✕
              </button>
            </div>

            {/* LCD Frequency Readout */}
            <div className="bg-[#05070c] border border-cyan-900/80 rounded-xl p-3 text-center shadow-inner">
              <div className="text-[10px] text-cyan-400 font-bold uppercase tracking-wider mb-1">
                {isSimplexLocked ? "SIMPLEX TRANSCEIVER FREQUENCY" : "SPLIT REPEATER FREQUENCY"}
              </div>
              <div className="text-3xl font-black font-mono text-cyan-300 tracking-wider">
                {tempTxFreq} <span className="text-sm text-cyan-500">MHz</span>
              </div>
              {!isSimplexLocked && (
                <div className="text-xs text-amber-400 font-bold mt-1">
                  RX: {tempRxFreq} MHz (Shift: {(parseFloat(tempTxFreq) - parseFloat(tempRxFreq)).toFixed(4)} MHz)
                </div>
              )}
            </div>

            {/* Quick Step Buttons */}
            <div>
              <div className="text-[11px] text-slate-400 font-semibold mb-1">Step Tuning (kHz):</div>
              <div className="grid grid-cols-4 gap-1.5 text-xs font-bold">
                <button
                  type="button"
                  onClick={() => adjustFreqStep(-0.0250)}
                  className="py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-rose-400 active:scale-95 transition-transform"
                >
                  -25k
                </button>
                <button
                  type="button"
                  onClick={() => adjustFreqStep(-0.0125)}
                  className="py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-rose-300 active:scale-95 transition-transform"
                >
                  -12.5k
                </button>
                <button
                  type="button"
                  onClick={() => adjustFreqStep(+0.0125)}
                  className="py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-emerald-300 active:scale-95 transition-transform"
                >
                  +12.5k
                </button>
                <button
                  type="button"
                  onClick={() => adjustFreqStep(+0.0250)}
                  className="py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-emerald-400 active:scale-95 transition-transform"
                >
                  +25k
                </button>
              </div>
            </div>

            {/* Direct Input */}
            <div className="space-y-2 text-xs">
              <div>
                <label className="block text-slate-400 mb-1 font-semibold">TX Frequency (MHz):</label>
                <input
                  type="text"
                  value={tempTxFreq}
                  onChange={(e) => {
                    setTempTxFreq(e.target.value);
                    if (isSimplexLocked) setTempRxFreq(e.target.value);
                  }}
                  className="w-full px-3 py-2 bg-[#05070c] border border-cyan-900 rounded-lg text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
              </div>

              {!isSimplexLocked && (
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">RX Frequency (MHz):</label>
                  <input
                    type="text"
                    value={tempRxFreq}
                    onChange={(e) => setTempRxFreq(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05070c] border border-cyan-900 rounded-lg text-amber-300 font-mono font-bold focus:border-cyan-400 outline-none"
                  />
                </div>
              )}

              <div className="flex items-center justify-between pt-1">
                <label className="text-[11px] text-slate-300 font-semibold">Mode Simplex (TX = RX):</label>
                <button
                  type="button"
                  onClick={() => {
                    const next = !isSimplexLocked;
                    setIsSimplexLocked(next);
                    if (next) setTempRxFreq(tempTxFreq);
                  }}
                  className={`px-2.5 py-1 rounded-lg text-xs font-bold transition-all ${
                    isSimplexLocked
                      ? "bg-emerald-500 text-slate-950"
                      : "bg-slate-800 text-slate-400 border border-slate-700"
                  }`}
                >
                  {isSimplexLocked ? "SIMPLEX ON" : "SPLIT / DUPLEX"}
                </button>
              </div>

              <div className="grid grid-cols-2 gap-2 pt-1">
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Color Code (CC):</label>
                  <select
                    value={radioColorCode}
                    onChange={(e) => setRadioColorCode(Number(e.target.value))}
                    className="w-full px-2 py-1.5 bg-[#05070c] border border-cyan-900 rounded-lg text-cyan-300 font-mono font-bold outline-none"
                  >
                    {Array.from({ length: 15 }, (_, i) => i + 1).map((cc) => (
                      <option key={cc} value={cc}>
                        Color Code {cc}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1 font-semibold">Power (Watt):</label>
                  <select
                    value={radioTxPwr}
                    onChange={(e) => setRadioTxPwr(Number(e.target.value))}
                    className="w-full px-2 py-1.5 bg-[#05070c] border border-cyan-900 rounded-lg text-cyan-300 font-mono font-bold outline-none"
                  >
                    <option value={1}>1 Watt (QRP/Hotspot)</option>
                    <option value={5}>5 Watt (HT)</option>
                    <option value={10}>10 Watt</option>
                    <option value={25}>25 Watt (Mobile)</option>
                    <option value={50}>50 Watt (Base)</option>
                  </select>
                </div>
              </div>
            </div>

            {/* BER & Offset Education Banner */}
            <div className="bg-[#05070c] border border-amber-900/60 rounded-xl p-2.5 text-[10px] text-slate-300 space-y-1">
              <div className="text-amber-400 font-bold flex items-center gap-1">
                <span>ℹ️ INFO BER (Bit Error Rate):</span>
              </div>
              <p className="text-slate-400 leading-relaxed">
                BER adalah indikator kualitas sinyal (ideal: <strong>0.0% – 0.3%</strong>). Jika Anda menggunakan Hotspot MMDVM/Pi-Star dan suara masih kresek, atur <strong>RXOffset / TXOffset</strong> di menu Modem Pi-Star agar BER turun ke 0%.
              </p>
            </div>

            {/* Action Buttons */}
            <div className="pt-2 flex gap-2">
              <button
                type="button"
                onClick={() => setShowFreqModal(false)}
                className="flex-1 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold rounded-xl transition-colors"
              >
                Batal
              </button>
              <button
                type="button"
                disabled={isTuningSaving}
                onClick={handleSaveFrequency}
                className="flex-1 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-black rounded-xl shadow transition-colors disabled:opacity-50"
              >
                {isTuningSaving ? "Menyimpan..." : "Terapkan Frekuensi"}
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
