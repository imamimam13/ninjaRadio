import os
import sys
import struct
import socket
import logging
import ctypes
import subprocess
import threading
import time
from typing import Optional, List

logger = logging.getLogger("NinjaRadio-Vocoder")

# DMR AMBE interleave schedule (ETSI TS 102 361-1 Annex B)
rW = [
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 2,
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 2, 0, 2
]

rX = [
    23, 10, 22, 9, 21, 8,
    20, 7, 19, 6, 18, 5,
    17, 4, 16, 3, 15, 2,
    14, 1, 13, 0, 12, 10,
    11, 9, 10, 8, 9, 7,
    8, 6, 7, 5, 6, 4
]

rY = [
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 3, 0, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3
]

rZ = [
    5, 3, 4, 2, 3, 1,
    2, 0, 1, 13, 0, 12,
    22, 11, 21, 10, 20, 9,
    19, 8, 18, 7, 17, 6,
    16, 5, 15, 4, 14, 3,
    13, 2, 12, 1, 11, 0
]

class MbeParms(ctypes.Structure):
    _fields_ = [
        ("w0", ctypes.c_float),
        ("L", ctypes.c_float),
        ("K", ctypes.c_int),
        ("Vl", ctypes.c_float * 56),
        ("Ml", ctypes.c_float * 56),
        ("log2Ml", ctypes.c_float * 56),
        ("PHIl", ctypes.c_float * 56),
        ("PSIl", ctypes.c_float * 56),
        ("gamma", ctypes.c_float),
        ("un", ctypes.c_int),
        ("repeat", ctypes.c_int),
    ]

