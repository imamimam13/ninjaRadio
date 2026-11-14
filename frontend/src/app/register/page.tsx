"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { UserPlus, ShieldCheck, Radio, CheckCircle2, AlertCircle, Lock } from "lucide-react";

const ORG_PREFIX_HINTS: Record<string, string> = {
  ORARI: "Prefix standar ORARI: YB, YC, YD, YE, 7A-8I",
  RAPI: "Prefix standar RAPI: JZ",
  BRIDI: "Prefix standar BRIDI: BD, BD62",
  "CB-11M": "Prefix standar CB 11M: 11M, CB",
  KOMUNITAS: "Semua prefix relawan / komunitas diizinkan",
};

export default function RegisterPage() {
  const { isConnected } = useLiveMonitor();
  const router = useRouter();

  const [callsign, setCallsign] = useState("");
  const [dmrId, setDmrId] = useState("");
  const [name, setName] = useState("");
  const [city, setCity] = useState("");
  const [organization, setOrganization] = useState("ORARI");
  const [organizationId, setOrganizationId] = useState("");
  const [verificationNote, setVerificationNote] = useState("");
  const [password, setPassword] = useState("");

  const [reqWeb, setReqWeb] = useState(true);
  const [reqDmr, setReqDmr] = useState(false);
  const [reqSvxlink, setReqSvxlink] = useState(false);
  const [reqMumble, setReqMumble] = useState(false);
  const [reqDvswitch, setReqDvswitch] = useState(false);
  const [reqZello, setReqZello] = useState(false);
  const [zelloUsername, setZelloUsername] = useState("");

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);

    const parsedDmr = parseInt(dmrId, 10);
    if (!callsign.trim() || callsign.trim().length < 3) {
      setError("Callsign minimal 3 karakter.");
      return;
    }
    if (isNaN(parsedDmr) || parsedDmr < 1000) {
      setError("DMR ID harus berupa angka valid (minimal 4 digit).");
      return;
    }
    if (password.length < 4) {
      setError("Password akun minimal 4 karakter.");
      return;
    }
    if (reqZello && !zelloUsername.trim()) {
      setError("Masukkan Username Zello Anda jika mengajukan akses Zello.");
      return;
    }

    setSubmitting(true);
    try {
      const res = await fetch("/api/selfcare/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          callsign: callsign.trim().toUpperCase(),
          dmr_id: parsedDmr,
          name: name.trim() || callsign.trim().toUpperCase(),
          city: city.trim(),
          organization,
          organization_id: organizationId.trim(),
          verification_note: verificationNote.trim(),
          password,
          req_web: reqWeb,
          req_dmr: reqDmr,
          req_svxlink: reqSvxlink,
          req_mumble: reqMumble,
          req_dvswitch: reqDvswitch,
          req_zello: reqZello,
          zello_username: zelloUsername.trim(),
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.detail || "Pendaftaran gagal.");
      } else {
        if (data.token && typeof window !== "undefined") {
          localStorage.setItem("ninjaradio_selfcare_token", data.token);
        }
        setSuccessMsg(data.message || "Pendaftaran berhasil! Status: PENDING (RX Only).");
        setTimeout(() => router.push("/account"), 1500);
      }
    } catch (err: any) {
      setError(`Gagal menghubungi server: ${err.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#0a0e17] text-slate-100">
      <Navbar isConnected={isConnected} />
      <main className="max-w-3xl mx-auto px-4 py-8">
        <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-2xl">
          <div className="flex items-center justify-between border-b border-slate-800 pb-4 mb-6">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-400">
                <UserPlus className="w-6 h-6" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-white">Pendaftaran Akun Callsign (Self-Care)</h1>
                <p className="text-xs text-slate-400">
                  Daftarkan Callsign & DMR ID Anda untuk mendapatkan kredensial unik per-layanan.
                </p>
              </div>
            </div>
            <Link
              href="/account"
              className="text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-400 border border-slate-700"
            >
              Sudah Punya Akun? Login
            </Link>
          </div>

          <div className="mb-6 p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-200 text-xs flex items-start gap-2.5">
            <ShieldCheck className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
            <div>
              <strong>Verifikasi Wajib (Status Awal PENDING):</strong> Selama menunggu persetujuan Admin (khususnya
              verifikasi anggota <strong>ORARI / RAPI</strong>), akun Anda hanya dapat <strong>RX / Monitor</strong>{" "}
              dan seluruh transmisi (TX) diblokir hingga disetujui.
            </div>
          </div>

          {error && (
            <div className="mb-4 p-3 rounded-xl bg-rose-500/15 border border-rose-500/40 text-rose-300 text-sm flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {successMsg && (
            <div className="mb-4 p-3 rounded-xl bg-emerald-500/15 border border-emerald-500/40 text-emerald-300 text-sm flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 shrink-0" />
              <span>{successMsg}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Callsign *</label>
                <input
                  type="text"
                  required
                  value={callsign}
                  onChange={(e) => setCallsign(e.target.value.toUpperCase())}
                  placeholder="Contoh: YD0XYZ / JZ10ABC"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm uppercase focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">DMR ID *</label>
                <input
                  type="number"
                  required
                  value={dmrId}
                  onChange={(e) => setDmrId(e.target.value)}
                  placeholder="Contoh: 5104001"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Nama Operator *</label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Nama lengkap"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Kota / QTH</label>
                <input
                  type="text"
                  value={city}
                  onChange={(e) => setCity(e.target.value)}
                  placeholder="Contoh: Jakarta / Bandung"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Organisasi *</label>
                <select
                  value={organization}
                  onChange={(e) => setOrganization(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                >
                  <option value="ORARI">ORARI</option>
                  <option value="RAPI">RAPI</option>
                  <option value="BRIDI">BRIDI</option>
                  <option value="CB-11M">CB 11-Meter</option>
                  <option value="KOMUNITAS">Komunitas / SAR</option>
                </select>
                <p className="text-[11px] text-slate-400 mt-1">{ORG_PREFIX_HINTS[organization]}</p>
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">
                  No. Anggota / IAR / IKRAP / 10-28
                </label>
                <input
                  type="text"
                  value={organizationId}
                  onChange={(e) => setOrganizationId(e.target.value)}
                  placeholder="Untuk verifikasi ORARI / RAPI"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1 flex items-center gap-1.5">
                  <Lock className="w-3.5 h-3.5 text-cyan-400" /> Password Akun Portal *
                </label>
                <input
                  type="password"
                  required
                  minLength={4}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Minimal 4 karakter"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Catatan Verifikasi (Opsional)</label>
                <input
                  type="text"
                  value={verificationNote}
                  onChange={(e) => setVerificationNote(e.target.value)}
                  placeholder="Misal: Lokal/Daerah atau referensi"
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm focus:border-cyan-500 focus:outline-none"
                />
              </div>
            </div>

            <div className="border-t border-slate-800 pt-4">
              <label className="block text-xs font-semibold text-cyan-400 uppercase tracking-wider mb-3 flex items-center gap-1.5">
                <Radio className="w-4 h-4" /> Pilih Layanan yang Ingin Digunakan (Request Permission)
              </label>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 text-sm">
                {[
                  { label: "Web PTT (/radio)", checked: reqWeb, set: setReqWeb },
                  { label: "DMR Hotspot (Port 62031)", checked: reqDmr, set: setReqDmr },
                  { label: "SvxLink / SvxReflector (Port 5300)", checked: reqSvxlink, set: setReqSvxlink },
                  { label: "Mumble / Mumla (Port 64738)", checked: reqMumble, set: setReqMumble },
                  { label: "DVSwitch Mobile (Port 32345)", checked: reqDvswitch, set: setReqDvswitch },
                  { label: "Zello Channels Bridge", checked: reqZello, set: setReqZello },
                ].map((item) => (
                  <label
                    key={item.label}
                    className={`flex items-center gap-2.5 p-2.5 rounded-xl border cursor-pointer transition ${
                      item.checked
                        ? "bg-cyan-500/10 border-cyan-500/40 text-white"
                        : "bg-slate-950/60 border-slate-800 text-slate-400 hover:border-slate-700"
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={item.checked}
                      onChange={(e) => item.set(e.target.checked)}
                      className="accent-cyan-500 w-4 h-4"
                    />
                    <span>{item.label}</span>
                  </label>
                ))}
              </div>

              {reqZello && (
                <div className="mt-3 p-3 rounded-xl bg-amber-500/10 border border-amber-500/30">
                  <label className="block text-xs font-semibold text-amber-300 mb-1">
                    Username Zello Anda (Wajib untuk dikaitkan ke Callsign) *
                  </label>
                  <input
                    type="text"
                    required={reqZello}
                    value={zelloUsername}
                    onChange={(e) => setZelloUsername(e.target.value)}
                    placeholder="Contoh: yd0xyz_zello"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-amber-500/40 text-white text-sm focus:border-amber-400 focus:outline-none"
                  />
                </div>
              )}
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="w-full py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 font-semibold text-white transition shadow-lg shadow-emerald-900/30"
            >
              {submitting ? "Mengirim Pendaftaran..." : "Daftar & Ajukan Verifikasi Admin"}
            </button>
          </form>
        </div>
      </main>
    </div>
  );
}
