"use client";

import { useEffect, useRef } from "react";

interface UseMediaSessionOptions {
  /** Whether the RX audio pipeline has been unlocked / is running. */
  active: boolean;
  /** Primary line, e.g. the talkgroup being monitored. */
  title: string;
  /** Secondary line, e.g. the callsign currently heard / "Standby". */
  artist: string;
  muted: boolean;
  onToggleMute: () => void;
  /** Experimental: map next/previous track buttons to a PTT toggle. */
  lockscreenPttEnabled: boolean;
  onPttToggle: () => void;
}

/**
 * Publishes NinjaRadio to the OS media controls (lockscreen / notification
 * shade) via the Media Session API. Requires an HTMLMediaElement to be playing
 * (see the hidden <audio> in the radio page) for Chrome Android to show it.
 */
export function useMediaSession({
  active,
  title,
  artist,
  muted,
  onToggleMute,
  lockscreenPttEnabled,
  onPttToggle,
}: UseMediaSessionOptions) {
  // Keep latest callbacks in refs so handlers are registered only once.
  const muteRef = useRef(onToggleMute);
  const pttRef = useRef(onPttToggle);
  muteRef.current = onToggleMute;
  pttRef.current = onPttToggle;

  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    if (!active) return;

    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title,
        artist,
        album: "NinjaRadio",
        artwork: [
          { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
        ],
      });
      navigator.mediaSession.playbackState = muted ? "paused" : "playing";
    } catch {
      // MediaMetadata unsupported
    }
  }, [active, title, artist, muted]);

  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    if (!active) return;

    const set = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
      try {
        navigator.mediaSession.setActionHandler(action, handler);
      } catch {
        // action not supported on this browser
      }
    };

    set("play", () => muteRef.current());
    set("pause", () => muteRef.current());
    if (lockscreenPttEnabled) {
      set("previoustrack", () => pttRef.current());
      set("nexttrack", () => pttRef.current());
    } else {
      set("previoustrack", null);
      set("nexttrack", null);
    }

    return () => {
      set("play", null);
      set("pause", null);
      set("previoustrack", null);
      set("nexttrack", null);
    };
  }, [active, lockscreenPttEnabled]);
}
