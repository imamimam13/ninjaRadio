import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "NinjaRadio - Multi-Mode Digital & Analog Tactical Voice Platform",
  description: "Cross-Network DMR, Analog, CB, YSF, P25, Mumble VoIP & Cable Gateway",
  manifest: "/manifest.json",
  icons: {
    icon: "/icon-192.png",
    apple: "/icon-192.png",
  },
};

export const viewport: Viewport = {
  themeColor: "#00f0ff",
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <head>
        <link rel="manifest" href="/manifest.json" />
        <meta name="apple-mobile-web-app-capable" content="yes" />
        <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
      </head>
      <body className="min-h-screen bg-ninja-dark text-slate-100 antialiased selection:bg-ninja-cyan selection:text-ninja-dark">
        {children}
      </body>
    </html>
  );
}
