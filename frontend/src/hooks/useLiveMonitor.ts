"use client";

import { useState, useEffect, useRef } from "react";

export interface LastHeardEntry {
  id: string;
  timestamp: string;
  callsign: string;
  name: string;
  dmr_id: number;
  talkgroup: number;
  tg_name: string;
  slot: number;
  mode: string;
  category: string;
  duration: string;
  loss: string;
  ber: string;
  rssi: string;
  is_tx?: boolean;
}

export interface ActiveTransmission {
  id: string;
  callsign: string;
  name: string;
  dmr_id: number;
  talkgroup: number;
  tg_name: string;
  slot: number;
  mode: string;
  category: string;
  rssi: string;
  ber: string;
  loss?: string;
  started_at: string;
}

export type AudioRxData = {
  src_id: number;
  callsign: string;
  tg: number;
  slot: number;
  pcm: string;
};

export type AudioRxListener = (audio: AudioRxData) => void;

function getAudioListeners(): Set<AudioRxListener> {
  if (typeof window !== "undefined") {
    if (!(window as any).__ninja_audio_listeners) {
      (window as any).__ninja_audio_listeners = new Set<AudioRxListener>();
    }
    return (window as any).__ninja_audio_listeners;
  }
  return new Set();
}

export function registerAudioRxListener(listener: AudioRxListener) {
  getAudioListeners().add(listener);
  return () => {
    getAudioListeners().delete(listener);
  };
}

export function useLiveMonitor() {
  const [lastHeard, setLastHeard] = useState<LastHeardEntry[]>([]);
  const [activeTx, setActiveTx] = useState<ActiveTransmission | null>(null);
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const wsRef = useRef<WebSocket | null>(null);
  const [incomingAudio, setIncomingAudio] = useState<AudioRxData | null>(null);

  const sendJson = (data: any): boolean => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(data));
      return true;
    }
    return false;
  };

  useEffect(() => {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const host = process.env.NEXT_PUBLIC_WS_HOST || (typeof window !== "undefined" ? window.location.host : "localhost:8000");
    const appName = typeof window !== "undefined" && window.location.pathname.includes("radio") ? "Radio Pocket Web" : "NOC Dashboard";
    const wsUrl = `${protocol}//${host}/ws/live?app=${encodeURIComponent(appName)}`;

    let disposed = false;
    let pingTimer: ReturnType<typeof setInterval> | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;

    function connect() {
      if (disposed) return;
      try {
        const ws = new WebSocket(wsUrl);
        wsRef.current = ws;

        ws.onopen = () => {
          setIsConnected(true);
          // Keep the connection alive through idle/screen-off periods (server ignores unknown types)
          if (pingTimer) clearInterval(pingTimer);
          pingTimer = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) {
              try {
                ws.send(JSON.stringify({ type: "PING" }));
              } catch {}
            }
          }, 20000);
        };

        ws.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data);
            if (message.type === "INITIAL_STATE") {
              setLastHeard(message.data.last_heard || []);
              setActiveTx(message.data.current_tx || null);
            } else if (message.type === "TX_STATE_CHANGE") {
              setActiveTx(message.data);
            } else if (message.type === "LAST_HEARD_UPDATE") {
              setLastHeard((prev) => [message.data, ...prev.slice(0, 49)]);
            } else if (message.type === "AUDIO_RX") {
              const data = (message.data || message) as AudioRxData;
              getAudioListeners().forEach((listener) => {
                try {
                  listener(data);
                } catch {}
              });
              setIncomingAudio(data);
            }
          } catch {
            // parse error
          }
        };

        ws.onclose = () => {
          setIsConnected(false);
          if (pingTimer) {
            clearInterval(pingTimer);
            pingTimer = null;
          }
          if (disposed) return;
          if (retryTimer) clearTimeout(retryTimer);
          retryTimer = setTimeout(connect, 3000); // Auto reconnect
        };

        ws.onerror = () => {
          ws.close();
        };
      } catch {
        // Handle initial connection fail
      }
    }

    // Reconnect immediately when the screen turns back on or the network returns
    const reconnectNow = () => {
      if (disposed) return;
      const state = wsRef.current?.readyState;
      if (state === WebSocket.OPEN || state === WebSocket.CONNECTING) return;
      if (retryTimer) clearTimeout(retryTimer);
      connect();
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") reconnectNow();
    };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("online", reconnectNow);

    connect();

    return () => {
      disposed = true;
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("online", reconnectNow);
      if (pingTimer) clearInterval(pingTimer);
      if (retryTimer) clearTimeout(retryTimer);
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, []);


  return {
    lastHeard,
    activeTx,
    incomingAudio,
    sendJson,
    isConnected,
  };
}
