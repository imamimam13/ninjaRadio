"use client";

import React, { useEffect, useRef, useState } from "react";

interface AudioVisualizerProps {
  isActive: boolean;
  color?: string;
  barsCount?: number;
  mode?: "bars" | "oscilloscope" | "smeter";
  rssiValue?: string;
}

export const AudioVisualizer: React.FC<AudioVisualizerProps> = ({
  isActive,
  color = "bg-cyan-400",
  barsCount = 14,
  mode = "bars",
  rssiValue = "-68 dBm"
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [heights, setHeights] = useState<number[]>(new Array(barsCount).fill(4));
  const [peakHold, setPeakHold] = useState<number[]>(new Array(barsCount).fill(4));

  // Oscilloscope Canvas animation
  useEffect(() => {
    if (mode !== "oscilloscope" || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animationFrameId: number;
    let phase = 0;

    const render = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      // Grid lines
      ctx.strokeStyle = "rgba(0, 240, 255, 0.08)";
      ctx.lineWidth = 1;
      for (let x = 0; x < canvas.width; x += 15) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, canvas.height);
        ctx.stroke();
      }
      ctx.beginPath();
      ctx.moveTo(0, canvas.height / 2);
      ctx.lineTo(canvas.width, canvas.height / 2);
      ctx.stroke();

      // Waveform
      ctx.beginPath();
      ctx.lineWidth = 2;
      ctx.strokeStyle = isActive ? "#00f0ff" : "rgba(0, 240, 255, 0.25)";
      ctx.shadowColor = isActive ? "#00f0ff" : "transparent";
      ctx.shadowBlur = isActive ? 8 : 0;

      const sliceWidth = canvas.width / 60;
      let x = 0;

      for (let i = 0; i < 60; i++) {
        const amplitude = isActive ? Math.sin(i * 0.2 + phase) * 12 + (Math.random() - 0.5) * 6 : Math.sin(i * 0.1) * 2;
        const y = canvas.height / 2 + amplitude;

        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);

        x += sliceWidth;
      }

      ctx.stroke();
      phase += isActive ? 0.15 : 0.02;
      animationFrameId = requestAnimationFrame(render);
    };

    render();
    return () => cancelAnimationFrame(animationFrameId);
  }, [isActive, mode]);

  // Bars Animation
  useEffect(() => {
    if (mode !== "bars") return;
    if (!isActive) {
      setHeights(new Array(barsCount).fill(3));
      setPeakHold(new Array(barsCount).fill(3));
      return;
    }

    const interval = setInterval(() => {
      setHeights((prev) => {
        const next = new Array(barsCount).fill(0).map(() => Math.floor(Math.random() * 24) + 4);
        setPeakHold((peaks) => peaks.map((p, idx) => Math.max(p - 1, next[idx])));
        return next;
      });
    }, 80);

    return () => clearInterval(interval);
  }, [isActive, barsCount, mode]);

  // S-METER MODE
  if (mode === "smeter") {
    const segments = [
      { label: "S1", color: "bg-emerald-500", on: isActive },
      { label: "S3", color: "bg-emerald-500", on: isActive },
      { label: "S5", color: "bg-emerald-400", on: isActive },
      { label: "S7", color: "bg-emerald-400", on: isActive },
      { label: "S9", color: "bg-cyan-400", on: isActive },
      { label: "+20", color: "bg-amber-400", on: isActive },
      { label: "+40", color: "bg-amber-500", on: isActive },
      { label: "+60", color: "bg-rose-500", on: isActive },
    ];

    return (
      <div className="flex flex-col gap-1 p-2 bg-[#05080c] border border-cyan-900/40 rounded-xl font-mono text-[10px]">
        <div className="flex items-center justify-between text-gray-400">
          <span className="text-cyan-400 font-bold">SIGNAL METER</span>
          <span className="text-emerald-400 font-bold">{isActive ? rssiValue : "STANDBY"}</span>
        </div>
        <div className="flex items-center gap-1">
          {segments.map((seg, i) => (
            <div key={i} className="flex-1 flex flex-col items-center gap-0.5">
              <div
                className={`w-full h-3.5 rounded-sm transition-all duration-75 ${
                  seg.on ? `${seg.color} shadow-[0_0_6px_currentColor]` : "bg-slate-800/60"
                }`}
              />
              <span className="text-[8px] text-gray-500">{seg.label}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // OSCILLOSCOPE CANVAS MODE
  if (mode === "oscilloscope") {
    return (
      <div className="relative rounded-xl overflow-hidden border border-cyan-500/30 bg-[#05080d] p-1 shadow-inner shadow-cyan-950/50">
        <canvas ref={canvasRef} width={220} height={45} className="w-full h-[45px] block" />
        <div className="absolute top-1 left-2 text-[9px] font-mono font-bold text-cyan-400/80">
          RF OSCILLOSCOPE
        </div>
      </div>
    );
  }

  // CLASSIC LED VU BARS MODE
  return (
    <div className="flex items-end justify-center gap-[3px] h-7 px-2.5 py-1 bg-[#05080e] rounded-lg border border-cyan-900/40 shadow-inner">
      {heights.map((height, i) => (
        <div key={i} className="relative w-1.5 h-full flex items-end">
          <div
            className={`w-full rounded-sm transition-all duration-75 ${
              isActive ? color : "bg-slate-800/80"
            }`}
            style={{ height: `${height}px` }}
          />
          {isActive && (
            <div
              className="absolute w-full h-[2px] bg-white shadow-[0_0_4px_#fff]"
              style={{ bottom: `${peakHold[i]}px` }}
            />
          )}
        </div>
      ))}
    </div>
  );
};
