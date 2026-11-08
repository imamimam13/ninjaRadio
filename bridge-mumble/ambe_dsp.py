import math
import struct
from typing import Union, Optional

try:
    import numpy as np
except ImportError:
    np = None

try:
    from scipy import signal as scipy_signal
except ImportError:
    scipy_signal = None


# Pre-computed 5th-order Butterworth Band-Pass (300 Hz - 3300 Hz @ fs=8000 Hz)
# Second-Order Sections (SOS) [b0, b1, b2, 1.0, a1, a2] for zero-overhead & fallback operation.
_DEFAULT_BPF_SOS_8K = [
    [0.52762425, 1.05524850, 0.52762425, 1.0, 0.90992999, 0.25963459],
    [1.00000000, 2.00000000, 1.00000000, 1.0, 1.19655318, 0.43635069],
    [1.00000000, 0.00000000, -1.00000000, 1.0, 0.57724052, 0.85948169],
    [1.00000000, -2.00000000, 1.00000000, 1.0, -1.66066202, 0.71065018],
    [1.00000000, -2.00000000, 1.00000000, 1.0, -1.87407755, 0.89629258],
]


class AmbeAudioConditioner:
    """
    Low-latency, stateful in-memory DSP conditioning pipeline for AMBE+2 vocoders.

    Pure PCM data-in / data-out audio pipe:
      1. High-quality Polyphase Downsampling (48000 Hz / 16000 Hz -> 8000 Hz)
      2. Stateful Butterworth Band-Pass Filter (300 Hz - 3300 Hz, order 4 or 5)
      3. RMS Noise Gate (Squelch to absolute zero to eliminate AMBE digital wind noise)
      4. Soft Peak Limiter / Anti-Clipping AGC (capped at safe 16-bit ceiling, default 24000)
    """

    TARGET_SAMPLE_RATE = 8000

    def __init__(
        self,
        input_rate: int = 48000,
        lowcut_hz: float = 300.0,
        highcut_hz: float = 3300.0,
        filter_order: int = 5,
        gate_threshold_rms: float = 160.0,
        peak_ceiling: float = 24000.0,
        pre_gain: float = 1.0,
    ) -> None:
        if input_rate <= 0:
            raise ValueError(f"Invalid input_rate: {input_rate}")
        if not (0.0 < lowcut_hz < highcut_hz < (self.TARGET_SAMPLE_RATE / 2.0)):
            raise ValueError(
                f"Cutoffs must satisfy 0 < lowcut ({lowcut_hz}) < highcut ({highcut_hz}) < 4000 Hz"
            )

        self.input_rate = int(input_rate)
        self.lowcut_hz = float(lowcut_hz)
        self.highcut_hz = float(highcut_hz)
        self.filter_order = int(filter_order)
        self.gate_threshold_rms = float(gate_threshold_rms)
        self.peak_ceiling = float(min(peak_ceiling, 32767.0))
        self.pre_gain = float(pre_gain)

        # 1. Pre-compute polyphase resampling ratio (up / down)
        gcd = math.gcd(self.TARGET_SAMPLE_RATE, self.input_rate)
        self._up = self.TARGET_SAMPLE_RATE // gcd
        self._down = self.input_rate // gcd
        self._needs_resample = self.input_rate != self.TARGET_SAMPLE_RATE

        # Pre-compute anti-aliasing FIR window & Butterworth coefficients
        self._resample_window = None
        self.b = None
        self.a = None
        self._sos = None
        self._zi = None

        if scipy_signal is not None and np is not None:
            if self._needs_resample:
                max_rate = max(self._up, self._down)
                f_c = 1.0 / max_rate
                half_len = 10 * max_rate
                self._resample_window = (
                    scipy_signal.firwin(2 * half_len + 1, f_c, window=("kaiser", 5.0)).astype(
                        np.float32
                    )
                    * self._up
                )

            nyquist = 0.5 * self.TARGET_SAMPLE_RATE
            low = self.lowcut_hz / nyquist
            high = self.highcut_hz / nyquist
            self.b, self.a = scipy_signal.butter(
                self.filter_order, [low, high], btype="bandpass", output="ba"
            )
            self._sos = scipy_signal.butter(
                self.filter_order, [low, high], btype="bandpass", output="sos"
            ).astype(np.float32)
            self._zi = np.zeros((self._sos.shape[0], 2), dtype=np.float32)
        else:
            # Fallback pre-computed SOS & polyphase FIR taps if scipy is not installed in environment
            self._sos_list = [list(row) for row in _DEFAULT_BPF_SOS_8K]
            self._zi_list = [[0.0, 0.0] for _ in self._sos_list]
            if np is not None and self._needs_resample:
                self._fallback_fir = self._build_sinc_kaiser_fir(self._up, self._down)

    @staticmethod
    def _build_sinc_kaiser_fir(up: int, down: int):
        """Builds a windowed-sinc anti-aliasing FIR kernel using pure numpy."""
        max_rate = max(up, down)
        f_c = 1.0 / max_rate
        half_len = 8 * max_rate
        n = np.arange(-half_len, half_len + 1, dtype=np.float32)
        h = np.sinc(f_c * n) * np.kaiser(2 * half_len + 1, 5.0).astype(np.float32)
        h *= f_c * up
        return h.astype(np.float32)

    def reset(self) -> None:
        """Reset stateful filter delay lines on PTT unkey."""
        if self._zi is not None:
            self._zi.fill(0.0)
        if hasattr(self, "_zi_list"):
            for st in self._zi_list:
                st[0] = 0.0
                st[1] = 0.0

    def set_input_rate(self, input_rate: int) -> None:
        """Dynamically reconfigure input sample rate if audio source changes."""
        if int(input_rate) != self.input_rate:
            self.__init__(
                input_rate=int(input_rate),
                lowcut_hz=self.lowcut_hz,
                highcut_hz=self.highcut_hz,
                filter_order=self.filter_order,
                gate_threshold_rms=self.gate_threshold_rms,
                peak_ceiling=self.peak_ceiling,
                pre_gain=self.pre_gain,
            )

    def _sosfilt_fallback_numpy(self, buf: "np.ndarray") -> "np.ndarray":
        """Direct Form II Transposed SOS cascade for environments without scipy."""
        samples = buf.tolist()
        for sec_idx, sec in enumerate(self._sos_list):
            b0, b1, b2, _, a1, a2 = sec
            z1, z2 = self._zi_list[sec_idx]
            for i, x in enumerate(samples):
                out = b0 * x + z1
                z1 = b1 * x - a1 * out + z2
                z2 = b2 * x - a2 * out
                samples[i] = out
            self._zi_list[sec_idx][0] = z1
            self._zi_list[sec_idx][1] = z2
        return np.asarray(samples, dtype=np.float32)

    def process_frame(
        self,
        pcm_data: Union[bytes, bytearray, memoryview, "np.ndarray"],
        input_rate: Optional[int] = None,
        return_bytes: bool = False,
    ) -> Union["np.ndarray", bytes]:
        """
        Condition a single PCM frame for AMBE+2 encoding.

        Args:
            pcm_data: Incoming 16-bit signed mono PCM (bytes or numpy int16 array).
            input_rate: Optional sample rate override (e.g., 48000, 16000, 8000).
            return_bytes: If True, returns raw int16 bytes; otherwise returns np.ndarray[int16].
        """
        if input_rate is not None and int(input_rate) != self.input_rate:
            self.set_input_rate(int(input_rate))

        if np is None:
            # Pure-Python emergency path if numpy is unavailable
            return self._process_frame_pure_python(pcm_data, return_bytes=return_bytes)

        # --- Step 0: Zero-copy ingest & conversion to float32 ---
        if isinstance(pcm_data, (bytes, bytearray, memoryview)):
            if len(pcm_data) == 0:
                return b"" if return_bytes else np.empty(0, dtype=np.int16)
            pcm_in = np.frombuffer(pcm_data, dtype=np.int16)
        elif isinstance(pcm_data, np.ndarray):
            if pcm_data.size == 0:
                return b"" if return_bytes else np.empty(0, dtype=np.int16)
            pcm_in = pcm_data
        else:
            raise TypeError(f"Unsupported pcm_data type: {type(pcm_data)}")

        buf = pcm_in.astype(np.float32, copy=True)

        if self.pre_gain != 1.0:
            np.multiply(buf, self.pre_gain, out=buf)

        # --- Step 1: High-Quality Polyphase Downsampling (to 8000 Hz) ---
        if self._needs_resample:
            if scipy_signal is not None:
                buf = scipy_signal.resample_poly(
                    buf, self._up, self._down, window=self._resample_window
                ).astype(np.float32, copy=False)
            else:
                # Polyphase FIR anti-aliasing decimation using numpy convolve
                if self._up > 1:
                    upsampled = np.zeros(buf.size * self._up, dtype=np.float32)
                    upsampled[:: self._up] = buf
                    filtered = np.convolve(upsampled, self._fallback_fir, mode="same")
                    buf = filtered[:: self._down]
                else:
                    filtered = np.convolve(buf, self._fallback_fir, mode="same")
                    buf = filtered[:: self._down]

        if buf.size == 0:
            return b"" if return_bytes else np.empty(0, dtype=np.int16)

        # --- Step 2: Strict Band-Pass Filter (300 Hz - 3300 Hz) ---
        if scipy_signal is not None and self._sos is not None:
            buf, self._zi = scipy_signal.sosfilt(self._sos, buf, zi=self._zi)
        else:
            buf = self._sosfilt_fallback_numpy(buf)

        # --- Step 3: Noise Gate (Squelch) ---
        rms = float(np.sqrt(np.dot(buf, buf) / buf.size))
        if rms < self.gate_threshold_rms:
            out_silent = np.zeros(buf.shape, dtype=np.int16)
            return out_silent.tobytes() if return_bytes else out_silent

        # --- Step 4: Soft Limiter / Proportional Anti-Clipping AGC ---
        peak = float(np.max(np.abs(buf)))
        if peak > self.peak_ceiling:
            scale = self.peak_ceiling / peak
            np.multiply(buf, scale, out=buf)

        # --- Step 5: Format Output as int16 ---
        np.clip(buf, -32768.0, 32767.0, out=buf)
        out_pcm = buf.astype(np.int16)

        return out_pcm.tobytes() if return_bytes else out_pcm

    def _process_frame_pure_python(
        self, pcm_data: Union[bytes, bytearray, memoryview], return_bytes: bool = True
    ) -> bytes:
        if not pcm_data:
            return b""
        n_in = len(pcm_data) // 2
        samples = [float(s) * self.pre_gain for s in struct.unpack(f"<{n_in}h", pcm_data[: n_in * 2])]

        if self._needs_resample and self._down > 1 and self._up == 1:
            step = self._down
            n_out = n_in // step
            samples = [
                sum(samples[i * step : (i + 1) * step]) / step for i in range(n_out)
            ]

        for sec_idx, sec in enumerate(self._sos_list):
            b0, b1, b2, _, a1, a2 = sec
            z1, z2 = self._zi_list[sec_idx]
            for i, x in enumerate(samples):
                out = b0 * x + z1
                z1 = b1 * x - a1 * out + z2
                z2 = b2 * x - a2 * out
                samples[i] = out
            self._zi_list[sec_idx][0] = z1
            self._zi_list[sec_idx][1] = z2

        if not samples:
            return b""

        rms = math.sqrt(sum(x * x for x in samples) / len(samples))
        if rms < self.gate_threshold_rms:
            return bytes(len(samples) * 2)

        peak = max(abs(x) for x in samples)
        scale = (self.peak_ceiling / peak) if peak > self.peak_ceiling else 1.0

        out_ints = [
            int(max(-32768.0, min(32767.0, x * scale))) for x in samples
        ]
        return struct.pack(f"<{len(out_ints)}h", *out_ints)
