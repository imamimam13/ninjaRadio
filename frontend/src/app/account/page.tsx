"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import {
  KeyRound, Copy, Check, RefreshCw, ShieldAlert, ShieldCheck,
  LogOut, Radio, Wifi, UserCheck, Send
} from "lucide-react";

interface UserAccount {
  id: number;
  callsign: string;
  dmr_id: number;
  name: string;
  city: string;
  organization: string;
  organization_id: string;
  status: string;
  rejected_reason: string;
  req_web: boolean;
  req_dmr: boolean;
  req_svxlink: boolean;
  req_mumble: boolean;
  req_dvswitch: boolean;
  req_zello: boolean;
  allow_web: boolean;
  allow_dmr: boolean;
  allow_svxlink: boolean;
  allow_mumble: boolean;
  allow_dvswitch: boolean;
  allow_zello: boolean;
  dmr_password: string;
  svxlink_password: string;
  mumble_password: string;
  zello_username: string;
  dvswitch_allowed_ip: string;
}

export default function AccountPage() {
  const { isConnected } = useLiveMonitor();
  const [token, setToken] = useState<string>("");
  const [user, setUser] = useState<UserAccount | null>(null);
  const [loading, setLoading] = useState(true);

  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  const [zelloInput, setZelloInput] = useState("");
  const [reqDmr, setReqDmr] = useState(false);
  const [reqSvx, setReqSvx] = useState(false);
  const [reqMumble, setReqMumble] = useState(false);
  const [reqDvs, setReqDvs] = useState(false);
  const [reqZello, setReqZello] = useState(false);

  const fetchProfile = async (tok: string) => {
    setLoading(true);
    try {
      const res = await fetch("/api/selfcare/me", {
        headers: { Authorization: `Bearer ${tok}` },
      });
      if (res.ok) {
        const data: UserAccount = await res.json();
        setUser(data);
        setZelloInput(data.zello_username || "");
        setReqDmr(data.req_dmr);
        setReqSvx(data.req_svxlink);
        setReqMumble(data.req_mumble);
        setReqDvs(data.req_dvswitch);
        setReqZello(data.req_zello);
      } else {
        localStorage.removeItem("ninjaradio_selfcare_token");
        setToken("");
        setUser(null);
      }
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const saved = typeof window !== "undefined" ? localStorage.getItem("ninjaradio_selfcare_token") || "" : "";
    if (saved) {
      setToken(saved);
      fetchProfile(saved);
    } else {
      setLoading(false);
    }
  }, []);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginError(null);
    try {
      const res = await fetch("/api/selfcare/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ identifier, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        setLoginError(data.detail || "Login gagal.");
      } else {
        localStorage.setItem("ninjaradio_selfcare_token", data.token);
        setToken(data.token);
        setUser(data.user);
      }
    } catch (err: any) {
      setLoginError(err.message);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem("ninjaradio_selfcare_token");
    setToken("");
    setUser(null);
  };

  const copyText = (key: string, val: string) => {
    navigator.clipboard.writeText(val);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const handleRotatePassword = async (protocol: string) => {
    setActionMsg(null);
    const res = await fetch("/api/selfcare/rotate-password", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ protocol }),
    });
    if (res.ok) {
      const updated = await res.json();
      setUser(updated);
      setActionMsg(`Password baru 6-karakter untuk ${protocol.toUpperCase()} berhasil dibuat.`);
    }
  };

  const handleWhitelistIp = async () => {
    setActionMsg(null);
    const res = await fetch("/api/selfcare/dvswitch/whitelist-ip", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    const data = await res.json();
    if (res.ok) {
      setUser(data);
      setActionMsg(`IP DVSwitch berhasil diperbarui ke ${data.dvswitch_allowed_ip}`);
    } else {
      setActionMsg(data.detail || "Gagal whitelist IP.");
    }
  };

  const handleRequestServices = async (e: React.FormEvent) => {
    e.preventDefault();
    setActionMsg(null);
    const res = await fetch("/api/selfcare/request-services", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        req_dmr: reqDmr,
        req_svxlink: reqSvx,
        req_mumble: reqMumble,
        req_dvswitch: reqDvs,
        req_zello: reqZello,
        zello_username: zelloInput.trim(),
      }),
    });
    if (res.ok) {
      const updated = await res.json();
      setUser(updated);
      setActionMsg("Permintaan layanan tambahan berhasil dikirim ke Admin.");
    }
  };

  return (
    <div className="min-h-screen bg-[#0a0e17] text-slate-100">
      <Navbar isConnected={isConnected} />
      <main className="max-w-4xl mx-auto px-4 py-8">
        {loading ? (
          <div className="text-center py-16 text-slate-400">Memuat akun Self-Care...</div>
        ) : !user ? (
          <div className="max-w-md mx-auto bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-2xl">
            <div className="flex items-center gap-3 mb-5">
              <div className="p-2.5 rounded-xl bg-cyan-500/15 border border-cyan-500/30 text-cyan-400">
                <KeyRound className="w-6 h-6" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white">Portal Self-Care Operator</h1>
                <p className="text-xs text-slate-400">Login untuk melihat status & kredensial unik protokol Anda.</p>
              </div>
            </div>
            {loginError && (
              <div className="mb-4 p-3 rounded-xl bg-rose-500/15 border border-rose-500/40 text-rose-300 text-xs">
                {loginError}
              </div>
            )}
            <form onSubmit={handleLogin} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Callsign atau DMR ID</label>
                <input
                  type="text"
                  required
                  value={identifier}
                  onChange={(e) => setIdentifier(e.target.value)}
                  placeholder="YD0XYZ atau 5104001"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Password</label>
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <button
                type="submit"
                className="w-full py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 font-semibold text-white text-sm"
              >
                Masuk ke Portal
              </button>
            </form>
            <div className="mt-4 text-center text-xs text-slate-400">
              Belum punya akun?{" "}
              <Link href="/register" className="text-emerald-400 hover:underline font-semibold">
                Daftar Callsign Baru
              </Link>
            </div>
          </div>
        ) : (
          <div className="space-y-6">
            <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 flex flex-wrap items-center justify-between gap-4">
              <div>
                <div className="flex items-center gap-2.5">
                  <h1 className="text-2xl font-bold text-white">{user.callsign}</h1>
                  <span className="px-2.5 py-0.5 rounded-full text-xs font-mono bg-slate-800 text-cyan-400 border border-slate-700">
                    ID: {user.dmr_id}
                  </span>
                  <span
                    className={`px-2.5 py-0.5 rounded-full text-xs font-bold ${
                      user.status === "APPROVED"
                        ? "bg-emerald-500/20 text-emerald-300 border border-emerald-500/40"
                        : user.status === "PENDING"
                        ? "bg-amber-500/20 text-amber-300 border border-amber-500/40"
                        : "bg-rose-500/20 text-rose-300 border border-rose-500/40"
                    }`}
                  >
                    {user.status}
                  </span>
                </div>
                <p className="text-xs text-slate-400 mt-1">
                  {user.name} • {user.organization} {user.organization_id ? `(${user.organization_id})` : ""}
                </p>
              </div>
              <div className="flex items-center gap-2">
                {user.status === "APPROVED" && user.allow_web && (
                  <Link
                    href="/radio"
                    className="px-3 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold text-white flex items-center gap-1.5"
                  >
                    <Radio className="w-3.5 h-3.5" /> Buka Web PTT
                  </Link>
                )}
                <button
                  onClick={handleLogout}
                  className="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs text-slate-300 flex items-center gap-1.5"
                >
                  <LogOut className="w-3.5 h-3.5" /> Keluar
                </button>
              </div>
            </div>

            {user.status === "PENDING" && (
              <div className="p-4 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-200 text-sm flex items-start gap-3">
                <ShieldAlert className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
                <div>
                  <strong>Status PENDING (Mode RX / Monitor Saja):</strong> Pendaftaran Anda sedang menunggu verifikasi
                  Admin. Anda dapat mendengarkan trafik di{" "}
                  <Link href="/" className="underline text-amber-300">
                    NOC Dashboard
                  </Link>
                  , namun akses TX di seluruh protokol masih dinonaktifkan.
                </div>
              </div>
            )}

            {(user.status === "REJECTED" || user.status === "SUSPENDED") && (
              <div className="p-4 rounded-2xl bg-rose-500/10 border border-rose-500/30 text-rose-200 text-sm">
                <strong>Status {user.status}:</strong> {user.rejected_reason || "Silakan hubungi Admin."}
              </div>
            )}

            {actionMsg && (
              <div className="p-3 rounded-xl bg-cyan-500/15 border border-cyan-500/30 text-cyan-200 text-xs">
                {actionMsg}
              </div>
            )}

            {user.status === "APPROVED" && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {[
                  {
                    key: "dmr",
                    title: "DMR Hotspot (Pi-Star / OpenGD77)",
                    allowed: user.allow_dmr,
                    port: "UDP 62031",
                    userLabel: `DMR ID: ${user.dmr_id}`,
                    pwd: user.dmr_password,
                  },
                  {
                    key: "svxlink",
                    title: "SvxLink / SvxReflector",
                    allowed: user.allow_svxlink,
                    port: "TCP/UDP 5300",
                    userLabel: `CALLSIGN: ${user.callsign}`,
                    pwd: user.svxlink_password,
                  },
                  {
                    key: "mumble",
                    title: "Mumble / Mumla VoIP",
                    allowed: user.allow_mumble,
                    port: "TCP/UDP 64738",
                    userLabel: `Username: ${user.callsign}`,
                    pwd: user.mumble_password,
                  },
                ].map((proto) => (
                  <div key={proto.key} className="bg-slate-900/90 border border-slate-800 rounded-2xl p-4">
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-sm font-bold text-white">{proto.title}</h3>
                      <span
                        className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${
                          proto.allowed ? "bg-emerald-500/20 text-emerald-300" : "bg-slate-800 text-slate-400"
                        }`}
                      >
                        {proto.allowed ? "AKTIF" : "BELUM DIIZINKAN"}
                      </span>
                    </div>
                    {proto.allowed ? (
                      <div className="space-y-2 text-xs">
                        <div className="text-slate-400">
                          {proto.port} • {proto.userLabel}
                        </div>
                        <div className="flex items-center justify-between bg-slate-950 border border-slate-800 rounded-lg px-3 py-2">
                          <span className="font-mono text-base tracking-widest text-cyan-300">{proto.pwd}</span>
                          <div className="flex items-center gap-1.5">
                            <button
                              onClick={() => copyText(proto.key, proto.pwd)}
                              className="p-1.5 rounded bg-slate-800 hover:bg-slate-700 text-slate-200"
                              title="Salin Password"
                            >
                              {copiedKey === proto.key ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                            </button>
                            <button
                              onClick={() => handleRotatePassword(proto.key)}
                              className="p-1.5 rounded bg-slate-800 hover:bg-slate-700 text-amber-300"
                              title="Buat Password Baru (6 Karakter)"
                            >
                              <RefreshCw className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </div>
                      </div>
                    ) : (
                      <p className="text-xs text-slate-500">Ajukan akses layanan ini di form di bawah jika diperlukan.</p>
                    )}
                  </div>
                ))}

                <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <h3 className="text-sm font-bold text-white">DVSwitch Mobile & Zello</h3>
                    <ShieldCheck className="w-4 h-4 text-cyan-400" />
                  </div>
                  <div className="text-xs space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-slate-300">DVSwitch (UDP 32345):</span>
                      <span className={user.allow_dvswitch ? "text-emerald-400 font-semibold" : "text-slate-500"}>
                        {user.allow_dvswitch ? user.dvswitch_allowed_ip || "Aktif (Belum Lock IP)" : "Belum Diizinkan"}
                      </span>
                    </div>
                    {user.allow_dvswitch && (
                      <button
                        onClick={handleWhitelistIp}
                        className="w-full py-1.5 rounded-lg bg-cyan-600/20 hover:bg-cyan-600/30 border border-cyan-500/40 text-cyan-300 flex items-center justify-center gap-1.5"
                      >
                        <Wifi className="w-3.5 h-3.5" /> Whitelist IP Saya untuk DVSwitch
                      </button>
                    )}
                    <div className="flex items-center justify-between pt-1 border-t border-slate-800">
                      <span className="text-slate-300">Zello Username:</span>
                      <span className={user.allow_zello ? "text-emerald-400 font-mono" : "text-slate-500"}>
                        {user.allow_zello ? user.zello_username || "-" : "Belum Diizinkan"}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            )}

            <form onSubmit={handleRequestServices} className="bg-slate-900/90 border border-slate-800 rounded-2xl p-5 space-y-4">
              <div className="flex items-center gap-2 text-sm font-bold text-white">
                <UserCheck className="w-4 h-4 text-emerald-400" />
                Ajukan Layanan Tambahan / Update Username Zello
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 text-xs">
                {[
                  { label: "DMR Hotspot", checked: reqDmr, set: setReqDmr },
                  { label: "SvxLink", checked: reqSvx, set: setReqSvx },
                  { label: "Mumble", checked: reqMumble, set: setReqMumble },
                  { label: "DVSwitch", checked: reqDvs, set: setReqDvs },
                  { label: "Zello Bridge", checked: reqZello, set: setReqZello },
                ].map((item) => (
                  <label key={item.label} className="flex items-center gap-2 p-2 rounded-lg bg-slate-950 border border-slate-800 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={item.checked}
                      onChange={(e) => item.set(e.target.checked)}
                      className="accent-cyan-500"
                    />
                    <span>{item.label}</span>
                  </label>
                ))}
              </div>
              {reqZello && (
                <input
                  type="text"
                  value={zelloInput}
                  onChange={(e) => setZelloInput(e.target.value)}
                  placeholder="Username Zello Anda"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-xs"
                />
              )}
              <button
                type="submit"
                className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-cyan-300 text-xs font-semibold flex items-center gap-1.5 border border-slate-700"
              >
                <Send className="w-3.5 h-3.5" /> Kirim Pembaruan Request
              </button>
            </form>
          </div>
        )}
      </main>
    </div>
  );
}
