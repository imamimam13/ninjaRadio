"use client";

import React, { useEffect, useRef, useState } from "react";

interface AudioVisualizerProps {
  isActive: boolean;
  color?: string;
  barsCount?: number;
  mode?: "bars" | "oscilloscope" | "smeter";
  rssiValue?: string;
  analyserNode?: AnalyserNode | null;
}

export const AudioVisualizer: React.FC<AudioVisualizerProps> = React.memo(({
  isActive,
  color = "bg-cyan-400",
  barsCount = 14,
  mode = "bars",
  rssiValue = "Digital IP",
  analyserNode = null
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [heights, setHeights] = useState<number[]>(new Array(barsCount).fill(3));
  const [peakHold, setPeakHold] = useState<number[]>(new Array(barsCount).fill(3));
  const [sMeterLevel, setSMeterLevel] = useState<number>(0);

  // Real-time Oscilloscope Canvas driven by Web Audio AnalyserNode
  useEffect(() => {
    if (mode !== "oscilloscope" || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animationFrameId: number;
    const timeBuf = analyserNode ? new Uint8Array(analyserNode.fftSize) : null;

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

      if (isActive && analyserNode && timeBuf) {
        analyserNode.getByteTimeDomainData(timeBuf);
        const step = Math.max(1, Math.floor(timeBuf.length / 64));
        const points = Math.floor(timeBuf.length / step);
        const sliceWidth = canvas.width / Math.max(1, points - 1);
        let x = 0;
        for (let i = 0; i < points; i++) {
          const sample = (timeBuf[i * step] - 128) / 128.0; // -1.0 .. +1.0
          const y = canvas.height / 2 + Math.max(-0.95, Math.min(0.95, sample * 1.4)) * (canvas.height * 0.44);
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
          x += sliceWidth;
        }
      } else {
        ctx.moveTo(0, canvas.height / 2);
        ctx.lineTo(canvas.width, canvas.height / 2);
      }

      ctx.stroke();
      if (isActive) {
        animationFrameId = requestAnimationFrame(render);
      }
    };

    render();
    return () => {
      if (animationFrameId) cancelAnimationFrame(animationFrameId);
    };
  }, [isActive, mode, analyserNode]);

  // Real-time VU Spectrum Bars driven by Web Audio AnalyserNode FFT
  useEffect(() => {
    if (mode !== "bars") return;
    if (!isActive) {
      setHeights(new Array(barsCount).fill(3));
      setPeakHold(new Array(barsCount).fill(3));
      return;
    }

    const freqBuf = analyserNode ? new Uint8Array(analyserNode.frequencyBinCount) : null;
    const timeBuf = analyserNode ? new Uint8Array(analyserNode.fftSize) : null;
    let rafId: number;
    let lastUpdate = 0;

    const tick = (ts: number) => {
      if (ts - lastUpdate >= 45) {
        lastUpdate = ts;
        const next = new Array(barsCount).fill(3);
        if (analyserNode && freqBuf && timeBuf) {
          analyserNode.getByteFrequencyData(freqBuf);
          // Focus on voice spectrum bins (0..40% of Nyquist)
          const usableBins = Math.max(barsCount, Math.floor(freqBuf.length * 0.45));
          const binsPerBar = Math.max(1, Math.floor(usableBins / barsCount));
          for (let b = 0; b < barsCount; b++) {
            let sum = 0;
            const start = b * binsPerBar;
            for (let k = 0; k < binsPerBar; k++) {
              sum += freqBuf[Math.min(freqBuf.length - 1, start + k)];
            }
            const avg = sum / binsPerBar; // 0..255
            next[b] = Math.max(3, Math.min(26, Math.round((avg / 220) * 26)));
          }
        }
        setHeights(next);
        setPeakHold((peaks) => peaks.map((p, idx) => Math.max(3, Math.max(p - 1, next[idx]))));
      }
      rafId = requestAnimationFrame(tick);
    };

    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [isActive, barsCount, mode, analyserNode]);

  // Real-time S-Meter level from numeric RSSI dBm + live audio RMS modulation
  useEffect(() => {
    if (mode !== "smeter") return;
    if (!isActive) {
      setSMeterLevel(0);
      return;
    }
    const timeBuf = analyserNode ? new Uint8Array(analyserNode.fftSize) : null;
    let rafId: number;
    let lastUpdate = 0;

    const dbmMatch = /-?\d+/.exec(rssiValue || "");
    const baseDbm = dbmMatch ? parseInt(dbmMatch[0], 10) : null;

    const tick = (ts: number) => {
      if (ts - lastUpdate >= 60) {
        lastUpdate = ts;
        let rms = 0;
        if (analyserNode && timeBuf) {
          analyserNode.getByteTimeDomainData(timeBuf);
          let sumSq = 0;
          for (let i = 0; i < timeBuf.length; i++) {
            const v = (timeBuf[i] - 128) / 128.0;
            sumSq += v * v;
          }
          rms = Math.sqrt(sumSq / timeBuf.length);
        }
        if (baseDbm !== null && baseDbm < 0) {
          // Map -127 dBm .. -45 dBm to 1..8 segments, with slight live modulation
          const norm = Math.max(0, Math.min(1, (baseDbm + 125) / 80));
          const segs = Math.max(1, Math.min(8, Math.round(norm * 7 + Math.min(1, rms * 3))));
          setSMeterLevel(segs);
        } else {
          // Digital IP: reflect live speech modulation envelope
          const segs = Math.max(1, Math.min(8, Math.round(1 + Math.min(1, rms * 14) * 7)));
          setSMeterLevel(segs);
        }
      }
      rafId = requestAnimationFrame(tick);
    };

    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [isActive, mode, rssiValue, analyserNode]);

  // S-METER MODE
  if (mode === "smeter") {
    const segments = [
      { label: "S1", color: "bg-emerald-500", on: isActive && sMeterLevel >= 1 },
      { label: "S3", color: "bg-emerald-500", on: isActive && sMeterLevel >= 2 },
      { label: "S5", color: "bg-emerald-400", on: isActive && sMeterLevel >= 3 },
      { label: "S7", color: "bg-emerald-400", on: isActive && sMeterLevel >= 4 },
      { label: "S9", color: "bg-cyan-400", on: isActive && sMeterLevel >= 5 },
      { label: "+20", color: "bg-amber-400", on: isActive && sMeterLevel >= 6 },
      { label: "+40", color: "bg-amber-500", on: isActive && sMeterLevel >= 7 },
      { label: "+60", color: "bg-rose-500", on: isActive && sMeterLevel >= 8 },
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
});