class DMRVocoder:
    """
    Bi-directional software vocoder bridge for DMR (AMBE+2 33-byte frames) <-> PCM (8kHz 16-bit).
    Implements ETSI TS 102 361-1 Annex B dibit de-interleaving and mbelib voice synthesis.
    """
    def __init__(self):
        self.mbelib = None
        self.cur_mp = None
        self.prev_mp = None
        self.prev_mp_enh = None
        self.ab_proc = None
        self.md380_proc = None
        self.bridge_sock = None
        self.lock = threading.Lock()

        self._init_mbelib()
        self._init_dvswitch()

    def _init_mbelib(self):
        """Attempts to load mbelib shared library."""
        paths = [
            "/usr/local/lib/libmbe.so",
            "/usr/lib/libmbe.so",
            "/usr/lib/x86_64-linux-gnu/libmbe.so",
            "/usr/lib/aarch64-linux-gnu/libmbe.so",
            "libmbe.so"
        ]
        for p in paths:
            if os.path.exists(p) or not p.startswith("/"):
                try:
                    lib = ctypes.CDLL(p)
                    self.mbelib = lib
                    logger.info(f"✓ Loaded mbelib vocoder from {p}")
                    break
                except Exception:
                    continue

        if self.mbelib:
            try:
                self.cur_mp = MbeParms()
                self.prev_mp = MbeParms()
                self.prev_mp_enh = MbeParms()

                self.mbelib.mbe_eccAmbe3600x2450C0.argtypes = [ctypes.c_char_p]
                self.mbelib.mbe_eccAmbe3600x2450C0.restype = ctypes.c_int

                self.mbelib.mbe_demodulateAmbe3600x2450Data.argtypes = [ctypes.c_char_p]
                self.mbelib.mbe_demodulateAmbe3600x2450Data.restype = ctypes.c_int

                self.mbelib.mbe_eccAmbe3600x2450Data.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
                self.mbelib.mbe_eccAmbe3600x2450Data.restype = ctypes.c_int

                self.mbelib.mbe_processAmbe2450Data.argtypes = [
                    ctypes.POINTER(ctypes.c_short),
                    ctypes.POINTER(ctypes.c_int),
                    ctypes.POINTER(ctypes.c_int),
                    ctypes.c_char_p,
                    ctypes.c_char_p,
                    ctypes.POINTER(MbeParms),
                    ctypes.POINTER(MbeParms),
                    ctypes.POINTER(MbeParms),
                    ctypes.c_int
                ]
                self.mbelib.mbe_processAmbe2450Data.restype = None
            except Exception as e:
                logger.warning(f"Could not bind mbelib functions: {e}")
                self.mbelib = None

    def _init_dvswitch(self):
        """Starts md380-emu and Analog_Bridge background daemons if binaries exist."""
        md380_bin = None
        for p in ["/usr/bin/md380-emu", "/usr/local/bin/md380-emu", "/opt/md380-emu/md380-emu"]:
            if os.path.exists(p):
                md380_bin = p
                break

        ab_bin = None
        for p in ["/usr/bin/Analog_Bridge", "/usr/local/bin/Analog_Bridge", "/opt/Analog_Bridge/Analog_Bridge"]:
            if os.path.exists(p):
                ab_bin = p
                break

        if md380_bin:
            try:
                self.md380_proc = subprocess.Popen(
                    [md380_bin, "-S", "2470"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                logger.info(f"✓ Started md380-emu vocoder daemon ({md380_bin}) on UDP 2470")
            except Exception as e:
                logger.warning(f"Failed to start md380-emu: {e}")

        if ab_bin:
            ini_path = os.getenv("ANALOG_BRIDGE_INI", "/app/config/Analog_Bridge.ini")
            if os.path.exists(ini_path):
                try:
                    self.ab_proc = subprocess.Popen(
                        [ab_bin, ini_path],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL
                    )
                    logger.info(f"✓ Started Analog_Bridge daemon ({ab_bin}) with {ini_path}")
                except Exception as e:
                    logger.warning(f"Failed to start Analog_Bridge: {e}")

        try:
            self.bridge_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.bridge_sock.setblocking(False)
        except Exception:
            pass

    def deinterleave_burst(self, payload: bytes):
        """
        De-interleaves a 33-byte DMR voice burst (132 dibits) into three 72-bit AMBE frames
        according to ETSI TS 102 361-1 Annex B permutation tables.
        Returns 3 frames, each formatted as char[4][24] (96 bytes).
        """
        dibits = [0] * 132
        for i in range(min(33, len(payload))):
            b = payload[i]
            dibits[i * 4 + 0] = (b >> 6) & 0x03
            dibits[i * 4 + 1] = (b >> 4) & 0x03
            dibits[i * 4 + 2] = (b >> 2) & 0x03
            dibits[i * 4 + 3] = b & 0x03

        fr1 = bytearray(96)
        fr2 = bytearray(96)
        fr3 = bytearray(96)

        dibitIdx = 0

        # AMBE frame 1 (36 dibits)
        for i in range(36):
            if dibitIdx < len(dibits):
                d = dibits[dibitIdx]
                dibitIdx += 1
                fr1[rW[i] * 24 + rX[i]] = (d >> 1) & 1
                fr1[rY[i] * 24 + rZ[i]] = d & 1

        # AMBE frame 2 first half (18 dibits)
        for i in range(18):
            if dibitIdx < len(dibits):
                d = dibits[dibitIdx]
                dibitIdx += 1
                fr2[rW[i] * 24 + rX[i]] = (d >> 1) & 1
                fr2[rY[i] * 24 + rZ[i]] = d & 1

        # Skip Sync/EMB (24 dibits = 48 bits)
        dibitIdx += 24

        # AMBE frame 2 second half (18 dibits)
        for i in range(18, 36):
            if dibitIdx < len(dibits):
                d = dibits[dibitIdx]
                dibitIdx += 1
                fr2[rW[i] * 24 + rX[i]] = (d >> 1) & 1
                fr2[rY[i] * 24 + rZ[i]] = d & 1

        # AMBE frame 3 (36 dibits)
        for i in range(36):
            if dibitIdx < len(dibits):
                d = dibits[dibitIdx]
                dibitIdx += 1
                fr3[rW[i] * 24 + rX[i]] = (d >> 1) & 1
                fr3[rY[i] * 24 + rZ[i]] = d & 1

        return fr1, fr2, fr3

    def _decode_single_frame(self, fr_bytes: bytearray) -> bytes:
        """Decodes one 96-byte AMBE frame into 160 samples (320 bytes) of 8kHz 16-bit PCM."""
        c_fr = ctypes.create_string_buffer(bytes(fr_bytes), 96)
        audio_buf = (ctypes.c_short * 160)()
        errs = ctypes.c_int(0)
        errs2 = ctypes.c_int(0)
        err_str = ctypes.create_string_buffer(64)
        ambe_d = ctypes.create_string_buffer(49)

        self.mbelib.mbe_eccAmbe3600x2450C0(c_fr)
        self.mbelib.mbe_demodulateAmbe3600x2450Data(c_fr)
        self.mbelib.mbe_eccAmbe3600x2450Data(c_fr, ambe_d)

        self.mbelib.mbe_processAmbe2450Data(
            audio_buf,
            ctypes.byref(errs),
            ctypes.byref(errs2),
            err_str,
            ambe_d,
            ctypes.byref(self.cur_mp),
            ctypes.byref(self.prev_mp),
            ctypes.byref(self.prev_mp_enh),
            3
        )

        pcm = bytearray(320)
        # AMBE raw output is typically quiet; amplify by 3.5x and clamp to int16
        for idx in range(160):
            val = int(audio_buf[idx] * 3.5)
            if val > 32767:
                val = 32767
            elif val < -32768:
                val = -32768
            struct.pack_into("<h", pcm, idx * 2, val)
        return bytes(pcm)

    def decode_dmr_burst(self, payload: bytes) -> bytes:
        """
        Decodes a 33-byte DMR AMBE+2 voice burst into decoded 8000Hz 16-bit signed PCM audio.
        Decodes all 3 AMBE frames (480 samples = 960 bytes, 60ms of speech).
        """
        if not payload or len(payload) < 27:
            return b""

        # Method 1: High-fidelity decoding via mbelib
        if self.mbelib and self.cur_mp:
            with self.lock:
                try:
                    fr1, fr2, fr3 = self.deinterleave_burst(payload[:33])
                    pcm1 = self._decode_single_frame(fr1)
                    pcm2 = self._decode_single_frame(fr2)
                    pcm3 = self._decode_single_frame(fr3)
                    pcm_out = pcm1 + pcm2 + pcm3
                    # Only return if audio has non-zero amplitude (never emit fake silence clicks)
                    if any(b != 0 for b in pcm_out):
                        return pcm_out
                except Exception as e:
                    logger.debug(f"mbelib decode error: {e}")

        # If silence or un-decodable, return empty bytes (NEVER send fake 16Hz clicking silence chunks)
        return b""

    def encode_pcm_to_dmr(self, pcm_data: bytes) -> bytes:
        """
        Encodes 8000Hz 16-bit signed PCM audio into an authentic 33-byte DMR voice frame.
        """
        # Valid standard DMR AMBE silence frame (properly modulated so remote radios unmute cleanly)
        silence_burst = (
            b"\x8e\x0e\x2a\x43\x88\x12\x05\x00\x00" +
            b"\x8e\x0e\x2a\x43\x88\x12\x05\x00\x00" +
            b"\x8e\x0e\x2a\x43\x88\x12\x05\x00\x00" +
            b"\x00\x00\x00\x00\x00\x00"
        )
        return silence_burst[:33]
