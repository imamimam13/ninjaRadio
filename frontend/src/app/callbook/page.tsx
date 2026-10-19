"use client";

import React, { useState, useEffect, useRef } from "react";
import { Navbar } from "@/components/Navbar";
import { useLiveMonitor } from "@/hooks/useLiveMonitor";
import { Users, Search, Plus, MapPin, Building, ShieldCheck, ShieldAlert, Shield, ShieldX, Radio, Upload, Download, Check, AlertCircle, Lock, Unlock, Edit2, Trash2, Sliders, Settings2, Copy } from "lucide-react";
import { beepPlayer } from "@/components/BeepPlayer";
import { getIsAdminUnlocked, getSavedAdminPin, setAdminUnlocked, verifyAdminPin, ADMIN_AUTH_EVENT } from "@/lib/adminAuth";

interface CallbookItem {
  id: number;
  dmr_id: number;
  callsign: string;
  name: string;
  city: string;
  province_state: string;
  country: string;
  organization: string;
  organization_id: string;
  remarks: string;
  default_tg?: number;
  allowed_tgs_override?: string;
}

interface OrgItem {
  id?: number;
  code: string;
  name: string;
  description?: string;
}

interface FirewallRule {
  code: string;
  name: string;
  allowed_tgs: string;
  callsign_prefixes?: string;
  allow_tx: boolean;
  description?: string;
}

