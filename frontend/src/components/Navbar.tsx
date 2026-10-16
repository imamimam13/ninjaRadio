"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { 
  Radio, 
  Activity, 
  Network, 
  BookOpen, 
  Users, 
  Settings, 
  Volume2, 
  Cpu, 
  Terminal, 
  Lock, 
  Unlock, 
  KeyRound, 
  AlertCircle, 
  X 
} from "lucide-react";
import { getIsAdminUnlocked, setAdminUnlocked, verifyAdminPin, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";
import { beepPlayer } from "@/components/BeepPlayer";

export const Navbar = ({ isConnected }: { isConnected: boolean }) => {
  const pathname = usePathname();

  // Admin Auth State
  const [isAdminUnlocked, setIsAdminUnlocked] = useState(false);
  const [showPinModal, setShowPinModal] = useState(false);
  const [pinInput, setPinInput] = useState("");
  const [pinError, setPinError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setIsAdminUnlocked(getIsAdminUnlocked());

    const handleAuthChange = () => {
      setIsAdminUnlocked(getIsAdminUnlocked());
    };

    window.addEventListener(ADMIN_AUTH_EVENT, handleAuthChange);
    window.addEventListener("storage", handleAuthChange);

    return () => {
      window.removeEventListener(ADMIN_AUTH_EVENT, handleAuthChange);
      window.removeEventListener("storage", handleAuthChange);
    };
  }, []);

  const handleToggleAdmin = () => {
    if (isAdminUnlocked) {
      setAdminUnlocked(false);
      setIsAdminUnlocked(false);
      beepPlayer?.playRogerBeep();
    } else {
      setPinInput("");
      setPinError(false);
      setShowPinModal(true);
    }
  };

  const handleVerifyPin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pinInput.trim()) return;

    setSubmitting(true);
    setPinError(false);

    const valid = await verifyAdminPin(pinInput);
    setSubmitting(false);

    if (valid) {
      setIsAdminUnlocked(true);
      setShowPinModal(false);
      beepPlayer?.playTalkGrant();
    } else {
      setPinError(true);
      beepPlayer?.playRogerBeep();
    }
  };

  // Public menu items
  const baseNavItems = [
    { href: "/", label: "NOC", icon: Activity, tag: "LIVE" },
    { href: "/radio", label: "RADIO POCKET", icon: Radio, highlight: true, tag: "PWA" },
    { href: "/matrix", label: "TG MATRIX", icon: Network },
    { href: "/logbook", label: "QSO LOG", icon: BookOpen },
    { href: "/callbook", label: "CALLBOOK", icon: Users },
  ];

  // Protected server daemon tabs - Accessible via Admin dropdown when unlocked
  const protectedNavItems = [
    { href: "/serverdmr", label: "DMR MASTER", icon: Cpu, tag: "PORT 62031", desc: "Hotspot & Peering", isProtected: true },
    { href: "/dvswitch", label: "DVSWITCH", icon: Terminal, tag: "PORT 32345", desc: "Mobile USRP Audio", isProtected: true },
    { href: "/mumla", label: "MUMLA", icon: Volume2, tag: "VOIP POOL", desc: "Multi-Bot Gateway", isProtected: true },
    { href: "/settings", label: "CONFIG", icon: Settings, tag: "DAEMONS", desc: "Supervisor & INI", isProtected: true },
  ];

  const [adminDropdownOpen, setAdminDropdownOpen] = useState(false);
  const dropdownRef = React.useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setAdminDropdownOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const activeProtectedItem = protectedNavItems.find((item) => pathname === item.href);
  const isAdminActive = !!activeProtectedItem;

  const mobileNavItems = isAdminUnlocked
    ? [
        { href: "/", label: "NOC", icon: Activity },
        { href: "/radio", label: "RADIO", icon: Radio },
        { href: "/matrix", label: "MATRIX", icon: Network },
        { href: "/serverdmr", label: "DMR", icon: Cpu },
        { href: "/dvswitch", label: "DVS", icon: Terminal },
        { href: "/mumla", label: "MUMLA", icon: Volume2 },
        { href: "/settings", label: "CONFIG", icon: Settings },
      ]
    : [
        { href: "/", label: "NOC", icon: Activity },
        { href: "/radio", label: "RADIO", icon: Radio },
        { href: "/matrix", label: "MATRIX", icon: Network },
        { href: "/logbook", label: "LOG", icon: BookOpen },
        { href: "/callbook", label: "CALLBOOK", icon: Users },
      ];

  return (
    <>
      {/* Top Tactical Cyberdeck Header */}
      <header className="sticky top-0 z-40 bg-[#070b12]/95 backdrop-blur-md border-b border-cyan-900/40 px-3 sm:px-6 py-2 shadow-xl">
        <div className="w-full max-w-[1600px] mx-auto flex items-center justify-between gap-2">
          
          {/* Logo & Tactical Brand */}
          <div className="flex items-center gap-3 shrink-0">
            <Link href="/" className="group flex items-center gap-2.5 focus:outline-none">
              <div className="relative w-9 h-9 rounded-xl bg-gradient-to-br from-cyan-400 via-cyan-600 to-emerald-500 p-[1.5px] shadow-[0_0_15px_rgba(6,182,212,0.35)] transition-transform group-hover:scale-105">
                <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center relative overflow-hidden">
                  <div className="absolute inset-0 bg-cyan-500/10 animate-pulse" />
                  <Radio className="w-4 h-4 text-cyan-400 relative z-10" />
                </div>
              </div>
              <div>
                <div className="flex items-center gap-1.5">
                  <span className="font-heading font-black text-lg tracking-wider text-white">
                    NINJA<span className="text-cyan-400 glow-cyan">RADIO</span>
                  </span>
                  <span className="text-[9px] font-mono font-bold tracking-widest px-1 py-0.2 rounded bg-cyan-950/80 text-cyan-300 border border-cyan-800/80 uppercase">
                    TACTICAL
                  </span>
                </div>
                <div className="items-center gap-2 text-[10px] font-mono text-slate-400 hidden 2xl:flex">
                  <span className="text-emerald-400 flex items-center gap-1">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping inline-block" />
                    DV CORE
                  </span>
                  <span className="text-slate-600">•</span>
                  <span>DMR / DVSWITCH / MUMLA / YSF / P25 / ASL</span>
                </div>
              </div>
            </Link>
          </div>

          {/* Desktop Tactical Navigation Links */}
          <nav className="hidden lg:flex items-center gap-1 bg-[#0b121e]/90 p-1 rounded-xl border border-cyan-900/30">
            {baseNavItems.map((item) => {
              const Icon = item.icon;
              const isActive = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`relative flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-mono font-bold tracking-wider transition-all whitespace-nowrap ${
                    isActive
                      ? item.highlight
                        ? "bg-gradient-to-r from-cyan-500 to-emerald-400 text-slate-950 shadow-[0_0_15px_rgba(6,182,212,0.4)]"
                        : "bg-cyan-950/70 text-cyan-300 border border-cyan-700/60 shadow-[0_0_10px_rgba(6,182,212,0.2)]"
                      : "text-slate-400 hover:text-cyan-300 hover:bg-slate-800/60"
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  <span>{item.label}</span>
                  {item.tag && !isActive && (
                    <span className="text-[9px] px-1 py-0.2 rounded bg-slate-800 text-cyan-400 border border-slate-700">
                      {item.tag}
                    </span>
                  )}
                </Link>
              );
            })}

            {/* Admin Dropdown Pill (Visible when unlocked) */}
            {isAdminUnlocked && (
              <div className="relative" ref={dropdownRef}>
                <button
                  type="button"
                  onClick={() => setAdminDropdownOpen(!adminDropdownOpen)}
                  className={`relative flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-mono font-bold tracking-wider transition-all whitespace-nowrap border cursor-pointer ${
                    isAdminActive
                      ? "bg-amber-950/80 text-amber-300 border-amber-600/80 shadow-[0_0_12px_rgba(245,158,11,0.3)]"
                      : "text-amber-400/90 border-amber-900/50 bg-amber-950/30 hover:bg-amber-900/40 hover:text-amber-200"
                  }`}
                >
                  {activeProtectedItem ? (
                    <>
                      <activeProtectedItem.icon className="w-3.5 h-3.5 text-amber-400" />
                      <span>{activeProtectedItem.label}</span>
                    </>
                  ) : (
                    <>
                      <Cpu className="w-3.5 h-3.5 text-amber-400" />
                      <span>SERVER DAEMONS</span>
                    </>
                  )}
                  <span className="text-[8px] px-1 py-0.2 rounded bg-amber-950 text-amber-300 border border-amber-800/70">
                    ADMIN
                  </span>
                  <span className="text-[10px] ml-0.5 text-amber-400/80">▾</span>
                </button>

                {/* Dropdown Floating Panel */}
                {adminDropdownOpen && (
                  <div className="absolute right-0 mt-2 w-64 bg-[#080d17]/98 backdrop-blur-xl border border-amber-500/40 rounded-xl p-2 shadow-[0_10px_30px_rgba(0,0,0,0.85),0_0_20px_rgba(245,158,11,0.2)] z-50 animate-in fade-in slide-in-from-top-2 duration-150">
                    <div className="px-2.5 py-1.5 mb-1.5 border-b border-amber-900/40 flex items-center justify-between">
                      <span className="text-[10px] font-mono font-bold text-amber-400/90 tracking-widest uppercase">
                        SERVER MANAGEMENT
                      </span>
                      <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-amber-950/80 text-amber-300 border border-amber-700/60">
                        ONLINE
                      </span>
                    </div>

                    <div className="space-y-1">
                      {protectedNavItems.map((item) => {
                        const Icon = item.icon;
                        const isActive = pathname === item.href;
                        return (
                          <Link
                            key={item.href}
                            href={item.href}
                            onClick={() => setAdminDropdownOpen(false)}
                            className={`flex items-center justify-between px-3 py-2 rounded-lg text-xs font-mono transition-all ${
                              isActive
                                ? "bg-amber-500/25 text-amber-200 border border-amber-500/50 shadow-sm"
                                : "text-slate-300 hover:text-white hover:bg-slate-800/70"
                            }`}
                          >
                            <div className="flex items-center gap-2.5">
                              <Icon className={`w-4 h-4 shrink-0 ${isActive ? "text-amber-400" : "text-amber-500/80"}`} />
                              <div>
                                <div className="font-bold">{item.label}</div>
                                <div className="text-[10px] text-slate-400">{item.desc}</div>
                              </div>
                            </div>
                            <span className="text-[9px] font-mono text-slate-400 px-1.5 py-0.5 rounded bg-slate-900 border border-slate-800 shrink-0">
                              {item.tag}
                            </span>
                          </Link>
                        );
                      })}
                    </div>

                    <div className="mt-2 pt-1.5 border-t border-slate-800/80">
                      <button
                        type="button"
                        onClick={() => {
                          setAdminDropdownOpen(false);
                          handleToggleAdmin();
                        }}
                        className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-lg text-[11px] font-mono text-rose-400 hover:bg-rose-950/40 hover:text-rose-300 transition-colors cursor-pointer"
                      >
                        <Lock className="w-3 h-3" />
                        <span>Kunci & Sembunyikan Admin</span>
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </nav>

          {/* Tactical Telemetry & Admin Lock Controls */}
          <div className="flex items-center gap-2 shrink-0">
            <Link
              href="/radio"
              className="flex md:hidden items-center gap-1 px-2.5 py-1 bg-gradient-to-r from-cyan-500 to-emerald-400 text-slate-950 rounded-lg text-xs font-heading font-black shadow-md shadow-cyan-500/20"
            >
              <Radio className="w-3.5 h-3.5" />
              <span>RADIO POCKET</span>
            </Link>

            {/* Admin PIN Unlock / Lock Toggle Pill */}
            <button
              onClick={handleToggleAdmin}
              title={isAdminUnlocked ? "Kunci Admin (Sembunyikan tab server sensitif)" : "Buka Kunci PIN Admin"}
              className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl border text-xs font-mono font-bold transition-all cursor-pointer ${
                isAdminUnlocked
                  ? "bg-emerald-950/40 border-emerald-500/50 text-emerald-300 shadow-[0_0_12px_rgba(16,185,129,0.25)] hover:bg-rose-950/50 hover:border-rose-500/50 hover:text-rose-300"
                  : "bg-[#0b121e] border-slate-800 text-slate-400 hover:text-cyan-300 hover:border-cyan-800/60"
              }`}
            >
              {isAdminUnlocked ? (
                <>
                  <Unlock className="w-3.5 h-3.5 text-emerald-400" />
                  <span className="hidden sm:inline">ADMIN</span>
                </>
              ) : (
                <>
                  <Lock className="w-3.5 h-3.5 text-slate-400" />
                  <span className="hidden sm:inline">ADMIN</span>
                </>
              )}
            </button>
            
            {/* Core Online Pill */}
            <div className="flex items-center gap-2 px-3 py-1.5 bg-[#0b121e] rounded-xl border border-cyan-900/40 text-xs font-mono">
              <span
                className={`w-2.5 h-2.5 rounded-full ${
                  isConnected
                    ? "bg-emerald-400 shadow-[0_0_10px_#34d399] animate-pulse"
                    : "bg-rose-500 shadow-[0_0_10px_#f43f5e]"
                }`}
              />
              <span className="text-slate-300 text-[11px] font-bold hidden xs:inline">
                {isConnected ? "CORE: ONLINE" : "CORE: OFFLINE"}
              </span>
            </div>
          </div>
        </div>
      </header>

      {/* Admin Unlock PIN Modal */}
      {showPinModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
          <div className="bg-[#0b121e] border border-cyan-500/40 rounded-2xl w-full max-w-sm p-6 shadow-[0_0_40px_rgba(6,182,212,0.25)] relative overflow-hidden">
            <button
              onClick={() => setShowPinModal(false)}
              className="absolute top-4 right-4 text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800/50"
            >
              <X className="w-4 h-4" />
            </button>

            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-cyan-950/80 border border-cyan-500/40 flex items-center justify-center text-cyan-400">
                <KeyRound className="w-5 h-5 animate-pulse" />
              </div>
              <div>
                <h3 className="font-heading font-black text-base text-white">Menu Admin Terkunci</h3>
                <p className="text-xs font-mono text-slate-400">Buka akses tab DMR MASTER, DVSWITCH, & MUMLA</p>
              </div>
            </div>

            <form onSubmit={handleVerifyPin} className="space-y-4">
              <div>
                <label className="block text-xs font-mono text-slate-300 mb-1.5">
                  PIN Keamanan Admin:
                </label>
                <input
                  type="password"
                  autoFocus
                  placeholder="Masukkan PIN (default: 1234)"
                  value={pinInput}
                  onChange={(e) => {
                    setPinInput(e.target.value);
                    setPinError(false);
                  }}
                  className="w-full px-4 py-2.5 bg-[#060a12] border border-cyan-900/60 rounded-xl font-mono text-center text-sm text-cyan-300 focus:outline-none focus:border-cyan-400 focus:ring-1 focus:ring-cyan-400 tracking-widest"
                />
              </div>

              {pinError && (
                <div className="flex items-center gap-1.5 text-xs font-mono text-rose-400 bg-rose-950/30 border border-rose-900/50 rounded-lg p-2">
                  <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                  <span>PIN Admin salah! Akses ditolak.</span>
                </div>
              )}

              <div className="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setShowPinModal(false)}
                  className="flex-1 py-2 px-3 rounded-xl border border-slate-700 hover:bg-slate-800 text-xs font-mono text-slate-300 transition-colors"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={submitting || !pinInput.trim()}
                  className="flex-1 py-2 px-3 rounded-xl bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 font-heading font-black text-xs tracking-wider transition-all shadow-md shadow-cyan-500/20 disabled:opacity-50"
                >
                  {submitting ? "Memverifikasi..." : "Buka Akses"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Bottom Floating Nav for Small Screen Devices */}
      <div className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-[#070b12]/95 backdrop-blur-lg border-t border-cyan-900/40 px-2 py-1.5">
        <nav className="flex items-center justify-around">
          {mobileNavItems.map((item) => {
            const Icon = item.icon;
            const isActive = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex flex-col items-center justify-center p-1 rounded-lg text-[10px] font-mono tracking-wider transition-colors ${
                  isActive
                    ? "text-cyan-400 font-bold"
                    : "text-slate-400 hover:text-slate-200"
                }`}
              >
                <Icon className={`w-4 h-4 mb-0.5 ${isActive ? "text-cyan-400 glow-cyan" : "text-slate-400"}`} />
                <span className="text-[9px]">{item.label}</span>
              </Link>
            );
          })}
        </nav>
      </div>
    </>
  );
};
