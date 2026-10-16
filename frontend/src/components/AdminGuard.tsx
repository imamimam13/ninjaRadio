"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { Lock, Unlock, ShieldAlert, ArrowLeft, KeyRound, AlertCircle } from "lucide-react";
import { getIsAdminUnlocked, setAdminUnlocked, verifyAdminPin, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";
import { beepPlayer } from "@/components/BeepPlayer";

interface AdminGuardProps {
  children: React.ReactNode;
  title?: string;
  description?: string;
  tag?: string;
}

export const AdminGuard: React.FC<AdminGuardProps> = ({
  children,
  title = "Akses Terbatas Administrator",
  description = "Halaman ini memuat informasi peering internal, hotspot terdaftar, dan parameter bridge rahasia. Masukkan PIN Admin untuk melihat.",
  tag = "PROTECTED"
}) => {
  const [isUnlocked, setIsUnlocked] = useState(false);
  const [isChecking, setIsChecking] = useState(true);
  const [pin, setPin] = useState("");
  const [error, setError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    // Initial check
    setIsUnlocked(getIsAdminUnlocked());
    setIsChecking(false);

    // Sync listener
    const syncAuth = () => {
      setIsUnlocked(getIsAdminUnlocked());
    };

    window.addEventListener(ADMIN_AUTH_EVENT, syncAuth);
    window.addEventListener("storage", syncAuth);

    return () => {
      window.removeEventListener(ADMIN_AUTH_EVENT, syncAuth);
      window.removeEventListener("storage", syncAuth);
    };
  }, []);

  const handleUnlock = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pin.trim()) return;

    setSubmitting(true);
    setError(false);

    const valid = await verifyAdminPin(pin);
    setSubmitting(false);

    if (valid) {
      setIsUnlocked(true);
      beepPlayer?.playTalkGrant();
    } else {
      setError(true);
      beepPlayer?.playRogerBeep();
    }
  };

  if (isChecking) {
    return (
      <div className="min-h-[60vh] flex items-center justify-center">
        <div className="w-8 h-8 rounded-full border-2 border-cyan-400 border-t-transparent animate-spin" />
      </div>
    );
  }

  if (isUnlocked) {
    return <>{children}</>;
  }

  return (
    <div className="min-h-[75vh] flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-[#0a0f18]/90 border border-cyan-900/40 rounded-2xl p-6 sm:p-8 shadow-[0_0_50px_rgba(6,182,212,0.15)] relative overflow-hidden backdrop-blur-xl">
        {/* Glow accent */}
        <div className="absolute -top-24 -left-24 w-48 h-48 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute -bottom-24 -right-24 w-48 h-48 bg-rose-500/10 rounded-full blur-3xl pointer-events-none" />

        <div className="text-center relative z-10">
          {/* Tactical Badge & Icon */}
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-rose-950/40 border border-rose-500/30 text-rose-400 mb-4 shadow-[0_0_20px_rgba(244,63,94,0.2)]">
            <Lock className="w-8 h-8 animate-pulse" />
          </div>

          <div className="flex items-center justify-center gap-2 mb-2">
            <span className="text-[10px] font-mono font-bold tracking-widest px-2 py-0.5 rounded bg-rose-950/80 text-rose-300 border border-rose-800/80 uppercase">
              {tag}
            </span>
            <span className="text-[10px] font-mono text-slate-500">•</span>
            <span className="text-[10px] font-mono text-slate-400">ADMIN PRIVILEGE REQUIRED</span>
          </div>

          <h2 className="text-xl sm:text-2xl font-heading font-black text-white tracking-wider mb-2">
            {title}
          </h2>

          <p className="text-xs sm:text-sm text-slate-400 leading-relaxed mb-6 font-mono">
            {description}
          </p>

          {/* Form PIN */}
          <form onSubmit={handleUnlock} className="space-y-4">
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500">
                <KeyRound className="w-4 h-4" />
              </div>
              <input
                type="password"
                autoFocus
                placeholder="Masukkan PIN Admin (default: 1234)"
                value={pin}
                onChange={(e) => {
                  setPin(e.target.value);
                  setError(false);
                }}
                className={`w-full pl-10 pr-4 py-3 bg-[#070b12] border rounded-xl font-mono text-center text-sm tracking-widest text-cyan-300 placeholder-slate-600 focus:outline-none transition-all ${
                  error
                    ? "border-rose-500/80 ring-2 ring-rose-500/30 text-rose-300"
                    : "border-cyan-900/60 focus:border-cyan-400 focus:ring-2 focus:ring-cyan-400/20"
                }`}
              />
            </div>

            {error && (
              <div className="flex items-center justify-center gap-1.5 text-xs font-mono text-rose-400 bg-rose-950/30 border border-rose-900/40 rounded-lg p-2 animate-shake">
                <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                <span>PIN Admin salah atau akses ditolak!</span>
              </div>
            )}

            <button
              type="submit"
              disabled={submitting || !pin.trim()}
              className="w-full flex items-center justify-center gap-2 py-3 px-4 bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 font-heading font-black text-sm tracking-wider rounded-xl transition-all shadow-[0_0_20px_rgba(6,182,212,0.3)] disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
            >
              <Unlock className="w-4 h-4" />
              <span>{submitting ? "MEMVERIFIKASI..." : "BUKA KUNCI ADMIN"}</span>
            </button>
          </form>

          {/* Back to Home Link */}
          <div className="mt-6 pt-5 border-t border-slate-800/80 flex items-center justify-between text-xs font-mono">
            <Link
              href="/"
              className="inline-flex items-center gap-1.5 text-slate-400 hover:text-cyan-400 transition-colors"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              <span>Kembali ke NOC Dashboard</span>
            </Link>
            <span className="text-[11px] text-slate-500">NinjaRadio Cyberdeck</span>
          </div>
        </div>
      </div>
    </div>
  );
};
