"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function AdminIndexPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace("/admin/dashboard");
  }, [router]);

  return (
    <div className="min-h-screen bg-[#070b14] flex items-center justify-center text-slate-400 font-mono text-xs">
      Mengarahkan ke Admin Dashboard...
    </div>
  );
}
