"use client";

import React, { useState, useEffect } from "react";
import { Navbar } from "@/components/Navbar";
import { AdminGuard } from "@/components/AdminGuard";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { getSavedAdminPin } from "@/lib/adminAuth";
import {
  UserCheck, UserX, Ban, CheckCircle2, AlertTriangle, RefreshCw, Shield
} from "lucide-react";

interface AdminUserItem {
  id: number;
  callsign: string;
  dmr_id: number;
  name: string;
  city: string;
  organization: string;
  organization_id: string;
  verification_note: string;
  status: string;
  rejected_reason: string;
  created_at: string;
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
  prefix_valid: boolean;
  prefix_hint: string;
}

export default function AdminRegistrationsPage() {
  const { isConnected } = useLiveMonitor();
  const [items, setItems] = useState<AdminUserItem[]>([]);
  const [statusFilter, setStatusFilter] = useState("PENDING");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Approval Modal State
  const [editing, setEditing] = useState<AdminUserItem | null>(null);
  const [allowWeb, setAllowWeb] = useState(true);
  const [allowDmr, setAllowDmr] = useState(false);
  const [allowSvx, setAllowSvx] = useState(false);
  const [allowMumble, setAllowMumble] = useState(false);
  const [allowDvs, setAllowDvs] = useState(false);
  const [allowZello, setAllowZello] = useState(false);
  const [zelloUsername, setZelloUsername] = useState("");
  const [saving, setSaving] = useState(false);

  const loadData = async (filter = statusFilter) => {
    const pin = getSavedAdminPin();
    if (!pin) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/admin/registrations?status=${filter}`, {
        headers: { "X-Admin-Pin": pin },
      });
      if (!res.ok) {
        const d = await res.json();
        setError(d.detail || "Gagal memuat data pendaftaran.");
      } else {
        setItems(await res.json());
      }
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData(statusFilter);
  }, [statusFilter]);

  const openApproveModal = (item: AdminUserItem) => {
    setEditing(item);
    setAllowWeb(item.status === "APPROVED" ? item.allow_web : item.req_web);
    setAllowDmr(item.status === "APPROVED" ? item.allow_dmr : item.req_dmr);
    setAllowSvx(item.status === "APPROVED" ? item.allow_svxlink : item.req_svxlink);
    setAllowMumble(item.status === "APPROVED" ? item.allow_mumble : item.req_mumble);
    setAllowDvs(item.status === "APPROVED" ? item.allow_dvswitch : item.req_dvswitch);
    setAllowZello(item.status === "APPROVED" ? item.allow_zello : item.req_zello);
    setZelloUsername(item.zello_username || "");
  };

  const handleApprove = async () => {
    if (!editing) return;
    const pin = getSavedAdminPin();
    setSaving(true);
    try {
      const res = await fetch(`/api/admin/registrations/${editing.id}/approve`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Admin-Pin": pin },
        body: JSON.stringify({
          allow_web: allowWeb,
          allow_dmr: allowDmr,
          allow_svxlink: allowSvx,
          allow_mumble: allowMumble,
          allow_dvswitch: allowDvs,
          allow_zello: allowZello,
          zello_username: zelloUsername.trim(),
        }),
      });
      if (res.ok) {
        setEditing(null);
        await loadData(statusFilter);
      }
    } finally {
      setSaving(false);
    }
  };

  const handleRejectOrSuspend = async (item: AdminUserItem, action: "reject" | "suspend") => {
    const reason = window.prompt(
      action === "reject" ? `Alasan menolak ${item.callsign}:` : `Alasan membekukan ${item.callsign}:`,
      action === "reject" ? "Data callsign/anggota tidak valid." : "Akun dibekukan oleh Admin."
    );
    if (reason === null) return;
    const pin = getSavedAdminPin();
    await fetch(`/api/admin/registrations/${item.id}/${action}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Admin-Pin": pin },
      body: JSON.stringify({ reason }),
    });
    await loadData(statusFilter);
  };

  return (
    <div className="min-h-screen bg-[#0a0e17] text-slate-100">
      <Navbar isConnected={isConnected} />
      <AdminGuard title="Verifikasi Pendaftaran Callsign (Self-Care)" tag="ADMIN APPROVAL">
        <main className="max-w-6xl mx-auto px-4 py-6 space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-900/90 border border-slate-800 rounded-2xl p-4">
            <div className="flex items-center gap-3">
              <Shield className="w-6 h-6 text-emerald-400" />
              <div>
                <h1 className="text-lg font-bold text-white">Manajemen Pendaftaran & Izin Layanan User</h1>
                <p className="text-xs text-slate-400">
                  Verifikasi anggota ORARI/RAPI/Komunitas dan aktifkan kredensial unik per protokol.
                </p>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {["PENDING", "APPROVED", "REJECTED", "SUSPENDED", "ALL"].map((st) => (
                <button
                  key={st}
                  onClick={() => setStatusFilter(st)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                    statusFilter === st
                      ? "bg-cyan-600 text-white"
                      : "bg-slate-800 text-slate-300 hover:bg-slate-700"
                  }`}
                >
                  {st}
                </button>
              ))}
              <button
                onClick={() => loadData(statusFilter)}
                className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300"
                title="Refresh"
              >
                <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin" : ""}`} />
              </button>
            </div>
          </div>

          {error && (
            <div className="p-3 rounded-xl bg-rose-500/15 border border-rose-500/40 text-rose-300 text-xs">
              {error}
            </div>
          )}

          {items.length === 0 ? (
            <div className="text-center py-12 bg-slate-900/50 border border-slate-800 rounded-2xl text-slate-400 text-sm">
              Tidak ada data pendaftaran untuk filter <strong>{statusFilter}</strong>.
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4">
              {items.map((item) => (
                <div
                  key={item.id}
                  className="bg-slate-900/90 border border-slate-800 rounded-2xl p-5 flex flex-col lg:flex-row lg:items-center justify-between gap-4"
                >
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-lg font-bold text-white">{item.callsign}</span>
                      <span className="px-2 py-0.5 rounded bg-slate-800 text-cyan-400 font-mono text-xs">
                        DMR ID: {item.dmr_id}
                      </span>
                      <span className="px-2 py-0.5 rounded bg-slate-800 text-slate-200 text-xs font-semibold">
                        {item.organization} {item.organization_id ? `• ${item.organization_id}` : ""}
                      </span>
                      <span
                        className={`px-2 py-0.5 rounded-full text-[11px] font-bold ${
                          item.status === "APPROVED"
                            ? "bg-emerald-500/20 text-emerald-300"
                            : item.status === "PENDING"
                            ? "bg-amber-500/20 text-amber-300"
                            : "bg-rose-500/20 text-rose-300"
                        }`}
                      >
                        {item.status}
                      </span>
                    </div>

                    <div className="text-xs text-slate-300">
                      <strong>{item.name}</strong> {item.city ? `(${item.city})` : ""}{" "}
                      {item.verification_note ? `— Catatan: "${item.verification_note}"` : ""}
                    </div>

                    <div
                      className={`text-[11px] flex items-center gap-1.5 ${
                        item.prefix_valid ? "text-emerald-400" : "text-amber-400"
                      }`}
                    >
                      {item.prefix_valid ? (
                        <CheckCircle2 className="w-3.5 h-3.5" />
                      ) : (
                        <AlertTriangle className="w-3.5 h-3.5" />
                      )}
                      <span>{item.prefix_hint}</span>
                    </div>

                    <div className="flex flex-wrap gap-1.5 pt-1 text-[11px]">
                      {[
                        { name: "Web", req: item.req_web, allow: item.allow_web },
                        { name: "DMR", req: item.req_dmr, allow: item.allow_dmr },
                        { name: "SvxLink", req: item.req_svxlink, allow: item.allow_svxlink },
                        { name: "Mumble", req: item.req_mumble, allow: item.allow_mumble },
                        { name: "DVSwitch", req: item.req_dvswitch, allow: item.allow_dvswitch },
                        { name: `Zello${item.zello_username ? ` (${item.zello_username})` : ""}`, req: item.req_zello, allow: item.allow_zello },
                      ].map((svc) => (
                        <span
                          key={svc.name}
                          className={`px-2 py-0.5 rounded border ${
                            svc.allow
                              ? "bg-emerald-500/15 border-emerald-500/40 text-emerald-300"
                              : svc.req
                              ? "bg-amber-500/15 border-amber-500/40 text-amber-300"
                              : "bg-slate-950 border-slate-800 text-slate-500"
                          }`}
                        >
                          {svc.name}: {svc.allow ? "AKTIF" : svc.req ? "DIMINTA" : "-"}
                        </span>
                      ))}
                    </div>

                    {item.status === "APPROVED" && (
                      <div className="text-[11px] font-mono text-slate-400 pt-1">
                        Pwd — DMR: <span className="text-cyan-300">{item.dmr_password || "-"}</span> | SvxLink:{" "}
                        <span className="text-cyan-300">{item.svxlink_password || "-"}</span> | Mumble:{" "}
                        <span className="text-cyan-300">{item.mumble_password || "-"}</span>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-wrap items-center gap-2 shrink-0">
                    <button
                      onClick={() => openApproveModal(item)}
                      className="px-3.5 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold flex items-center gap-1.5"
                    >
                      <UserCheck className="w-4 h-4" />
                      {item.status === "APPROVED" ? "Atur Izin" : "Approve & Buat Password"}
                    </button>
                    {item.status === "PENDING" && (
                      <button
                        onClick={() => handleRejectOrSuspend(item, "reject")}
                        className="px-3 py-2 rounded-xl bg-rose-600/20 hover:bg-rose-600/30 border border-rose-500/40 text-rose-300 text-xs font-semibold flex items-center gap-1.5"
                      >
                        <UserX className="w-4 h-4" /> Tolak
                      </button>
                    )}
                    {item.status === "APPROVED" && (
                      <button
                        onClick={() => handleRejectOrSuspend(item, "suspend")}
                        className="px-3 py-2 rounded-xl bg-amber-600/20 hover:bg-amber-600/30 border border-amber-500/40 text-amber-300 text-xs font-semibold flex items-center gap-1.5"
                      >
                        <Ban className="w-4 h-4" /> Bekukan
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {editing && (
            <div className="fixed inset-0 z-50 bg-black/75 flex items-center justify-center p-4">
              <div className="bg-slate-900 border border-slate-700 rounded-2xl max-w-md w-full p-6 space-y-4">
                <h2 className="text-base font-bold text-white">
                  Atur Izin Layanan: {editing.callsign} ({editing.dmr_id})
                </h2>
                <p className="text-xs text-slate-400">
                  Centang layanan yang diizinkan. Password 6-karakter unik akan dibuat otomatis untuk protokol yang aktif.
                </p>
                <div className="space-y-2 text-sm">
                  {[
                    { label: "Web PTT (/radio)", val: allowWeb, set: setAllowWeb, req: editing.req_web },
                    { label: "DMR Hotspot (62031)", val: allowDmr, set: setAllowDmr, req: editing.req_dmr },
                    { label: "SvxLink / SvxReflector (5300)", val: allowSvx, set: setAllowSvx, req: editing.req_svxlink },
                    { label: "Mumble / Mumla (64738)", val: allowMumble, set: setAllowMumble, req: editing.req_mumble },
                    { label: "DVSwitch Mobile (32345)", val: allowDvs, set: setAllowDvs, req: editing.req_dvswitch },
                    { label: "Zello Channels Bridge", val: allowZello, set: setAllowZello, req: editing.req_zello },
                  ].map((opt) => (
                    <label
                      key={opt.label}
                      className="flex items-center justify-between p-2.5 rounded-xl bg-slate-950 border border-slate-800 cursor-pointer"
                    >
                      <span className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={opt.val}
                          onChange={(e) => opt.set(e.target.checked)}
                          className="accent-emerald-500 w-4 h-4"
                        />
                        <span>{opt.label}</span>
                      </span>
                      {opt.req && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300">
                          DIMINTA
                        </span>
                      )}
                    </label>
                  ))}
                </div>
                {allowZello && (
                  <div>
                    <label className="block text-xs text-slate-300 mb-1">Username Zello Terkait</label>
                    <input
                      type="text"
                      value={zelloUsername}
                      onChange={(e) => setZelloUsername(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white text-sm"
                    />
                  </div>
                )}
                <div className="flex justify-end gap-2 pt-2">
                  <button
                    onClick={() => setEditing(null)}
                    className="px-4 py-2 rounded-xl bg-slate-800 text-slate-300 text-xs"
                  >
                    Batal
                  </button>
                  <button
                    onClick={handleApprove}
                    disabled={saving}
                    className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold"
                  >
                    {saving ? "Menyimpan..." : "Simpan & Approve"}
                  </button>
                </div>
              </div>
            </div>
          )}
        </main>
      </AdminGuard>
    </div>
  );
}
