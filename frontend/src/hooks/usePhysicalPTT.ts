"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { beepPlayer } from "@/components/BeepPlayer";

interface UsePhysicalPTTOptions {
  onTransmitStart?: () => void;
  onTransmitEnd?: () => void;
  enabled?: boolean;
  /** Experimental lockscreen PTT: don't auto-release TX when the page is hidden/blurred. */
  keepTxInBackground?: boolean;
}

export function usePhysicalPTT({
  onTransmitStart,
  onTransmitEnd,
  enabled = true,
  keepTxInBackground = false,
}: UsePhysicalPTTOptions = {}) {
  const keepBgRef = useRef(keepTxInBackground);
  keepBgRef.current = keepTxInBackground;
  const [isTransmitting, setIsTransmitting] = useState(false);
  const [lastKeyPressed, setLastKeyPressed] = useState<string>("");
  // pttKey: "AUTO" (accepts Space, Volume Down/Up, PoC PTT, Headset Hook) or custom key code
  const [pttKey, setPttKey] = useState<string>("AUTO");
  // pttMode: "hold" (Momentary: tekan tahan) vs "toggle" (Latch: tekan sekali on, tekan lagi off)
  const [pttMode, setPttMode] = useState<"hold" | "toggle">("hold");

  const isTxRef = useRef(false);
  isTxRef.current = isTransmitting;

  // Load saved PTT settings from localStorage
  useEffect(() => {
    if (typeof window !== "undefined") {
      const savedKey = localStorage.getItem("ninjaradio_ptt_key") || localStorage.getItem("ninjadmr_ptt_key");
      if (savedKey) setPttKey(savedKey);
      const savedMode = localStorage.getItem("ninjaradio_ptt_mode") as "hold" | "toggle";
      if (savedMode === "hold" || savedMode === "toggle") setPttMode(savedMode);
    }
  }, []);

  const startTx = useCallback(() => {
    if (isTxRef.current) return;
    setIsTransmitting(true);
    beepPlayer?.playTalkGrant();
    if (navigator.vibrate) navigator.vibrate(50);
    onTransmitStart?.();
  }, [onTransmitStart]);

  const stopTx = useCallback(() => {
    if (!isTxRef.current) return;
    setIsTransmitting(false);
    beepPlayer?.playRogerBeep();
    if (navigator.vibrate) navigator.vibrate(30);
    onTransmitEnd?.();
  }, [onTransmitEnd]);

  const toggleTx = useCallback(() => {
    if (isTxRef.current) {
      stopTx();
    } else {
      startTx();
    }
  }, [startTx, stopTx]);

  // Helper to test if a keyboard / hardware event matches PTT key
  const isPttEvent = useCallback((e: KeyboardEvent) => {
    // If user is typing in a text field, don't trigger PTT
    if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) {
      return false;
    }

    // 1. Explicit specific key set by user
    if (pttKey !== "AUTO") {
      if (pttKey === "VolumeDown") {
        return e.code === "VolumeDown" || e.key === "AudioVolumeDown" || e.keyCode === 25;
      }
      if (pttKey === "VolumeUp") {
        return e.code === "VolumeUp" || e.key === "AudioVolumeUp" || e.keyCode === 24;
      }
      if (pttKey === "Space") {
        return e.code === "Space" || e.key === " " || e.keyCode === 32;
      }
      if (pttKey === "HeadsetHook") {
        return e.key === "HeadsetHook" || e.key === "MediaPlayPause" || e.keyCode === 79 || e.keyCode === 85;
      }
      return e.code === pttKey || e.key === pttKey;
    }

    // 2. Default "AUTO" mode: accepts smartphone Volume Down, Volume Up, Headset Hook / Media, and dedicated PoC hardware keys
    // Note: Spacebar is NOT triggered automatically to prevent accidental transmissions while scrolling/typing
    const isVolumeDown = e.code === "VolumeDown" || e.key === "AudioVolumeDown" || e.keyCode === 25;
    const isVolumeUp = e.code === "VolumeUp" || e.key === "AudioVolumeUp" || e.keyCode === 24;
    const isHeadset =
      e.key === "HeadsetHook" ||
      e.key === "MediaPlayPause" ||
      e.code === "MediaPlayPause" ||
      e.keyCode === 79 ||
      e.keyCode === 85;

    const isPocHardware =
      e.keyCode === 138 || // KEY_PTT
      e.keyCode === 226 || // KEY_MEDIA_FAST_FORWARD (MTK PTT)
      e.keyCode === 228 || // Qualcomm PTT
      e.keyCode === 261 || // F4
      e.keyCode === 262 || // F5
      e.keyCode === 263 || // F6
      e.key === "F4";

    return isVolumeDown || isVolumeUp || isHeadset || isPocHardware;
  }, [pttKey]);

  // Safety Time-Out Timer (TOT): Auto-release PTT after 60s
  useEffect(() => {
    if (!isTransmitting) return;
    const timer = setTimeout(() => {
      stopTx();
    }, 60000);
    return () => clearTimeout(timer);
  }, [isTransmitting, stopTx]);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Record any pressed key for diagnostics
      setLastKeyPressed(`${e.code || e.key} (key: ${e.key}, keyCode: ${e.keyCode})`);

      if (isPttEvent(e)) {
        e.preventDefault();
        if (e.repeat) return; // Prevent repeated triggers from OS auto-repeat

        if (pttMode === "toggle") {
          toggleTx();
        } else {
          startTx();
        }
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (isPttEvent(e)) {
        e.preventDefault();
        if (pttMode === "hold") {
          stopTx();
        }
      }
    };

    // Auto-release PTT if tab loses focus, window blurs, or page is navigated away
    const handleSafetyRelease = () => {
      if (keepBgRef.current) return;
      if (isTxRef.current) {
        stopTx();
      }
    };

    const handleVisibilityChange = () => {
      if (keepBgRef.current) return;
      if (document.hidden && isTxRef.current) {
        stopTx();
      }
    };

    window.addEventListener("keydown", handleKeyDown, { passive: false });
    window.addEventListener("keyup", handleKeyUp, { passive: false });
    window.addEventListener("blur", handleSafetyRelease);
    window.addEventListener("beforeunload", handleSafetyRelease);
    document.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("keyup", handleKeyUp);
      window.removeEventListener("blur", handleSafetyRelease);
      window.removeEventListener("beforeunload", handleSafetyRelease);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [enabled, isPttEvent, pttMode, startTx, stopTx, toggleTx]);

  const saveCustomKey = (key: string) => {
    setPttKey(key);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_ptt_key", key);
    }
  };

  const savePttMode = (mode: "hold" | "toggle") => {
    setPttMode(mode);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_ptt_mode", mode);
    }
  };

  return {
    isTransmitting,
    startTx,
    stopTx,
    toggleTx,
    pttKey,
    pttMode,
    saveCustomKey,
    savePttMode,
    lastKeyPressed,
  };
}