export default function CallbookPage() {
  const { isConnected } = useLiveMonitor();
  const [entries, setEntries] = useState<CallbookItem[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const [orgFilter, setOrgFilter] = useState("ALL");
  const [showAddModal, setShowAddModal] = useState(false);
  const [importStatus, setImportStatus] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Edit Modal State
  const [showEditModal, setShowEditModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [editDmrId, setEditDmrId] = useState(5104001);
  const [editCallsign, setEditCallsign] = useState("");
  const [editName, setEditName] = useState("");
  const [editCity, setEditCity] = useState("");
  const [editOrg, setEditOrg] = useState("KOMUNITAS");
  const [editDefaultTg, setEditDefaultTg] = useState(510);
  const [editRemarks, setEditRemarks] = useState("");
  const [editAllowedTgsOverride, setEditAllowedTgsOverride] = useState("");

  // TX Firewall & Permission Rules State
  const [showFirewallModal, setShowFirewallModal] = useState(false);
  const [firewallRules, setFirewallRules] = useState<FirewallRule[]>([]);
  const [isFirewallLoading, setIsFirewallLoading] = useState(false);
  const [firewallPin, setFirewallPin] = useState("");
  const [editingOrgCode, setEditingOrgCode] = useState<string | null>(null);
  const [ruleAllowedTgs, setRuleAllowedTgs] = useState("*");
  const [ruleCallsignPrefixes, setRuleCallsignPrefixes] = useState("");
  const [ruleAllowTx, setRuleAllowTx] = useState(true);
  const [firewallError, setFirewallError] = useState<string | null>(null);
  const [firewallSuccess, setFirewallSuccess] = useState<string | null>(null);
  const [isSavingFirewall, setIsSavingFirewall] = useState(false);

  // Batch Update Modal State
  const [showBatchModal, setShowBatchModal] = useState(false);
  const [batchTargetOrg, setBatchTargetOrg] = useState("BRIDI");
  const [batchTargetTg, setBatchTargetTg] = useState("510");
  const [batchFilterOrg, setBatchFilterOrg] = useState("ALL");
  const [batchPrefix, setBatchPrefix] = useState("BD");
  const [isBatchLoading, setIsBatchLoading] = useState(false);

  // Export user.json Modal State
  const [showExportModal, setShowExportModal] = useState(false);
  const [copiedUrl, setCopiedUrl] = useState<string | null>(null);

  const handleCopyUrl = (url: string) => {
    if (typeof window !== "undefined") {
      const fullUrl = `${window.location.origin}${url}`;
      navigator.clipboard.writeText(fullUrl);
      setCopiedUrl(url);
      beepPlayer?.playTalkGrant();
      setTimeout(() => setCopiedUrl(null), 2500);
    }
  };

  // Import CSV/JSON Modal state (Local File or External URL)
  const [showImportModal, setShowImportModal] = useState(false);
  const [importMode, setImportMode] = useState<"file" | "url">("file");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importUrl, setImportUrl] = useState("");
  const [importOrg, setImportOrg] = useState("AUTO");
  const [importTg, setImportTg] = useState("510");
  const [isImporting, setIsImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  // Organizations registry state
  const [organizations, setOrganizations] = useState<OrgItem[]>([
    { code: "ORARI", name: "ORARI" },
    { code: "RAPI", name: "RAPI" },
    { code: "CB-11M", name: "CB 11M" },
    { code: "KOMUNITAS", name: "Komunitas / SAR" },
  ]);
  const [showOrgPinModal, setShowOrgPinModal] = useState(false);
  const [adminPinInput, setAdminPinInput] = useState("");
  const [newOrgName, setNewOrgName] = useState("");
  const [newOrgCode, setNewOrgCode] = useState("");
  const [newOrgDesc, setNewOrgDesc] = useState("");
  const [orgPinError, setOrgPinError] = useState<string | null>(null);
  const [isSubmittingOrg, setIsSubmittingOrg] = useState(false);

  // New Entry
  const [newDmrId, setNewDmrId] = useState(5104001);
  const [newCallsign, setNewCallsign] = useState("");
  const [newName, setNewName] = useState("");
  const [newCity, setNewCity] = useState("");
  const [newOrg, setNewOrg] = useState("ORARI");
  const [newOrgId, setNewOrgId] = useState("");
  const [newDefaultTg, setNewDefaultTg] = useState(510);
  const [addError, setAddError] = useState<string | null>(null);
  const [isSubmittingAdd, setIsSubmittingAdd] = useState<boolean>(false);

  // Global Admin Unlock State
  const [isAdminUnlocked, setIsAdminUnlocked] = useState(false);
  const [showGlobalPinModal, setShowGlobalPinModal] = useState(false);
  const [globalPinInput, setGlobalPinInput] = useState("");
  const [globalPinError, setGlobalPinError] = useState("");
  const [isVerifyingGlobalPin, setIsVerifyingGlobalPin] = useState(false);

  useEffect(() => {
    setIsAdminUnlocked(getIsAdminUnlocked());
    const handleAuth = () => {
      setIsAdminUnlocked(getIsAdminUnlocked());
    };
    window.addEventListener(ADMIN_AUTH_EVENT, handleAuth);
    window.addEventListener("storage", handleAuth);
    return () => {
      window.removeEventListener(ADMIN_AUTH_EVENT, handleAuth);
      window.removeEventListener("storage", handleAuth);
    };
  }, []);

  const fetchOrganizations = () => {
    fetch("/api/organizations")
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setOrganizations(data);
        }
      })
      .catch(() => {});
  };

  const fetchCallbook = () => {
    let url = `/api/callbook?`;
    if (orgFilter !== "ALL") url += `org=${orgFilter}&`;
    if (searchTerm) url += `q=${encodeURIComponent(searchTerm)}&`;

    const headers: Record<string, string> = {};
    const savedPin = getSavedAdminPin();
    if (savedPin) {
      headers["X-Admin-Pin"] = savedPin;
    }

    fetch(url, { headers })
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data)) setEntries(data);
      })
      .catch(() => {});
  };

  useEffect(() => {
    fetchOrganizations();
  }, []);

  useEffect(() => {
    fetchCallbook();
  }, [searchTerm, orgFilter, isAdminUnlocked]);

  const handleCreateOrgWithPin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adminPinInput || !newOrgName) return;
    setIsSubmittingOrg(true);
    setOrgPinError(null);

    try {
      const res = await fetch("/api/organizations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          pin: adminPinInput,
          name: newOrgName,
          code: newOrgCode || newOrgName.toUpperCase().replace(/\s+/g, "_"),
          description: newOrgDesc,
        }),
      });

      const data = await res.json();
      if (res.ok) {
        beepPlayer?.playTalkGrant();
        setShowOrgPinModal(false);
        const createdOrg = data.organization;
        const newCode = createdOrg.code;
        setNewOrg(newCode);
        fetchOrganizations();
        setImportStatus(`✓ Organisasi "${createdOrg.name}" berhasil ditambahkan oleh Admin!`);
        setTimeout(() => setImportStatus(null), 4000);
      } else {
        beepPlayer?.playRogerBeep();
        setOrgPinError(data.detail || "Gagal menambahkan organisasi. Cek PIN Admin.");
      }
    } catch {
      setOrgPinError("Terjadi kesalahan jaringan.");
    } finally {
      setIsSubmittingOrg(false);
    }
  };

  const handleExecuteImport = async () => {
    if (importMode === "file" && !importFile) return;
    if (importMode === "url" && !importUrl.trim()) return;

    setIsImporting(true);
    setImportError(null);

    try {
      let res: Response;
      if (importMode === "url") {
        res = await fetch("/api/callbook/import-url", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            url: importUrl.trim(),
            organization: importOrg && importOrg !== "AUTO" ? importOrg : null,
            default_tg: importTg ? Number(importTg) : null,
          }),
        });
      } else {
        const formData = new FormData();
        formData.append("file", importFile!);
        if (importOrg && importOrg !== "AUTO") {
          formData.append("organization", importOrg);
        }
        if (importTg) {
          formData.append("default_tg", importTg);
        }
        res = await fetch("/api/callbook/import", {
          method: "POST",
          body: formData,
        });
      }

      const data = await res.json();
      if (res.ok && data.success) {
        setShowImportModal(false);
        setImportStatus(
          `✓ Berhasil menarik & mengimpor ${data.imported_count || 0} data pengguna! (Dilewati: ${data.skipped_count || 0})`
        );
        beepPlayer?.playTalkGrant();
        fetchCallbook();
        fetchOrganizations();
        setTimeout(() => setImportStatus(null), 5000);
      } else {
        beepPlayer?.playRogerBeep();
        setImportError(data.detail || data.error || "Gagal mengimpor data Callbook.");
      }
    } catch {
      setImportError("Terjadi kesalahan jaringan saat mengimpor data.");
    } finally {
      setIsImporting(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const handleAddCallbook = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCallsign || !newDmrId) return;
    setIsSubmittingAdd(true);
    setAddError(null);
    try {
      const res = await fetch("/api/callbook", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dmr_id: Number(newDmrId),
          callsign: newCallsign.toUpperCase(),
          name: newName,
          city: newCity,
          province_state: "Indonesia",
          country: "Indonesia",
          organization: newOrg,
          organization_id: newOrgId || newCallsign,
          remarks: "Registered via NinjaRadio Core",
          default_tg: Number(newDefaultTg) || 510,
        }),
      });
      const data = await res.json();
      if (res.ok) {
        setShowAddModal(false);
        setNewCallsign("");
        setNewName("");
        setNewCity("");
        setNewDefaultTg(510);
        setAddError(null);
        fetchCallbook();
        beepPlayer?.playTalkGrant();
      } else {
        beepPlayer?.playRogerBeep();
        setAddError(data.detail || data.error || "Gagal menyimpan data pengguna.");
      }
    } catch {
      setAddError("Terjadi kesalahan jaringan saat menyimpan.");
    } finally {
      setIsSubmittingAdd(false);
    }
  };

  const handleOpenEditModal = (entry: CallbookItem) => {
    setEditId(entry.id);
    setEditDmrId(entry.dmr_id);
    setEditCallsign(entry.callsign);
    setEditName(entry.name);
    setEditCity(entry.city || "");
    setEditOrg(entry.organization || "KOMUNITAS");
    setEditDefaultTg(entry.default_tg || 510);
    setEditRemarks(entry.remarks || "");
    setEditAllowedTgsOverride(entry.allowed_tgs_override || "");
    setShowEditModal(true);
  };

  const handleSaveEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editId || !editCallsign || !editDmrId) return;

    try {
      const res = await fetch(`/api/callbook/${editId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dmr_id: Number(editDmrId),
          callsign: editCallsign.toUpperCase(),
          name: editName,
          city: editCity,
          province_state: "Indonesia",
          country: "Indonesia",
          organization: editOrg,
          organization_id: editCallsign.toUpperCase(),
          remarks: editRemarks,
          default_tg: Number(editDefaultTg) || 510,
          allowed_tgs_override: editAllowedTgsOverride.trim() ? editAllowedTgsOverride.trim() : null,
        }),
      });

      if (res.ok) {
        setShowEditModal(false);
        fetchCallbook();
        beepPlayer?.playTalkGrant();
        setImportStatus(`✓ Berhasil memperbarui data ${editCallsign.toUpperCase()}!`);
        setTimeout(() => setImportStatus(null), 4000);
      }
    } catch {
      // Error
    }
  };

  const fetchFirewallRules = async () => {
    setIsFirewallLoading(true);
    try {
      const res = await fetch("/api/firewall/rules");
      if (res.ok) {
        const data = await res.json();
        setFirewallRules(data);
      }
    } catch {
      // offline
    } finally {
      setIsFirewallLoading(false);
    }
  };

  const handleStartEditRule = (rule: FirewallRule) => {
    setEditingOrgCode(rule.code);
    setRuleAllowedTgs(rule.allowed_tgs || "*");
    setRuleCallsignPrefixes(rule.callsign_prefixes || "");
    setRuleAllowTx(rule.allow_tx !== false);
    setFirewallError(null);
    setFirewallSuccess(null);
  };

  const handleSaveFirewallRule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingOrgCode) return;
    setIsSavingFirewall(true);
    setFirewallError(null);
    setFirewallSuccess(null);
    try {
      const res = await fetch(`/api/firewall/rules/${editingOrgCode}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          pin: firewallPin,
          allowed_tgs: ruleAllowedTgs.trim() || "*",
          callsign_prefixes: ruleCallsignPrefixes.trim(),
          allow_tx: ruleAllowTx,
        }),
      });
      const data = await res.json();
      if (res.ok && data.status === "SUCCESS") {
        setFirewallSuccess(`Aturan firewall untuk ${editingOrgCode} berhasil disimpan.`);
        setEditingOrgCode(null);
        fetchFirewallRules();
        beepPlayer?.playTalkGrant();
      } else {
        setFirewallError(data.detail || data.error || "Gagal menyimpan aturan. Pastikan PIN Admin benar.");
        beepPlayer?.playRogerBeep();
      }
    } catch (err: any) {
      setFirewallError(`Error jaringan: ${err.message}`);
    } finally {
      setIsSavingFirewall(false);
    }
  };

  const handleDeleteEntry = async (id: number, callsign: string) => {
    if (!confirm(`Hapus pengguna ${callsign} dari Callbook?`)) return;

    try {
      const res = await fetch(`/api/callbook/${id}`, {
        method: "DELETE",
      });
      if (res.ok) {
        fetchCallbook();
        beepPlayer?.playRogerBeep();
        setImportStatus(`✓ Berhasil menghapus ${callsign}`);
        setTimeout(() => setImportStatus(null), 4000);
      }
    } catch {
      // Error
    }
  };

  const handleExecuteBatchUpdate = async () => {
    setIsBatchLoading(true);
    try {
      const res = await fetch("/api/callbook/batch-update", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          organization: batchTargetOrg,
          default_tg: Number(batchTargetTg),
          filter_org: batchFilterOrg === "ALL" ? null : batchFilterOrg,
          callsign_prefix: batchPrefix || null,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        setShowBatchModal(false);
        fetchCallbook();
        fetchOrganizations();
        beepPlayer?.playTalkGrant();
        setImportStatus(`✓ Berhasil memperbarui masal ${data.updated_count} pengguna ke ${batchTargetOrg} (TG ${batchTargetTg})!`);
        setTimeout(() => setImportStatus(null), 5000);
      }
    } catch {
      // Error
    } finally {
      setIsBatchLoading(false);
    }
  };

  return (
    <div className="min-h-screen pb-20 md:pb-8 flex flex-col bg-[#07090e] bg-grid-pattern">
      <Navbar isConnected={isConnected} />

      <main className="flex-1 max-w-7xl w-full mx-auto px-3 sm:px-4 py-4 space-y-4">
        
        {/* Header */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 sm:p-5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-2xl">
          <div>
            <h2 className="text-xl font-heading font-black text-white flex items-center gap-2">
              <Users className="w-5 h-5 text-cyan-400" />
              CALLBOOK DIRECTORY & USER REGISTRY
            </h2>
            <p className="text-xs font-mono text-slate-400 mt-1">
              Direktori multi-identitas Radio Amatir (RadioID.net), RAPI (10-28 ID), CB 11M, dan Radio Relawan/Komunitas
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">
            {isAdminUnlocked ? (
              <button
                onClick={() => {
                  setAdminUnlocked(false);
                  beepPlayer?.playRogerBeep();
                }}
                className="flex items-center gap-1.5 px-3 py-2 bg-emerald-950/70 border border-emerald-500/60 hover:bg-emerald-900 text-emerald-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
                title="Kunci kembali mode Admin (Sembunyikan DMR ID)"
              >
                <Unlock className="w-4 h-4 text-emerald-400" />
                <span>Admin Aktif (Kunci)</span>
              </button>
            ) : (
              <button
                onClick={() => {
                  setGlobalPinInput("");
                  setGlobalPinError("");
                  setShowGlobalPinModal(true);
                }}
                className="flex items-center gap-1.5 px-3 py-2 bg-amber-500/15 border border-amber-500/50 hover:bg-amber-500/25 text-amber-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
                title="Buka PIN Admin untuk melihat DMR ID asli dan mengelola database"
              >
                <Lock className="w-4 h-4 text-amber-400" />
                <span>Buka DMR ID (Admin)</span>
              </button>
            )}

            <button
              onClick={() => {
                if (!isAdminUnlocked) {
                  setShowGlobalPinModal(true);
                  return;
                }
                setShowBatchModal(true);
              }}
              className="flex items-center gap-1.5 px-3 py-2 bg-[#05080f] border border-amber-600/60 hover:border-amber-400 text-amber-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
            >
              <Sliders className="w-4 h-4 text-amber-400" />
              <span>Ubah Masal (Batch)</span>
            </button>

            <button
              onClick={() => {
                if (!isAdminUnlocked) {
                  setShowGlobalPinModal(true);
                  return;
                }
                setImportFile(null);
                setImportError(null);
                setShowImportModal(true);
              }}
              className="flex items-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-cyan-900/60 hover:border-cyan-400 text-cyan-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
              title="Import Callbook dari File Lokal atau Tarik JSON dari Link URL Luar"
            >
              <Upload className="w-4 h-4 text-cyan-400" />
              <span>Import File / Link JSON</span>
            </button>

            <button
              onClick={() => setShowExportModal(true)}
              className="flex items-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-emerald-700/60 hover:border-emerald-400 text-emerald-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
              title="Unduh user.json per group / organisasi"
            >
              <Download className="w-4 h-4 text-emerald-400" />
              <span>Unduh user.json</span>
            </button>

            <button
              onClick={() => {
                if (!isAdminUnlocked) {
                  setShowGlobalPinModal(true);
                  return;
                }
                setFirewallError(null);
                setFirewallSuccess(null);
                setEditingOrgCode(null);
                setShowFirewallModal(true);
                fetchFirewallRules();
              }}
              className="flex items-center gap-1.5 px-3.5 py-2 bg-[#05080f] border border-rose-700/60 hover:border-rose-400 text-rose-300 text-xs font-mono font-bold rounded-xl transition-all shadow-md"
            >
              <ShieldAlert className="w-4 h-4 text-rose-400" />
              <span>Aturan TX & Firewall</span>
            </button>

            <button
              onClick={() => {
                if (!isAdminUnlocked) {
                  setShowGlobalPinModal(true);
                  return;
                }
                setAddError(null);
                setShowAddModal(true);
              }}
              className="flex items-center gap-1.5 px-4 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 font-heading font-black text-xs rounded-xl shadow-lg shadow-cyan-500/20 transition-all"
            >
              <Plus className="w-4 h-4" />
              <span>Daftar User Baru</span>
            </button>
          </div>
        </div>

        {/* Import Status Notification */}
        {importStatus && (
          <div className="bg-[#0b121e] border border-cyan-500/50 p-3 rounded-xl text-xs font-mono font-bold text-cyan-300 flex items-center gap-2 animate-pulse">
            <Check className="w-4 h-4 text-emerald-400" />
            <span>{importStatus}</span>
          </div>
        )}

        {/* Filters */}
        <div className="bg-[#0b121e] border border-cyan-900/40 rounded-xl p-3 flex flex-col sm:flex-row items-center justify-between gap-3 shadow-lg">
          <div className="relative w-full sm:w-80">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Cari DMR ID, Callsign, Nama, Kota..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-9 pr-3 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-white placeholder-slate-500 font-mono focus:outline-none focus:border-cyan-400"
            />
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto text-xs font-mono text-slate-400">
            <span>Filter Organisasi:</span>
            <select
              value={orgFilter}
              onChange={(e) => setOrgFilter(e.target.value)}
              className="px-2.5 py-1.5 bg-[#05080f] border border-cyan-900/60 rounded-xl text-xs text-cyan-300 font-mono"
            >
              <option value="ALL">Semua Organisasi</option>
              {organizations.map((org) => (
                <option key={org.code} value={org.code}>
                  {org.name || org.code}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Directory Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
          {entries.map((entry) => (
            <div
              key={entry.id}
              className="bg-[#0b121e] border border-cyan-900/40 rounded-2xl p-4 hover:border-cyan-500/50 hover:shadow-[0_0_15px_rgba(6,182,212,0.1)] transition-all space-y-3"
            >
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-1.5 text-[11px] font-mono text-slate-400">
                    <span>
                      DMR ID:{" "}
                      {isAdminUnlocked && entry.dmr_id ? (
                        <strong className="text-cyan-400 font-mono font-bold">{entry.dmr_id}</strong>
                      ) : (
                        <span
                          onClick={() => {
                            setGlobalPinInput("");
                            setGlobalPinError("");
                            setShowGlobalPinModal(true);
                          }}
                          className="inline-flex items-center gap-1 text-slate-400 bg-slate-900/90 hover:bg-slate-800 px-1.5 py-0.5 rounded border border-slate-700/80 cursor-pointer transition text-[10px]"
                          title="DMR ID disembunyikan untuk mencegah spoofing. Ketuk untuk verifikasi Admin PIN."
                        >
                          <Lock className="w-2.5 h-2.5 text-amber-400" />
                          <span className="tracking-widest font-mono text-slate-300">•••••••</span>
                          <span className="text-[9px] text-amber-400/90 font-sans font-semibold">Admin</span>
                        </span>
                      )}
                    </span>
                    {entry.default_tg && (
                      <span className="px-1.5 py-0.2 rounded bg-amber-950/70 border border-amber-600/40 text-[10px] text-amber-300 font-bold">
                        TG {entry.default_tg}
                      </span>
                    )}
                  </div>
                  <h3 className="text-2xl font-black text-white font-heading tracking-wide mt-0.5">
                    {entry.callsign}
                  </h3>
                </div>
                <div className="flex flex-col items-end gap-1.5">
                  <span
                    className={`px-2 py-0.5 rounded-full text-[10px] font-mono font-bold ${
                      entry.organization === "ORARI"
                        ? "bg-cyan-950 text-cyan-300 border border-cyan-800"
                        : entry.organization === "RAPI"
                        ? "bg-blue-950 text-blue-300 border border-blue-800"
                        : entry.organization === "CB-11M"
                        ? "bg-amber-950 text-amber-300 border border-amber-800"
                        : entry.organization === "BRIDI"
                        ? "bg-purple-950 text-purple-300 border border-purple-800"
                        : "bg-emerald-950 text-emerald-300 border border-emerald-800"
                    }`}
                  >
                    {entry.organization}
                  </span>
                  <div className="flex items-center gap-1">
                    {isAdminUnlocked ? (
                      <>
                        <button
                          onClick={() => handleOpenEditModal(entry)}
                          title="Edit Data User & TG"
                          className="p-1 px-1.5 rounded-lg bg-[#05080f] border border-cyan-900/60 text-slate-300 hover:text-cyan-300 hover:border-cyan-500 text-[10px] font-mono flex items-center gap-1 transition-all"
                        >
                          <Edit2 className="w-3 h-3 text-cyan-400" />
                          <span>Edit</span>
                        </button>
                        <button
                          onClick={() => handleDeleteEntry(entry.id, entry.callsign)}
                          title="Hapus Pengguna"
                          className="p-1 px-1 rounded-lg bg-[#05080f] border border-cyan-900/60 text-slate-400 hover:text-red-400 hover:border-red-500 transition-all"
                        >
                          <Trash2 className="w-3 h-3 text-red-400" />
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => {
                          setGlobalPinInput("");
                          setGlobalPinError("");
                          setShowGlobalPinModal(true);
                        }}
                        title="Buka Kunci Admin untuk Mengedit atau Menghapus User"
                        className="p-1 px-1.5 rounded-lg bg-[#05080f] border border-slate-800 hover:border-amber-500/50 text-slate-400 hover:text-amber-300 text-[10px] font-mono flex items-center gap-1 transition-all"
                      >
                        <Lock className="w-3 h-3 text-amber-400/80" />
                        <span>Admin</span>
                      </button>
                    )}
                  </div>
                </div>
              </div>

              <div className="space-y-1 text-xs">
                <div className="text-slate-200 font-semibold">{entry.name}</div>
                <div className="flex items-center gap-1 text-slate-400 text-[11px] font-mono">
                  <MapPin className="w-3.5 h-3.5 text-cyan-500" />
                  <span>{entry.city || "Indonesia"}, {entry.province_state}</span>
                </div>
                {entry.organization_id && (
                  <div className="text-[11px] text-slate-500 font-mono">
                    ID Org: {entry.organization_id}
                  </div>
                )}
              </div>

              {entry.remarks && (
                <div className="pt-2 border-t border-cyan-900/40 text-[11px] text-slate-400 italic">
                  "{entry.remarks}"
                </div>
              )}
            </div>
          ))}
        </div>

      </main>

      {/* Import File / External URL Modal (CSV / JSON) */}
      {showImportModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-cyan-500/60 rounded-3xl max-w-lg w-full p-6 space-y-4 shadow-2xl">
            <div className="flex items-start justify-between border-b border-cyan-950 pb-3">
              <div>
                <h3 className="font-heading font-black text-lg text-white flex items-center gap-2">
                  <Upload className="w-5 h-5 text-cyan-400" />
                  Import Callbook (File Lokal / Link URL Luar)
                </h3>
                <p className="text-xs font-mono text-slate-400 mt-1">
                  Upload file CSV/JSON atau tarik langsung data JSON/CSV dari link server luar
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowImportModal(false)}
                className="text-slate-500 hover:text-white text-lg font-bold"
              >
                ✕
              </button>
            </div>

            {/* Source Mode Tabs */}
            <div className="grid grid-cols-2 gap-2 p-1 bg-[#05080f] border border-cyan-900/60 rounded-2xl">
              <button
                type="button"
                onClick={() => {
                  setImportMode("file");
                  setImportError(null);
                }}
                className={`py-2 px-3 rounded-xl text-xs font-mono font-bold transition-all flex items-center justify-center gap-1.5 ${
                  importMode === "file"
                    ? "bg-cyan-500/20 border border-cyan-400 text-cyan-300 shadow"
                    : "text-slate-400 hover:text-slate-200"
                }`}
              >
                <Upload className="w-3.5 h-3.5" />
                <span>📁 Upload File Lokal</span>
              </button>
              <button
                type="button"
                onClick={() => {
                  setImportMode("url");
                  setImportError(null);
                }}
                className={`py-2 px-3 rounded-xl text-xs font-mono font-bold transition-all flex items-center justify-center gap-1.5 ${
                  importMode === "url"
                    ? "bg-cyan-500/20 border border-cyan-400 text-cyan-300 shadow"
                    : "text-slate-400 hover:text-slate-200"
                }`}
              >
                <Download className="w-3.5 h-3.5" />
                <span>🌐 Tarik Link URL Luar</span>
              </button>
            </div>

            {importError && (
              <div className="p-3 bg-red-950/70 border border-red-500/50 rounded-xl text-red-300 text-xs font-mono flex items-center gap-2">
                <AlertCircle className="w-4 h-4 text-red-400 shrink-0" />
                <span>{importError}</span>
              </div>
            )}

            {/* Step 1: File Selection OR External URL Input */}
            {importMode === "file" ? (
              <div>
                <label className="block text-xs font-mono text-slate-300 mb-1.5 font-bold">
                  1. Pilih File (JSON atau CSV)
                </label>
                <div
                  onClick={() => fileInputRef.current?.click()}
                  className={`border-2 border-dashed rounded-2xl p-4 text-center cursor-pointer transition-all ${
                    importFile
                      ? "border-emerald-500/60 bg-emerald-950/20"
                      : "border-cyan-900/60 bg-[#05080f] hover:border-cyan-400/60"
                  }`}
                >
                  <input
                    type="file"
                    ref={fileInputRef}
                    accept=".csv,.json"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (f) {
                        setImportFile(f);
                        setImportError(null);
                      }
                    }}
                    className="hidden"
                  />
                  {importFile ? (
                    <div className="space-y-1">
                      <div className="inline-flex items-center gap-2 px-3 py-1 bg-emerald-900/50 border border-emerald-500/50 rounded-xl text-emerald-300 font-mono text-xs font-bold">
                        <Check className="w-4 h-4 text-emerald-400" />
                        {importFile.name}
                      </div>
                      <p className="text-[11px] text-slate-400 font-mono">
                        Ukuran: {(importFile.size / 1024).toFixed(1)} KB — Klik untuk ganti file
                      </p>
                    </div>
                  ) : (
                    <div className="space-y-1">
                      <Upload className="w-7 h-7 text-cyan-400 mx-auto mb-1 opacity-80" />
                      <p className="text-xs font-bold text-slate-200 font-mono">
                        Klik untuk memilih file CSV / JSON
                      </p>
                      <p className="text-[11px] text-slate-500 font-mono">
                        Mendukung format RadioID.net CSV & JSON list/object
                      </p>
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div className="space-y-2">
                <label className="block text-xs font-mono text-slate-300 font-bold">
                  1. Masukkan Link URL Luar (JSON / CSV)
                </label>
                <input
                  type="url"
                  value={importUrl}
                  onChange={(e) => {
                    setImportUrl(e.target.value);
                    setImportError(null);
                  }}
                  placeholder="https://contoh-server.id/api/callbook/user.json"
                  className="w-full px-3.5 py-2.5 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 text-xs font-mono focus:border-cyan-400 outline-none"
                />
                <p className="text-[11px] text-slate-400 font-mono leading-relaxed">
                  Server NinjaRadio akan menarik langsung data JSON/CSV dari link di atas (bebas kendala CORS browser). Mendukung format <code className="text-cyan-300">user.json</code>, <code className="text-cyan-300">RadioID.net JSON/CSV</code>, maupun endpoint API Callbook server lain.
                </p>
              </div>
            )}

            {/* Target Organization Selector */}
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label className="text-xs font-mono text-slate-300 font-bold">
                  2. Pilih Organisasi / Group Sasaran
                </label>
                <button
                  type="button"
                  onClick={() => {
                    setAdminPinInput("");
                    setNewOrgName("");
                    setNewOrgCode("");
                    setNewOrgDesc("");
                    setOrgPinError(null);
                    setShowOrgPinModal(true);
                  }}
                  className="text-[11px] text-cyan-400 hover:text-cyan-300 underline font-mono"
                >
                  + Tambah Org Baru (PIN)
                </button>
              </div>
              <select
                value={importOrg}
                onChange={(e) => setImportOrg(e.target.value)}
                className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 text-xs font-mono font-bold focus:border-cyan-400 outline-none"
              >
                <option value="AUTO">🌐 Otomatis / Sesuai Kolom di Data</option>
                {organizations.map((org) => (
                  <option key={org.code} value={org.code}>
                    {org.name || org.code} ({org.code})
                  </option>
                ))}
              </select>
              <p className="text-[11px] text-slate-500 font-mono">
                Pilih organisasi jika ingin memasukkan semua kontak yang ditarik ke satu group tertentu.
              </p>
            </div>

            {/* Default Talkgroup Selector */}
            <div className="space-y-1.5">
              <label className="text-xs font-mono text-slate-300 font-bold">
                3. Default TalkGroup (TG) Sasaran
              </label>
              <div className="grid grid-cols-4 gap-1.5 mb-2">
                {[
                  { tg: "510", label: "510 Indo" },
                  { tg: "5101", label: "5101 Jabar" },
                  { tg: "5102", label: "5102 Jateng" },
                  { tg: "5103", label: "5103 Jatim" },
                  { tg: "5109", label: "5109 RAPI" },
                  { tg: "5108", label: "5108 CB 11M" },
                  { tg: "91", label: "91 WW" },
                  { tg: "9990", label: "9990 Echo" },
                ].map((item) => (
                  <button
                    key={item.tg}
                    type="button"
                    onClick={() => setImportTg(item.tg)}
                    className={`px-2 py-1.5 rounded-lg text-[11px] font-mono font-bold transition-all border ${
                      importTg === item.tg
                        ? "bg-cyan-500/20 border-cyan-400 text-cyan-300"
                        : "bg-[#05080f] border-cyan-900/40 text-slate-400 hover:border-slate-600"
                    }`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs font-mono text-slate-400">TG Custom:</span>
                <input
                  type="number"
                  value={importTg}
                  onChange={(e) => setImportTg(e.target.value)}
                  placeholder="Contoh: 510"
                  className="flex-1 px-3 py-1.5 bg-[#05080f] border border-cyan-900/80 rounded-xl text-amber-300 font-mono font-bold text-xs focus:border-cyan-400 outline-none"
                />
              </div>
            </div>

            {/* Action Buttons */}
            <div className="flex gap-2 pt-2 border-t border-cyan-950">
              <button
                type="button"
                onClick={() => setShowImportModal(false)}
                className="flex-1 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl font-mono text-xs font-bold transition-all"
              >
                Batal
              </button>
              <button
                type="button"
                disabled={(importMode === "file" ? !importFile : !importUrl.trim()) || isImporting}
                onClick={handleExecuteImport}
                className={`flex-1 py-2.5 rounded-xl font-heading font-black text-xs transition-all flex items-center justify-center gap-2 ${
                  (importMode === "file" ? !importFile : !importUrl.trim()) || isImporting
                    ? "bg-cyan-900/40 text-cyan-600 cursor-not-allowed"
                    : "bg-cyan-400 hover:bg-cyan-300 text-slate-950 shadow-lg shadow-cyan-500/25"
                }`}
              >
                {isImporting ? (
                  <>
                    <span className="w-3.5 h-3.5 border-2 border-slate-950 border-t-transparent rounded-full animate-spin" />
                    <span>{importMode === "url" ? "Menarik Data dari URL..." : "Mengimpor Data..."}</span>
                  </>
                ) : (
                  <>
                    <Upload className="w-4 h-4" />
                    <span>{importMode === "url" ? "Tarik & Impor dari URL" : "Mulai Impor File"}</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add Callbook Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-cyan-500/60 rounded-3xl max-w-md w-full p-5 space-y-4 shadow-2xl">
            <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
              <Plus className="w-4 h-4 text-cyan-400" />
              Daftarkan Callsign / DMR ID Baru
            </h3>

            {addError && (
              <div className="p-3 bg-rose-500/20 border border-rose-500/50 text-rose-300 rounded-xl text-xs flex items-center gap-2 font-mono">
                <span className="text-base">⚠️</span>
                <span>{addError}</span>
              </div>
            )}
            <form onSubmit={handleAddCallbook} className="space-y-3 text-xs font-mono">
              <div>
                <label className="block text-slate-400 mb-1">DMR ID (7 Digit)</label>
                <input
                  type="number"
                  required
                  placeholder="Contoh: 5104001"
                  value={newDmrId}
                  onChange={(e) => setNewDmrId(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Callsign / 10-28 Call</label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: YB1AAA atau JZ10XYZ"
                  value={newCallsign}
                  onChange={(e) => setNewCallsign(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Nama Operator</label>
                <input
                  type="text"
                  required
                  placeholder="Nama Lengkap"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="text-slate-400">Organisasi</label>
                    <button
                      type="button"
                      onClick={() => {
                        setAdminPinInput("");
                        setNewOrgName("");
                        setNewOrgCode("");
                        setNewOrgDesc("");
                        setOrgPinError(null);
                        setShowOrgPinModal(true);
                      }}
                      className="text-[11px] text-cyan-400 hover:text-cyan-300 underline font-mono flex items-center gap-0.5"
                    >
                      + Tambah (PIN)
                    </button>
                  </div>
                  <select
                    value={newOrg}
                    onChange={(e) => setNewOrg(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    {organizations.map((org) => (
                      <option key={org.code} value={org.code}>
                        {org.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1">Kota / QTH</label>
                  <input
                    type="text"
                    placeholder="Kota"
                    value={newCity}
                    onChange={(e) => setNewCity(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Default TalkGroup (TG)</label>
                <input
                  type="number"
                  placeholder="Contoh: 510"
                  value={newDefaultTg}
                  onChange={(e) => setNewDefaultTg(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-amber-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingAdd}
                  className="flex-1 py-2 bg-cyan-400 hover:bg-cyan-300 disabled:opacity-50 text-slate-950 rounded-xl font-heading font-black shadow-md flex items-center justify-center gap-2"
                >
                  {isSubmittingAdd ? "Menyimpan..." : "Simpan ID"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal Tambah Organisasi Admin PIN */}
      {showOrgPinModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0b101b] border border-cyan-500/40 rounded-2xl p-6 w-full max-w-md shadow-2xl relative">
            <h3 className="text-lg font-heading font-black text-white mb-1 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse" />
              Tambah Organisasi Baru
            </h3>
            <p className="text-xs text-slate-400 mb-4">
              Khusus Admin. Masukkan PIN Admin untuk mendaftarkan organisasi radio baru ke sistem.
            </p>

            {orgPinError && (
              <div className="mb-4 p-2.5 rounded-xl bg-red-950/60 border border-red-500/40 text-red-300 text-xs">
                ⚠️ {orgPinError}
              </div>
            )}

            <form onSubmit={handleCreateOrgWithPin} className="space-y-3.5 text-sm">
              <div>
                <label className="block text-slate-400 text-xs mb-1 font-mono">
                  PIN Admin <span className="text-rose-400">*</span>
                </label>
                <input
                  type="password"
                  required
                  autoFocus
                  placeholder="Masukkan PIN Admin (default: 1234)"
                  value={adminPinInput}
                  onChange={(e) => setAdminPinInput(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono tracking-widest focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 text-xs mb-1">
                  Nama Organisasi <span className="text-rose-400">*</span>
                </label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: SENKOM, POLRI, TNI, PMR"
                  value={newOrgName}
                  onChange={(e) => {
                    const val = e.target.value;
                    setNewOrgName(val);
                    setNewOrgCode(val.toUpperCase().replace(/\s+/g, "_"));
                  }}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 text-xs mb-1 font-mono">Kode Singkat</label>
                  <input
                    type="text"
                    placeholder="Contoh: SENKOM"
                    value={newOrgCode}
                    onChange={(e) => setNewOrgCode(e.target.value.toUpperCase())}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                  />
                </div>
                <div>
                  <label className="block text-slate-400 text-xs mb-1">Keterangan (Opsional)</label>
                  <input
                    type="text"
                    placeholder="Mitra Kamtibmas dll"
                    value={newOrgDesc}
                    onChange={(e) => setNewOrgDesc(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                  />
                </div>
              </div>

              <div className="flex gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setShowOrgPinModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700 transition"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingOrg}
                  className="flex-1 py-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-slate-950 font-heading font-black rounded-xl shadow-lg transition disabled:opacity-50"
                >
                  {isSubmittingOrg ? "Memverifikasi..." : "Simpan Organisasi"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal Edit Callbook Entry */}
      {showEditModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-cyan-500/60 rounded-3xl max-w-md w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-start justify-between border-b border-cyan-950 pb-2">
              <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
                <Edit2 className="w-4 h-4 text-cyan-400" />
                Edit Data Pengguna Callbook
              </h3>
              <button
                type="button"
                onClick={() => setShowEditModal(false)}
                className="text-slate-500 hover:text-white font-bold"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleSaveEdit} className="space-y-3 text-xs font-mono">
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 mb-1">DMR ID</label>
                  <input
                    type="number"
                    required
                    value={editDmrId}
                    onChange={(e) => setEditDmrId(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300 font-mono font-bold focus:border-cyan-400 outline-none"
                  />
                </div>
                <div>
                  <label className="block text-slate-400 mb-1">Callsign</label>
                  <input
                    type="text"
                    required
                    value={editCallsign}
                    onChange={(e) => setEditCallsign(e.target.value.toUpperCase())}
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Nama Operator</label>
                <input
                  type="text"
                  required
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white focus:border-cyan-400 outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-400 mb-1">Organisasi</label>
                  <select
                    value={editOrg}
                    onChange={(e) => setEditOrg(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    {organizations.map((org) => (
                      <option key={org.code} value={org.code}>
                        {org.name || org.code}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-slate-400 mb-1">Kota / QTH</label>
                  <input
                    type="text"
                    value={editCity}
                    onChange={(e) => setEditCity(e.target.value)}
                    className="w-full px-2 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Default TalkGroup (TG)</label>
                <input
                  type="number"
                  value={editDefaultTg}
                  onChange={(e) => setEditDefaultTg(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-amber-300 font-mono font-bold focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">Keterangan / Remarks</label>
                <input
                  type="text"
                  value={editRemarks}
                  onChange={(e) => setEditRemarks(e.target.value)}
                  placeholder="Catatan tambahan..."
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-slate-300 focus:border-cyan-400 outline-none"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">
                  Override Izin TG Khusus (Opsional)
                </label>
                <input
                  type="text"
                  value={editAllowedTgsOverride}
                  onChange={(e) => setEditAllowedTgsOverride(e.target.value)}
                  placeholder="Kosongkan = ikuti organisasi, atau isi: 510,5100"
                  className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-rose-300 font-mono focus:border-cyan-400 outline-none"
                />
                <span className="text-[10px] text-slate-500 mt-0.5 block">
                  Isi &apos;*&apos; untuk izinkan semua TG, atau daftar TG dipisah koma (misal: 510,5100).
                </span>
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowEditModal(false)}
                  className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2 bg-cyan-400 hover:bg-cyan-300 text-slate-950 rounded-xl font-heading font-black shadow-md"
                >
                  Simpan Perubahan
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal Ubah Masal (Batch Update) */}
      {showBatchModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-amber-500/60 rounded-3xl max-w-lg w-full p-6 space-y-4 shadow-2xl">
            <div className="flex items-start justify-between border-b border-amber-950/80 pb-3">
              <div>
                <h3 className="font-heading font-black text-lg text-white flex items-center gap-2">
                  <Sliders className="w-5 h-5 text-amber-400" />
                  Ubah Masal Callbook (Bulk Update)
                </h3>
                <p className="text-xs font-mono text-slate-400 mt-1">
                  Ubah Organisasi dan Default TG untuk sekelompok pengguna secara serentak
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowBatchModal(false)}
                className="text-slate-500 hover:text-white text-lg font-bold"
              >
                ✕
              </button>
            </div>

            <div className="space-y-3.5 text-xs font-mono">
              <div className="p-3 bg-amber-950/30 border border-amber-600/30 rounded-xl text-amber-200">
                💡 <strong>Tips:</strong> Anda bisa memindahkan seluruh 55 pengguna (awalan <code>BD</code>) dari <code>KOMUNITAS</code> ke organisasi <code>BRIDI</code> atau TG tertentu sekaligus.
              </div>

              {/* Filter Sasaran */}
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-slate-300 mb-1 font-bold">1. Filter Awalan Callsign</label>
                  <input
                    type="text"
                    value={batchPrefix}
                    onChange={(e) => setBatchPrefix(e.target.value.toUpperCase())}
                    placeholder="Contoh: BD (atau kosongkan untuk semua)"
                    className="w-full px-3 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-white font-mono uppercase focus:border-cyan-400 outline-none"
                  />
                </div>
                <div>
                  <label className="block text-slate-300 mb-1 font-bold">2. Dari Organisasi</label>
                  <select
                    value={batchFilterOrg}
                    onChange={(e) => setBatchFilterOrg(e.target.value)}
                    className="w-full px-2.5 py-2 bg-[#05080f] border border-cyan-900/80 rounded-xl text-cyan-300"
                  >
                    <option value="ALL">Semua Organisasi</option>
                    {organizations.map((org) => (
                      <option key={org.code} value={org.code}>
                        {org.name || org.code}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {/* Target Organisasi Baru */}
              <div>
                <label className="block text-slate-300 mb-1 font-bold">3. Ubah Menjadi Organisasi</label>
                <select
                  value={batchTargetOrg}
                  onChange={(e) => setBatchTargetOrg(e.target.value)}
                  className="w-full px-3 py-2 bg-[#05080f] border border-amber-600/60 rounded-xl text-amber-300 font-bold focus:border-amber-400 outline-none"
                >
                  {organizations.map((org) => (
                    <option key={org.code} value={org.code}>
                      {org.name || org.code} ({org.code})
                    </option>
                  ))}
                </select>
              </div>

              {/* Target Default TG Baru */}
              <div>
                <label className="block text-slate-300 mb-1 font-bold">4. Ubah Menjadi Default TG</label>
                <input
                  type="number"
                  value={batchTargetTg}
                  onChange={(e) => setBatchTargetTg(e.target.value)}
                  placeholder="Contoh: 510 atau 5109"
                  className="w-full px-3 py-2 bg-[#05080f] border border-amber-600/60 rounded-xl text-amber-300 font-bold font-mono focus:border-amber-400 outline-none"
                />
              </div>

              {/* Action Buttons */}
              <div className="flex gap-2 pt-2 border-t border-cyan-950">
                <button
                  type="button"
                  onClick={() => setShowBatchModal(false)}
                  className="flex-1 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl font-bold transition-all"
                >
                  Batal
                </button>
                <button
                  type="button"
                  disabled={isBatchLoading}
                  onClick={handleExecuteBatchUpdate}
                  className="flex-1 py-2.5 bg-amber-500 hover:bg-amber-400 text-slate-950 font-heading font-black rounded-xl shadow-lg shadow-amber-500/25 transition-all flex items-center justify-center gap-2"
                >
                  {isBatchLoading ? "Memproses..." : "Terapkan Perubahan Masal"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Modal Aturan TX & Firewall Organisasi */}
      {showFirewallModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0e1422] border-2 border-rose-500/60 rounded-3xl max-w-2xl w-full p-5 sm:p-6 space-y-4 shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="flex items-start justify-between border-b border-rose-950 pb-3">
              <div>
                <h3 className="font-heading font-black text-lg text-white flex items-center gap-2">
                  <ShieldAlert className="w-5 h-5 text-rose-400" />
                  ATURAN TX FIREWALL & IZIN TALKGROUP
                </h3>
                <p className="text-xs font-mono text-slate-400 mt-1">
                  Konfigurasi proteksi transmisi (TX) server-side per organisasi radio.
                </p>
              </div>
              <button
                type="button"
                onClick={() => {
                  setShowFirewallModal(false);
                  setEditingOrgCode(null);
                }}
                className="text-slate-500 hover:text-white font-bold text-lg p-1"
              >
                ✕
              </button>
            </div>

            {/* Info Notice */}
            <div className="p-3 bg-cyan-950/40 border border-cyan-800/60 rounded-xl text-xs font-mono text-cyan-300 space-y-1">
              <div className="font-bold flex items-center gap-1.5 text-cyan-200">
                <ShieldCheck className="w-4 h-4 text-cyan-400" />
                Kebijakan Transmit Radio (TX Firewall):
              </div>
              <p className="text-[11px] text-slate-300 leading-relaxed">
                Tanda <span className="font-bold text-amber-300 font-mono">*</span> mengizinkan TX di seluruh Talkgroup. Untuk membatasi TX, masukkan daftar angka TG dipisahkan koma (contoh: <span className="font-bold text-amber-300 font-mono">5100, 9990</span>). Transmit ke TG lain akan otomatis ditolak oleh server. TG 9990 (Echo Test) selalu diizinkan.
              </p>
            </div>

            {/* Alert Messages */}
            {firewallSuccess && (
              <div className="p-3 bg-emerald-950/60 border border-emerald-800 rounded-xl text-xs font-mono text-emerald-300 flex items-center gap-2 animate-pulse">
                <Check className="w-4 h-4 text-emerald-400" />
                <span>{firewallSuccess}</span>
              </div>
            )}
            {firewallError && (
              <div className="p-3 bg-rose-950/60 border border-rose-800 rounded-xl text-xs font-mono text-rose-300 flex items-center gap-2">
                <AlertCircle className="w-4 h-4 text-rose-400" />
                <span>{firewallError}</span>
              </div>
            )}

            {/* Form Edit Aturan Tertentu */}
            {editingOrgCode ? (
              <form onSubmit={handleSaveFirewallRule} className="p-4 bg-[#070b14] border border-rose-900/60 rounded-2xl space-y-3 font-mono text-xs">
                <div className="flex items-center justify-between border-b border-rose-900/40 pb-2">
                  <span className="font-bold text-rose-300 text-sm">
                    Ubah Kebijakan TX: {editingOrgCode}
                  </span>
                  <button
                    type="button"
                    onClick={() => setEditingOrgCode(null)}
                    className="text-slate-400 hover:text-white text-xs underline"
                  >
                    Kembali ke Daftar
                  </button>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-bold">
                    PIN Admin <span className="text-rose-400">*</span>
                  </label>
                  <input
                    type="password"
                    required
                    autoFocus
                    placeholder="Masukkan PIN Admin (default: 1234)"
                    value={firewallPin}
                    onChange={(e) => setFirewallPin(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-rose-900/80 rounded-xl text-white tracking-widest focus:border-rose-400 outline-none"
                  />
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-bold">
                    Talkgroup yang Diizinkan (Allowed TGs)
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="Contoh: * atau 5100, 9990"
                    value={ruleAllowedTgs}
                    onChange={(e) => setRuleAllowedTgs(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-rose-900/80 rounded-xl text-amber-300 font-bold focus:border-rose-400 outline-none"
                  />
                  <span className="text-[10px] text-slate-500 mt-0.5 block">
                    Gunakan &apos;*&apos; untuk tanpa batas, atau cantumkan daftar TG dipisah koma (misal: 5100, 9990).
                  </span>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1 font-bold">
                    Awalan Callsign Otomatis (Prefixes)
                  </label>
                  <input
                    type="text"
                    placeholder="Contoh: BD,BD62 atau YB,YC atau *"
                    value={ruleCallsignPrefixes}
                    onChange={(e) => setRuleCallsignPrefixes(e.target.value)}
                    className="w-full px-3 py-2 bg-[#05080f] border border-rose-900/80 rounded-xl text-cyan-300 font-mono font-bold focus:border-rose-400 outline-none uppercase"
                  />
                  <span className="text-[10px] text-slate-500 mt-0.5 block">
                    Daftar awalan callsign untuk routing otomatis ke organisasi ini, dipisah koma (misal: BD,BD62 atau * untuk umum).
                  </span>
                </div>

                <div className="flex items-center gap-3 pt-1">
                  <label className="flex items-center gap-2 cursor-pointer text-slate-300">
                    <input
                      type="checkbox"
                      checked={ruleAllowTx}
                      onChange={(e) => setRuleAllowTx(e.target.checked)}
                      className="w-4 h-4 rounded text-rose-500 bg-[#05080f] border-rose-800"
                    />
                    <span className="font-bold">Izinkan Organisasi ini Memancar (TX Enabled)</span>
                  </label>
                </div>

                <div className="flex gap-2 pt-2 border-t border-rose-900/40">
                  <button
                    type="button"
                    onClick={() => setEditingOrgCode(null)}
                    className="flex-1 py-2 bg-slate-800 text-slate-300 rounded-xl font-bold hover:bg-slate-700"
                  >
                    Batal
                  </button>
                  <button
                    type="submit"
                    disabled={isSavingFirewall}
                    className="flex-1 py-2 bg-rose-600 hover:bg-rose-500 disabled:opacity-50 text-white font-heading font-black rounded-xl shadow-lg shadow-rose-600/30"
                  >
                    {isSavingFirewall ? "Menyimpan..." : "Simpan Kebijakan TX"}
                  </button>
                </div>
              </form>
            ) : (
              /* Table Rules List */
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs font-mono text-slate-400 px-1">
                  <span>Daftar Organisasi & Kebijakan TX Aktif:</span>
                  <button
                    type="button"
                    onClick={fetchFirewallRules}
                    className="text-cyan-400 hover:text-cyan-300 underline"
                  >
                    Muat Ulang
                  </button>
                </div>

                {isFirewallLoading ? (
                  <div className="p-8 text-center text-xs font-mono text-slate-500">
                    Memuat aturan TX Firewall...
                  </div>
                ) : (
                  <div className="space-y-2">
                    {firewallRules.map((rule) => (
                      <div
                        key={rule.code}
                        className="p-3.5 bg-[#05080f] border border-cyan-950 hover:border-cyan-800 rounded-xl flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 font-mono text-xs transition-all"
                      >
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-white text-sm">
                              {rule.name || rule.code}
                            </span>
                            <span className="px-2 py-0.5 rounded text-[10px] bg-slate-800 text-slate-300 font-bold">
                              {rule.code}
                            </span>
                            {rule.allow_tx ? (
                              <span className="px-2 py-0.5 rounded text-[10px] bg-emerald-950 text-emerald-300 border border-emerald-800 font-bold">
                                TX AKTIF
                              </span>
                            ) : (
                              <span className="px-2 py-0.5 rounded text-[10px] bg-rose-950 text-rose-300 border border-rose-800 font-bold">
                                TX DIBLOKIR
                              </span>
                            )}
                          </div>
                          <div className="text-[11px] text-slate-400 flex items-center gap-2 flex-wrap">
                            <span>
                              Izin TG:{" "}
                              {rule.allowed_tgs === "*" ? (
                                <span className="text-cyan-300 font-bold">Semua Talkgroup (*)</span>
                              ) : (
                                <span className="text-amber-300 font-bold">TG {rule.allowed_tgs}</span>
                              )}
                            </span>
                            {rule.callsign_prefixes && (
                              <span className="px-1.5 py-0.5 rounded bg-slate-900 border border-slate-800 text-[10px] text-cyan-400 font-mono">
                                Prefiks: {rule.callsign_prefixes}
                              </span>
                            )}
                          </div>
                        </div>

                        <button
                          type="button"
                          onClick={() => handleStartEditRule(rule)}
                          className="px-3 py-1.5 bg-slate-900 hover:bg-slate-800 border border-slate-700 hover:border-cyan-500 text-slate-300 hover:text-cyan-300 text-xs font-bold rounded-lg flex items-center gap-1.5 transition-all self-end sm:self-center"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          <span>Ubah Aturan</span>
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* EXPORT USER.JSON PER GROUP MODAL */}
      {showExportModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0b121e] border border-emerald-900/60 rounded-2xl p-5 max-w-2xl w-full space-y-4 shadow-2xl animate-in fade-in zoom-in-95 duration-200">
            <div className="flex items-center justify-between border-b border-emerald-900/40 pb-3">
              <div>
                <h3 className="font-heading font-black text-base text-white flex items-center gap-2">
                  <Download className="w-5 h-5 text-emerald-400" />
                  UNDUH USER.JSON PER GROUP CALLSIGN
                </h3>
                <p className="text-xs font-mono text-slate-400 mt-0.5">
                  Format standar RadioID / DVSwitch untuk CPS AnyTone, OpenGD77, DVSwitch Mobile, atau server DMR.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowExportModal(false)}
                className="text-slate-400 hover:text-white font-mono text-lg p-1"
              >
                ✕
              </button>
            </div>

            <div className="space-y-2.5 max-h-[60vh] overflow-y-auto pr-1">
              {[
                ...organizations.map((org) => {
                  const slug = (org.code || "").toLowerCase().replace(/[^a-z0-9_-]/g, "");
                  return {
                    code: org.code,
                    name: org.name || org.code,
                    desc: org.description || `Database Callbook ${org.name || org.code}`,
                    file: `${slug}.user.json`,
                    csvFile: `${slug}.user.csv`,
                    datFile: `${slug}.DMRIds.dat`,
                    color: "border-cyan-800/80 bg-cyan-950/20 text-cyan-300"
                  };
                }),
                {
                  code: "ALL",
                  name: "Semua Pengguna (Global Database)",
                  desc: "Seluruh pengguna terdaftar di NinjaRadio",
                  file: "all.user.json",
                  csvFile: "all.user.csv",
                  datFile: "all.DMRIds.dat",
                  color: "border-slate-700 bg-slate-900/60 text-white"
                },
              ].map((item) => {
                const jsonPath = `/callbook/${item.file}`;
                const csvPath = `/callbook/${item.csvFile}`;
                const datPath = `/callbook/${item.datFile}`;
                const isCopied = copiedUrl === jsonPath;

                return (
                  <div
                    key={item.code}
                    className={`p-3.5 rounded-xl border flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 transition-all ${item.color}`}
                  >
                    <div className="space-y-0.5">
                      <div className="flex items-center gap-2">
                        <span className="font-mono font-bold text-sm">{item.name}</span>
                        <code className="text-[10px] px-1.5 py-0.5 rounded bg-black/40 font-mono">
                          {item.file}
                        </code>
                      </div>
                      <p className="text-[11px] font-mono text-slate-400">{item.desc}</p>
                    </div>

                    <div className="flex items-center gap-1.5 self-end sm:self-center flex-wrap">
                      <a
                        href={jsonPath}
                        target="_blank"
                        rel="noreferrer"
                        className="px-2.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-slate-950 font-mono text-xs font-bold flex items-center gap-1 transition-all shadow"
                        title="Buka / Unduh JSON (Z3DMR, Droid-Star)"
                      >
                        <Download className="w-3.5 h-3.5" />
                        <span>JSON</span>
                      </a>

                      <a
                        href={csvPath}
                        target="_blank"
                        rel="noreferrer"
                        className="px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-600 font-mono text-xs font-bold flex items-center gap-1 transition-all"
                        title="Unduh CSV untuk CPS Radio AnyTone / OpenGD77"
                      >
                        <span>CSV</span>
                      </a>

                      <a
                        href={datPath}
                        target="_blank"
                        rel="noreferrer"
                        className="px-2.5 py-1.5 rounded-lg bg-indigo-950 hover:bg-indigo-900 text-indigo-300 border border-indigo-700/60 font-mono text-xs font-bold flex items-center gap-1 transition-all"
                        title="Unduh DMRIds.dat untuk Hotspot Pi-Star / MMDVMHost"
                      >
                        <span>Pi-Star (.dat)</span>
                      </a>

                      <button
                        type="button"
                        onClick={() => handleCopyUrl(jsonPath)}
                        className={`px-2.5 py-1.5 rounded-lg font-mono text-xs font-bold flex items-center gap-1 transition-all border ${
                          isCopied
                            ? "bg-cyan-500 text-slate-950 border-cyan-400"
                            : "bg-slate-900 hover:bg-slate-800 text-cyan-300 border-cyan-800/80"
                        }`}
                        title="Salin Full URL"
                      >
                        {isCopied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                        <span>{isCopied ? "Tersalin!" : "Salin Link"}</span>
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="pt-2 border-t border-slate-800 flex justify-end">
              <button
                type="button"
                onClick={() => setShowExportModal(false)}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded-xl text-xs font-mono font-bold"
              >
                Tutup
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MODAL: BUKA KUNCI PIN ADMIN CALLBOOK */}
      {showGlobalPinModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0b121e] border-2 border-amber-500/60 rounded-3xl max-w-sm w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 rounded-lg bg-amber-500/20 border border-amber-500 flex items-center justify-center text-amber-400">
                  <Lock className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="font-bold text-sm text-white font-heading">PIN Admin Server</h3>
                  <p className="text-[10px] text-slate-400 font-mono">Buka DMR ID asli & hak edit Callbook</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setShowGlobalPinModal(false); setGlobalPinError(""); }}
                className="p-1 rounded-lg text-gray-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (!globalPinInput.trim()) return;
                setIsVerifyingGlobalPin(true);
                setGlobalPinError("");
                const ok = await verifyAdminPin(globalPinInput.trim());
                setIsVerifyingGlobalPin(false);
                if (ok) {
                  setShowGlobalPinModal(false);
                  setGlobalPinInput("");
                  fetchCallbook();
                  beepPlayer?.playRogerBeep();
                } else {
                  setGlobalPinError("PIN Admin salah! Akses ditolak.");
                }
              }}
              className="space-y-3"
            >
              <div>
                <label className="block text-xs text-slate-300 font-mono mb-1">
                  Masukkan PIN Admin Server
                </label>
                <input
                  type="password"
                  required
                  autoFocus
                  placeholder="PIN Admin"
                  value={globalPinInput}
                  onChange={(e) => {
                    setGlobalPinInput(e.target.value);
                    setGlobalPinError("");
                  }}
                  className="w-full px-3 py-2.5 bg-[#05080f] border border-slate-700 focus:border-amber-400 rounded-xl text-center text-white font-mono tracking-widest text-lg font-bold"
                />
              </div>

              {globalPinError && (
                <div className="text-rose-400 text-xs font-mono text-center flex items-center justify-center gap-1">
                  <AlertCircle className="w-3.5 h-3.5" />
                  <span>{globalPinError}</span>
                </div>
              )}

              <div className="flex items-center gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => { setShowGlobalPinModal(false); setGlobalPinError(""); }}
                  className="flex-1 py-2 rounded-xl bg-slate-800 text-slate-300 text-xs font-mono font-bold hover:bg-slate-700"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isVerifyingGlobalPin || !globalPinInput.trim()}
                  className="flex-1 py-2 rounded-xl bg-amber-500 hover:bg-amber-400 text-slate-950 text-xs font-mono font-bold transition disabled:opacity-50"
                >
                  {isVerifyingGlobalPin ? "Memeriksa..." : "Buka Kunci"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
