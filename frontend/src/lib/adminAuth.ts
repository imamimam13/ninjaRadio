"use client";

export const ADMIN_AUTH_EVENT = "ninjaradio_admin_auth_changed";
const ADMIN_UNLOCKED_KEY = "ninjaradio_admin_unlocked";
const ADMIN_PIN_KEY = "ninjaradio_admin_pin";

export function getIsAdminUnlocked(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return localStorage.getItem(ADMIN_UNLOCKED_KEY) === "true";
  } catch {
    return false;
  }
}

export function setAdminUnlocked(unlocked: boolean): void {
  if (typeof window === "undefined") return;
  try {
    if (unlocked) {
      localStorage.setItem(ADMIN_UNLOCKED_KEY, "true");
    } else {
      localStorage.removeItem(ADMIN_UNLOCKED_KEY);
    }
    window.dispatchEvent(new Event(ADMIN_AUTH_EVENT));
  } catch (e) {
    console.error("Failed to update admin auth state:", e);
  }
}

export async function verifyAdminPin(pin: string): Promise<boolean> {
  const trimmed = (pin || "").trim();
  if (!trimmed) return false;

  // 1. Attempt backend verification
  try {
    const res = await fetch("/api/auth/verify-pin", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin: trimmed }),
    });
    if (res.ok) {
      const data = await res.json();
      if (data.valid === true) {
        setAdminUnlocked(true);
        return true;
      }
    }
  } catch (err) {
    console.warn("Backend PIN verification unreachable, trying local fallback:", err);
  }

  // 2. Client-side fallback check (stored PIN or default)
  const storedPin = (typeof window !== "undefined" && localStorage.getItem(ADMIN_PIN_KEY)) || "";
  const validPins = ["1234", "admin", storedPin].filter(Boolean);
  if (validPins.includes(trimmed)) {
    setAdminUnlocked(true);
    return true;
  }

  return false;
}
