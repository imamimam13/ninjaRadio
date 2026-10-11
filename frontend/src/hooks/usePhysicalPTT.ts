"use client";

import { useState, useEffect, useCallback } from "react";
import { beepPlayer } from "@/components/BeepPlayer";

interface UsePhysicalPTTOptions {
  onTransmitStart?: () => void;
  onTransmitEnd?: () => void;
  enabled?: boolean;
}

export function usePhysicalPTT({
  onTransmitStart,
  onTransmitEnd,
  enabled = true,
}: UsePhysicalPTTOptions = {}) {
  const [isTransmitting, setIsTransmitting] = useState(false);
  const [lastKeyPressed, setLastKeyPressed] = useState<string>("");
  const [pttKey, setPttKey] = useState<string>("Space"); // Default Space / F4 / PoC Key

  // Load saved PTT key from localStorage
  useEffect(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("ninjaradio_ptt_key") || localStorage.getItem("ninjadmr_ptt_key");
      if (saved) setPttKey(saved);
    }
  }, []);

  const startTx = useCallback(() => {
    if (isTransmitting) return;
    setIsTransmitting(true);
    beepPlayer?.playTalkGrant();
    if (navigator.vibrate) navigator.vibrate(50);
    onTransmitStart?.();
  }, [isTransmitting, onTransmitStart]);

  const stopTx = useCallback(() => {
    if (!isTransmitting) return;
    setIsTransmitting(false);
    beepPlayer?.playRogerBeep();
    if (navigator.vibrate) navigator.vibrate(30);
    onTransmitEnd?.();
  }, [isTransmitting, onTransmitEnd]);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Record any pressed key for settings/debug
      setLastKeyPressed(`${e.code} (Key: ${e.key}, Code: ${e.keyCode})`);

      // Match PTT conditions:
      // 1. Matched saved key
      // 2. PoC Android Radio typical PTT codes (138, 226, 228, F4, F8)
      // 3. Prevent repeated events when holding key down
      const isMatch =
        e.code === pttKey ||
        e.key === pttKey ||
        e.keyCode === 138 ||
        e.keyCode === 226;

      if (isMatch && !e.repeat) {
        // Prevent default only if not typing in an input
        if (
          !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)
        ) {
          e.preventDefault();
          startTx();
        }
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      const isMatch =
        e.code === pttKey ||
        e.key === pttKey ||
        e.keyCode === 138 ||
        e.keyCode === 226;

      if (isMatch) {
        if (
          !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)
        ) {
          e.preventDefault();
          stopTx();
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    window.addEventListener("keyup", handleKeyUp);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("keyup", handleKeyUp);
    };
  }, [enabled, pttKey, startTx, stopTx]);

  const saveCustomKey = (key: string) => {
    setPttKey(key);
    if (typeof window !== "undefined") {
      localStorage.setItem("ninjaradio_ptt_key", key);
    }
  };

  return {
    isTransmitting,
    startTx,
    stopTx,
    pttKey,
    saveCustomKey,
    lastKeyPressed,
  };
}
