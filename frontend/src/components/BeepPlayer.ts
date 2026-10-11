class BeepPlayer {
  private ctx: AudioContext | null = null;

  private getContext(): AudioContext {
    if (!this.ctx) {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      this.ctx = new AudioCtx();
    }
    if (this.ctx.state === "suspended") {
      this.ctx.resume();
    }
    return this.ctx;
  }

  // DMR Talk Grant Tone (3 quick high pitch beeps)
  playTalkGrant() {
    try {
      const ctx = this.getContext();
      const now = ctx.currentTime;
      
      const freqs = [800, 1000, 1200];
      freqs.forEach((freq, index) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = "sine";
        osc.frequency.setValueAtTime(freq, now + index * 0.04);
        
        gain.gain.setValueAtTime(0.15, now + index * 0.04);
        gain.gain.exponentialRampToValueAtTime(0.01, now + (index + 1) * 0.04);
        
        osc.connect(gain);
        gain.connect(ctx.destination);
        
        osc.start(now + index * 0.04);
        osc.stop(now + (index + 1) * 0.04);
      });
    } catch {
      // Audio context might be blocked prior to user interaction
    }
  }

  // Classic DMR Roger Beep on transmission release
  playRogerBeep() {
    try {
      const ctx = this.getContext();
      const now = ctx.currentTime;
      
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.setValueAtTime(880, now);
      osc.frequency.setValueAtTime(1320, now + 0.08);
      
      gain.gain.setValueAtTime(0.18, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.18);
      
      osc.connect(gain);
      gain.connect(ctx.destination);
      
      osc.start(now);
      osc.stop(now + 0.18);
    } catch {
      // Ignore
    }
  }

  // Error / Channel Busy Tone
  playErrorTone() {
    try {
      const ctx = this.getContext();
      const now = ctx.currentTime;
      
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sawtooth";
      osc.frequency.setValueAtTime(300, now);
      
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);
      
      osc.connect(gain);
      gain.connect(ctx.destination);
      
      osc.start(now);
      osc.stop(now + 0.25);
    } catch {
      // Ignore
    }
  }
}

export const beepPlayer = typeof window !== "undefined" ? new BeepPlayer() : null;
