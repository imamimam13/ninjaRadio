"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Radio, Activity, Network, BookOpen, Users, Settings, Volume2, Shield, Wifi, Cpu, Terminal } from "lucide-react";

export const Navbar = ({ isConnected }: { isConnected: boolean }) => {
  const pathname = usePathname();

  const navItems = [
    { href: "/", label: "NOC DASHBOARD", icon: Activity, tag: "LIVE" },
    { href: "/radio", label: "RADIO POCKET", icon: Radio, highlight: true, tag: "PWA" },
    { href: "/matrix", label: "TG MATRIX", icon: Network },
    { href: "/logbook", label: "QSO LOG", icon: BookOpen },
    { href: "/callbook", label: "CALLBOOK", icon: Users },
    { href: "/settings", label: "SYS CONFIG", icon: Settings },
  ];

  return (
    <>
      {/* Top Tactical Cyberdeck Header */}
      <header className="sticky top-0 z-40 bg-[#070b12]/95 backdrop-blur-md border-b border-cyan-900/40 px-3 sm:px-6 py-2.5 shadow-xl">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          
          {/* Logo & Tactical Brand */}
          <div className="flex items-center gap-3">
            <Link href="/" className="group flex items-center gap-3 focus:outline-none">
              <div className="relative w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-400 via-cyan-600 to-emerald-500 p-[1.5px] shadow-[0_0_15px_rgba(6,182,212,0.35)] transition-transform group-hover:scale-105">
                <div className="w-full h-full bg-[#070c16] rounded-[10px] flex items-center justify-center relative overflow-hidden">
                  <div className="absolute inset-0 bg-cyan-500/10 animate-pulse" />
                  <Radio className="w-5 h-5 text-cyan-400 relative z-10" />
                </div>
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-heading font-black text-xl tracking-wider text-white">
                    NINJA<span className="text-cyan-400 glow-cyan">RADIO</span>
                  </span>
                  <span className="text-[10px] font-mono font-bold tracking-widest px-1.5 py-0.5 rounded bg-cyan-950/80 text-cyan-300 border border-cyan-800/80 uppercase">
                    TACTICAL
                  </span>
                </div>
                <div className="flex items-center gap-2 text-[10px] font-mono text-slate-400 hidden sm:flex">
                  <span className="text-emerald-400 flex items-center gap-1">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping inline-block" />
                    DV CORE
                  </span>
                  <span className="text-slate-600">•</span>
                  <span>DMR / YSF / P25 / ASL / CB / MUMBLE</span>
                </div>
              </div>
            </Link>
          </div>

          {/* Desktop Tactical Navigation Links */}
          <nav className="hidden md:flex items-center gap-1 bg-[#0b121e]/90 p-1 rounded-xl border border-cyan-900/30">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`relative flex items-center gap-2 px-3.5 py-1.5 rounded-lg text-xs font-mono font-bold tracking-wider transition-all ${
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
          </nav>

          {/* Tactical Telemetry & Connection Pill */}
          <div className="flex items-center gap-2">
            <Link
              href="/radio"
              className="flex md:hidden items-center gap-1 px-2.5 py-1 bg-gradient-to-r from-cyan-500 to-emerald-400 text-slate-950 rounded-lg text-xs font-heading font-black shadow-md shadow-cyan-500/20"
            >
              <Radio className="w-3.5 h-3.5" />
              <span>RADIO POCKET</span>
            </Link>
            
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

      {/* Bottom Floating Nav for Small Screen Devices */}
      <div className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-[#070b12]/95 backdrop-blur-lg border-t border-cyan-900/40 px-2 py-1.5">
        <nav className="flex items-center justify-around">
          {navItems.map((item) => {
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
                <span className="text-[9px]">{item.label.split(" ")[0]}</span>
              </Link>
            );
          })}
        </nav>
      </div>
    </>
  );
};
