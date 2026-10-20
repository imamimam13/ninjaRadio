import os
import sys
import time
import struct
import ctypes
import logging
from typing import List, Tuple, Optional, Callable

logger = logging.getLogger("NinjaRadio-DirectDMR")

# ETSI TS 102 361-1 Annex B interleave schedule (36 dibits per AMBE frame)
R_W = [
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 2,
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 2, 0, 2,
]
R_X = [
    23, 10, 22, 9, 21, 8,
    20, 7, 19, 6, 18, 5,
    28, 4, 16, 3, 15, 2,
    14, 1, 13, 0, 12, 10,
    11, 9, 10, 8, 9, 7,
    8, 6, 7, 5, 6, 4,
]
# Fix index 28 to 17 per standard ETSI TS 102 361-1
R_X[12] = 17

R_Y = [
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 3, 0, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
]
R_Z = [
    5, 3, 4, 2, 3, 1,
    2, 0, 1, 13, 0, 12,
    22, 11, 21, 10, 20, 9,
    19, 8, 18, 7, 17, 6,
    16, 5, 15, 4, 14, 3,
    13, 2, 12, 1, 11, 0,
]

BURST_BYTES = 33
DIBITS_PER_BURST = 132
FRAME_BYTES = 9
FRAME_BITS = 72
PCM_SAMPLES = 160
PCM_BYTES = PCM_SAMPLES * 2  # 320 bytes (20ms)
BURST_SAMPLES = 480
BURST_PCM_BYTES = BURST_SAMPLES * 2  # 960 bytes (60ms)
DMRD = b"DMRD"

try:
    from dmr_utils3 import bptc, const
    from bitarray import bitarray
except ImportError:
    try:
        from core.dmr_bptc import bptc, const
        from bitarray import bitarray
    except ImportError:
        bptc = None
        const = None
        bitarray = None

# ETSI TS 102 361-1 Table 9.1: Base Station (BS) Voice Sync (48 bits = 24 dibits: 0x755FD7DF75F7)
VOICE_SYNC_BS_DIBITS = [
    1, 3, 1, 1, 1, 1, 3, 3, 3, 1, 1, 3, 3, 1, 3, 3, 1, 3, 1, 1, 3, 3, 1, 3
]

# ETSI TS 102 361-1 Table 9.1: Base Station (BS) Data Sync (48 bits = 24 dibits: 0xDFF57D75DF5D)
# Used for signaling bursts including Voice LC Header (data_type=0) and Voice LC Terminator (data_type=2)
DATA_SYNC_BS_DIBITS = [
    3, 1, 3, 3, 3, 3, 1, 1, 1, 3, 3, 1, 1, 3, 1, 1, 3, 1, 3, 3, 1, 1, 3, 1
]

# ETSI TS 102 361-1 Section 9.1.4: Embedded Signalling (EMB) with Colour Code 1 (CC1) fallback
EMB_CC1_DIBITS = [
    0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 3
]


def build_dmr_header_burst(src_id: int, dst_tg: int, slot: int, call_type: str = "group") -> bytes:
    """
    Constructs a standard ETSI TS 102 361-1 compliant 33-byte Voice Header burst (data_type = 0).
    Applies BPTC(196,96) error correction, RS(12,9,3) parity, and interleaves with BS Data Sync.
    """
    if bptc and const:
        try:
            flco = b"\x03" if call_type == "unit" else b"\x00"
            lc_bytes = flco + b"\x00\x00" + dst_tg.to_bytes(3, "big") + src_id.to_bytes(3, "big")
            h_lc = bptc.encode_header_lc(lc_bytes)
            slot_head = const.SLOT_TYPE["VOICE_LC_HEAD"]
            sync_data = const.SYNC["BS_DATA"]
            burst_bits = h_lc[0:98] + slot_head[0:10] + sync_data + slot_head[10:20] + h_lc[98:196]
            return burst_bits.tobytes()
        except Exception as e:
            logger.debug(f"build_dmr_header_burst bptc error: {e}")
    hdr_dibits = [0] * 54 + DATA_SYNC_BS_DIBITS + [0] * 54
    return dibits_to_bytes(hdr_dibits)


def build_dmr_terminator_burst(src_id: int, dst_tg: int, slot: int, call_type: str = "group") -> bytes:
    """
    Constructs a standard ETSI TS 102 361-1 compliant 33-byte Voice Terminator burst (data_type = 2).
    """
    if bptc and const:
        try:
            flco = b"\x03" if call_type == "unit" else b"\x00"
            lc_bytes = flco + b"\x00\x00" + dst_tg.to_bytes(3, "big") + src_id.to_bytes(3, "big")
            t_lc = bptc.encode_terminator_lc(lc_bytes)
            slot_term = const.SLOT_TYPE["VOICE_LC_TERM"]
            sync_data = const.SYNC["BS_DATA"]
            burst_bits = t_lc[0:98] + slot_term[0:10] + sync_data + slot_term[10:20] + t_lc[98:196]
            return burst_bits.tobytes()
        except Exception as e:
            logger.debug(f"build_dmr_terminator_burst bptc error: {e}")
    term_dibits = [0] * 54 + DATA_SYNC_BS_DIBITS + [0] * 54
    return dibits_to_bytes(term_dibits)


def build_dmr_emblc_fragments(src_id: int, dst_tg: int, call_type: str = "group") -> Optional[dict]:
    """
    Precomputes the 4 Embedded Link Control (EMB_LC) fragments for bursts B, C, D, E
    using standard 5-bit CRC and Hamming error correction per ETSI TS 102 361-1.
    """
    if bptc:
        try:
            flco = b"\x03" if call_type == "unit" else b"\x00"
            lc_bytes = flco + b"\x00\x00" + dst_tg.to_bytes(3, "big") + src_id.to_bytes(3, "big")
            return bptc.encode_emblc(lc_bytes)
        except Exception as e:
            logger.debug(f"build_dmr_emblc_fragments error: {e}")
    return None


def get_burst_center_dibits(seq: int, emblc_dict: Optional[dict] = None) -> List[int]:
    """
    Returns the standard 24 center dibits (48 bits) for a DMR burst in a superframe:
    - Seq 0 (Burst A): Base Station Voice Sync (0x755FD7DF75F7)
    - Seq 1..4 (Bursts B..E): EMB with CC1 + 32-bit Embedded Link Control fragment
    - Seq 5 (Burst F): EMB with CC1 + null payload
    """
    if seq == 0:
        return VOICE_SYNC_BS_DIBITS
    if const and emblc_dict:
        burst_names = {1: "BURST_B", 2: "BURST_C", 3: "BURST_D", 4: "BURST_E", 5: "BURST_F"}
        name = burst_names.get(seq, "BURST_F")
        emb_bits = const.EMB.get(name)
        if emb_bits:
            if seq in emblc_dict:
                frag_bits = emblc_dict[seq]
            else:
                frag_bits = bitarray("0" * 32)
            center_bits = emb_bits[0:8] + frag_bits + emb_bits[8:16]
            return bytes_to_dibits(center_bits.tobytes())[:24]
    return EMB_CC1_DIBITS



def bytes_to_dibits(payload: bytes) -> List[int]:
    dibits = [0] * DIBITS_PER_BURST
    for i in range(min(BURST_BYTES, len(payload))):
        b = payload[i]
        dibits[i * 4 + 0] = (b >> 6) & 0x03
        dibits[i * 4 + 1] = (b >> 4) & 0x03
        dibits[i * 4 + 2] = (b >> 2) & 0x03
        dibits[i * 4 + 3] = b & 0x03
    return dibits


def dibits_to_bytes(dibits: List[int]) -> bytes:
    out = bytearray(BURST_BYTES)
    for i in range(BURST_BYTES):
        d0, d1, d2, d3 = dibits[i * 4:i * 4 + 4]
        out[i] = ((d0 & 3) << 6) | ((d1 & 3) << 4) | ((d2 & 3) << 2) | (d3 & 3)
    return bytes(out)


def _fr_get_bit(fr: bytearray, row: int, col: int) -> int:
    return fr[row * 24 + col] & 1


def _fr_set_bit(fr: bytearray, row: int, col: int, value: int) -> None:
    fr[row * 24 + col] = value & 1


def canonical_to_fr(frame: bytes) -> bytearray:
    fr = bytearray(96)

    def bit(c: int) -> int:
        return (frame[c // 8] >> (7 - (c % 8))) & 1

    for c in range(0, 24):
        _fr_set_bit(fr, 0, 23 - c, bit(c))
    for c in range(24, 47):
        _fr_set_bit(fr, 1, 46 - c, bit(c))
    for c in range(47, 58):
        _fr_set_bit(fr, 2, 57 - c, bit(c))
    for c in range(58, 72):
        _fr_set_bit(fr, 3, 71 - c, bit(c))
    return fr


def fr_to_canonical(fr: bytearray) -> bytes:
    bits = [0] * FRAME_BITS
    for c in range(0, 24):
        bits[c] = _fr_get_bit(fr, 0, 23 - c)
    for c in range(24, 47):
        bits[c] = _fr_get_bit(fr, 1, 46 - c)
    for c in range(47, 58):
        bits[c] = _fr_get_bit(fr, 2, 57 - c)
    for c in range(58, 72):
        bits[c] = _fr_get_bit(fr, 3, 71 - c)

    out = bytearray(FRAME_BYTES)
    for i, b in enumerate(bits):
        if b:
            out[i // 8] |= 1 << (7 - (i % 8))
    return bytes(out)


def interleave_burst(frames: Tuple[bytes, bytes, bytes], sync_dibits: Optional[List[int]] = None) -> bytes:
    fr1 = canonical_to_fr(frames[0])
    fr2 = canonical_to_fr(frames[1])
    fr3 = canonical_to_fr(frames[2])

    dibits = [0] * DIBITS_PER_BURST
    idx = 0

    for i in range(36):
        d = (_fr_get_bit(fr1, R_W[i], R_X[i]) << 1) | _fr_get_bit(fr1, R_Y[i], R_Z[i])
        dibits[idx] = d
        idx += 1

    for i in range(18):
        d = (_fr_get_bit(fr2, R_W[i], R_X[i]) << 1) | _fr_get_bit(fr2, R_Y[i], R_Z[i])
        dibits[idx] = d
        idx += 1

    if sync_dibits is not None and len(sync_dibits) == 24:
        dibits[54:78] = sync_dibits
    else:
        dibits[54:78] = [0] * 24
    idx = 78

    for i in range(18, 36):
        d = (_fr_get_bit(fr2, R_W[i], R_X[i]) << 1) | _fr_get_bit(fr2, R_Y[i], R_Z[i])
        dibits[idx] = d
        idx += 1

    for i in range(36):
        d = (_fr_get_bit(fr3, R_W[i], R_X[i]) << 1) | _fr_get_bit(fr3, R_Y[i], R_Z[i])
        dibits[idx] = d
        idx += 1

    return dibits_to_bytes(dibits)


def deinterleave_burst(payload: bytes) -> Tuple[bytes, bytes, bytes]:
    dibits = bytes_to_dibits(payload)
    fr1 = bytearray(96)
    fr2 = bytearray(96)
    fr3 = bytearray(96)
    idx = 0

    for i in range(36):
        d = dibits[idx]
        idx += 1
        _fr_set_bit(fr1, R_W[i], R_X[i], (d >> 1) & 1)
        _fr_set_bit(fr1, R_Y[i], R_Z[i], d & 1)

    for i in range(18):
        d = dibits[idx]
        idx += 1
        _fr_set_bit(fr2, R_W[i], R_X[i], (d >> 1) & 1)
        _fr_set_bit(fr2, R_Y[i], R_Z[i], d & 1)

    idx += 24  # skip sync / EMB

    for i in range(18, 36):
        d = dibits[idx]
        idx += 1
        _fr_set_bit(fr2, R_W[i], R_X[i], (d >> 1) & 1)
        _fr_set_bit(fr2, R_Y[i], R_Z[i], d & 1)

    for i in range(36):
        d = dibits[idx]
        idx += 1
        _fr_set_bit(fr3, R_W[i], R_X[i], (d >> 1) & 1)
        _fr_set_bit(fr3, R_Y[i], R_Z[i], d & 1)

    return (fr_to_canonical(fr1), fr_to_canonical(fr2), fr_to_canonical(fr3))


class DirectOpenDmrVocoder:
    """Stateful OpenDMR encoder/decoder pair wrapping libopendmr (C/C++)."""

    def __init__(self):
        self.lib = None
        self._dec = None
        self._enc = None
        self._pcm_out = (ctypes.c_int16 * PCM_SAMPLES)()
        self._pcm_in = (ctypes.c_int16 * PCM_SAMPLES)()
        self._frame_out = (ctypes.c_uint8 * FRAME_BYTES)()
        self._errs = ctypes.c_int(0)

        self._load_library()
        if self.lib:
            self._bind()
            try:
                self._dec = self.lib.opendmr_decoder_create()
                self._enc = self.lib.opendmr_encoder_create()
                if self._enc and hasattr(self.lib, "opendmr_encoder_set_gain"):
                    try:
                        self.lib.opendmr_encoder_set_gain(self._enc, 6)
                    except Exception:
                        pass
                logger.info(f"✓ OpenDMR vocoder initialized (+6dB gain, version: {self.version()})")
            except Exception as e:
                logger.warning(f"Could not create OpenDMR codec instances: {e}")

    def _load_library(self):
        candidates = [
            os.environ.get("OPENDMR_LIB", ""),
            "/usr/local/lib/libopendmr.so",
            "/usr/lib/libopendmr.so",
            "/usr/lib/x86_64-linux-gnu/libopendmr.so",
            "/usr/lib/aarch64-linux-gnu/libopendmr.so",
            os.path.join(os.path.dirname(__file__), "opendmr/libopendmr.so"),
            os.path.join(os.path.dirname(__file__), "opendmr/libopendmr.dylib"),
            os.path.join(os.path.dirname(__file__), "../app-android/third_party/OpenDMR/libopendmr.so"),
            os.path.join(os.path.dirname(__file__), "../app-android/third_party/OpenDMR/libopendmr.dylib"),
            "libopendmr.so",
            "libopendmr.dylib",
        ]
        for p in candidates:
            if p and (os.path.exists(p) or not p.startswith("/")):
                try:
                    self.lib = ctypes.CDLL(p)
                    logger.info(f"✓ Loaded libopendmr from {p}")
                    return
                except Exception:
                    continue

        # If not found in standard paths, auto-compile from source just-in-time
        opendmr_dir = os.path.join(os.path.dirname(__file__), "opendmr")
        makefile_path = os.path.join(opendmr_dir, "Makefile")
        if os.path.exists(makefile_path):
            try:
                logger.info(f"🛠️ [DirectDMR] Auto-compiling OpenDMR vocoder in {opendmr_dir}...")
                import subprocess
                res = subprocess.run(["make", "-C", opendmr_dir], capture_output=True, text=True, timeout=30)
                if res.returncode == 0:
                    so_path = os.path.join(opendmr_dir, "libopendmr.so")
                    dylib_path = os.path.join(opendmr_dir, "libopendmr.dylib")
                    for target in [so_path, dylib_path]:
                        if os.path.exists(target):
                            try:
                                self.lib = ctypes.CDLL(target)
                                logger.info(f"✓ [DirectDMR] Successfully compiled and loaded {target}")
                                # Try copying to /usr/local/lib for future instant loading
                                try:
                                    import shutil
                                    shutil.copyfile(target, "/usr/local/lib/libopendmr.so")
                                    subprocess.run(["ldconfig"], check=False)
                                except Exception:
                                    pass
                                return
                            except Exception as e:
                                logger.warning(f"Failed to load compiled {target}: {e}")
                else:
                    logger.error(f"❌ [DirectDMR] make failed:\n{res.stderr}")
            except Exception as e:
                logger.error(f"❌ [DirectDMR] Failed to run make: {e}")

    def _bind(self):
        lib = self.lib
        lib.opendmr_decoder_create.restype = ctypes.c_void_p
        lib.opendmr_decoder_destroy.argtypes = [ctypes.c_void_p]
        lib.opendmr_decoder_reset.argtypes = [ctypes.c_void_p]
        lib.opendmr_decode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_uint8),
            ctypes.POINTER(ctypes.c_int16),
            ctypes.POINTER(ctypes.c_int),
        ]
        lib.opendmr_decode.restype = ctypes.c_bool

        lib.opendmr_encoder_create.restype = ctypes.c_void_p
        lib.opendmr_encoder_destroy.argtypes = [ctypes.c_void_p]
        lib.opendmr_encoder_reset.argtypes = [ctypes.c_void_p]
        if hasattr(lib, "opendmr_encoder_set_gain"):
            lib.opendmr_encoder_set_gain.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.opendmr_encode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.POINTER(ctypes.c_uint8),
        ]
        lib.opendmr_encode.restype = ctypes.c_bool
        lib.opendmr_version.restype = ctypes.c_char_p

    def version(self) -> str:
        if not self.lib:
            return "unavailable"
        try:
            return self.lib.opendmr_version().decode("utf-8")
        except Exception:
            return "unknown"

    def is_available(self) -> bool:
        return self._enc is not None and self._dec is not None

    def encode_frame(self, pcm: bytes) -> bytes:
        if not self._enc or len(pcm) != PCM_BYTES:
            return bytes(FRAME_BYTES)
        samples = struct.unpack("<160h", pcm)
        for i, s in enumerate(samples):
            self._pcm_in[i] = s
        ok = self.lib.opendmr_encode(self._enc, self._pcm_in, self._frame_out)
        if not ok:
            return bytes(FRAME_BYTES)
        return bytes(self._frame_out)

    def decode_frame(self, frame: bytes) -> bytes:
        if not self._dec or len(frame) != FRAME_BYTES:
            return bytes(PCM_BYTES)
        buf = (ctypes.c_uint8 * FRAME_BYTES).from_buffer_copy(frame)
        ok = self.lib.opendmr_decode(self._dec, buf, self._pcm_out, ctypes.byref(self._errs))
        if not ok:
            return bytes(PCM_BYTES)
        return struct.pack("<160h", *self._pcm_out)

    def encode_burst(self, pcm_960: bytes, sequence: int = 0, emblc_dict: Optional[dict] = None) -> bytes:
        if len(pcm_960) != BURST_PCM_BYTES:
            return bytes(BURST_BYTES)
        f1 = self.encode_frame(pcm_960[:320])
        f2 = self.encode_frame(pcm_960[320:640])
        f3 = self.encode_frame(pcm_960[640:960])
        sync = get_burst_center_dibits(sequence, emblc_dict)
        return interleave_burst((f1, f2, f3), sync)

    def decode_burst(self, burst_33: bytes) -> bytes:
        if len(burst_33) < BURST_BYTES:
            return b""
        f1, f2, f3 = deinterleave_burst(burst_33[:BURST_BYTES])
        p1 = self.decode_frame(f1)
        p2 = self.decode_frame(f2)
        p3 = self.decode_frame(f3)
        return p1 + p2 + p3

    def close(self):
        if self._dec:
            self.lib.opendmr_decoder_destroy(self._dec)
            self._dec = None
        if self._enc:
            self.lib.opendmr_encoder_destroy(self._enc)
            self._enc = None


_stream_seq_counter = 0

def build_dmrd_packet(
    src_id: int,
    dst_tg: int,
    slot: int,
    data_type: int,
    seq: int,
    payload: bytes,
    rpt_id: int = 5109999,
    stream_id: Optional[int] = None,
    call_type: str = "group"
) -> bytes:
    """Constructs a valid standard 53-byte HomeBrew DMRD packet."""
    global _stream_seq_counter
    _stream_seq_counter = (_stream_seq_counter + 1) & 0xFF

    bits = 0
    if slot == 2:
        bits |= 0x80
    if call_type == "unit":
        bits |= 0x40
    bits |= (data_type & 0x03) << 4
    bits |= (seq & 0x0F)

    # For Voice Header (data_type = 0), if payload is empty, construct a valid header with BPTC(196,96) LC
    if data_type == 0 and (not payload or len(payload) == 0):
        safe_payload = build_dmr_header_burst(src_id, dst_tg, slot, call_type)
    elif data_type == 2 and (not payload or len(payload) == 0):
        # Voice Terminator with BPTC(196,96) LC
        safe_payload = build_dmr_terminator_burst(src_id, dst_tg, slot, call_type)
    else:
        safe_payload = (payload[:BURST_BYTES] if payload else b"").ljust(BURST_BYTES, b"\x00")

    if stream_id is None:
        stream_id = int(time.time()) & 0xFFFFFFFF

    return (
        DMRD
        + struct.pack("B", _stream_seq_counter)
        + struct.pack(">I", src_id)[1:]
        + struct.pack(">I", dst_tg)[1:]
        + struct.pack(">I", rpt_id)
        + struct.pack("B", bits)
        + struct.pack(">I", stream_id)
        + safe_payload
    )


class DirectDmrEngine:
    """
    Direct Z3DMR-Style Engine for WebRTC / Web PTT:
    - Encodes 8kHz PCM to DMRD frames in real time (Zero Analog_Bridge overhead).
    - Decodes incoming DMR bursts directly to 8kHz PCM for Web Audio Player.
    - Completely eliminates resource competition with DVSwitch Mobile.
    """

    def __init__(self, dmr_master_broadcast_fn: Callable[[bytes], None], rpt_id: int = 5109999):
        self.vocoder = DirectOpenDmrVocoder()
        self.broadcast_fn = dmr_master_broadcast_fn
        self.rpt_id = rpt_id

        # TX State Tracking
        self.in_tx = False
        self.current_stream_id = 0
        self.current_burst_seq = 0
        self.current_tg = 0
        self.current_slot = 2
        self.current_src_id = 0
        self.current_callsign = ""
        self.current_emblc = None
        self.current_hdr_burst = b""
        self.current_term_burst = b""
        self.tx_pcm_buffer = bytearray()
        self.last_tx_ts = 0.0
        logger.info(f"✓ Direct Z3DMR Engine ready (Vocoder available: {self.vocoder.is_available()}, version: {self.vocoder.version()})")

    def push_web_ptt_pcm(
        self,
        callsign: str,
        dmr_id: int,
        tg: int,
        slot: int,
        pcm_chunk: bytes,
        is_tx: bool
    ) -> List[bytes]:
        """
        Receives PCM audio chunks from Web PTT, encodes them to DMRD packets,
        and broadcasts them directly to DMR Master and connected repeaters.
        """
        out_packets = []
        now = time.time()

        if not is_tx:
            # PTT Released (Unkey)
            if self.in_tx:
                # Flush residual buffer if at least 1 burst can be formed
                if len(self.tx_pcm_buffer) >= BURST_PCM_BYTES:
                    burst_pcm = bytes(self.tx_pcm_buffer[:BURST_PCM_BYTES])
                    del self.tx_pcm_buffer[:BURST_PCM_BYTES]
                    burst = self.vocoder.encode_burst(burst_pcm, self.current_burst_seq, self.current_emblc)
                    pkt = build_dmrd_packet(
                        src_id=self.current_src_id,
                        dst_tg=self.current_tg,
                        slot=self.current_slot,
                        data_type=1,
                        seq=self.current_burst_seq,
                        payload=burst,
                        rpt_id=self.rpt_id,
                        stream_id=self.current_stream_id
                    )
                    out_packets.append(pkt)
                    if self.broadcast_fn:
                        self.broadcast_fn(pkt)

                # Send DMRD Voice Terminator (data_type = 2) with valid BPTC(196,96) Terminator LC
                term_burst = self.current_term_burst or build_dmr_terminator_burst(self.current_src_id, self.current_tg, self.current_slot)
                term_pkt = build_dmrd_packet(
                    src_id=self.current_src_id,
                    dst_tg=self.current_tg,
                    slot=self.current_slot,
                    data_type=2,
                    seq=0,
                    payload=term_burst,
                    rpt_id=self.rpt_id,
                    stream_id=self.current_stream_id
                )
                out_packets.append(term_pkt)
                if self.broadcast_fn:
                    self.broadcast_fn(term_pkt)

                logger.info(f"🎙️ [DirectDMR] Unkeyed TX session for {self.current_callsign} (TG {self.current_tg})")

            self.in_tx = False
            self.tx_pcm_buffer.clear()
            self.current_burst_seq = 0
            self.current_emblc = None
            return out_packets

        # PTT Active (Keyed)
        if not self.in_tx or self.current_tg != tg or self.current_src_id != dmr_id:
            # Start new transmission session: send DMRD Voice Header (data_type = 0)
            self.in_tx = True
            self.current_stream_id = int(now) & 0xFFFFFFFF
            self.current_burst_seq = 0
            self.current_tg = tg
            self.current_slot = slot if slot in (1, 2) else 2
            self.current_src_id = dmr_id
            self.current_callsign = callsign
            self.tx_pcm_buffer.clear()
            self.current_emblc = build_dmr_emblc_fragments(self.current_src_id, self.current_tg)
            self.current_hdr_burst = build_dmr_header_burst(self.current_src_id, self.current_tg, self.current_slot)
            self.current_term_burst = build_dmr_terminator_burst(self.current_src_id, self.current_tg, self.current_slot)

            if self.vocoder.is_available():
                # Pre-warm vocoder LPC predictor history with 2 primed frames so initial burst has full volume
                warmup_pcm = bytes(320)
                self.vocoder.encode_frame(warmup_pcm)
                self.vocoder.encode_frame(warmup_pcm)

            # Send 3 Voice Header preamble packets (seq 0, 1, 2) per ETSI DMR / HomeBrew standard
            for p_seq in range(3):
                hdr_pkt = build_dmrd_packet(
                    src_id=self.current_src_id,
                    dst_tg=self.current_tg,
                    slot=self.current_slot,
                    data_type=0,
                    seq=0,
                    payload=self.current_hdr_burst,
                    rpt_id=self.rpt_id,
                    stream_id=self.current_stream_id
                )
                out_packets.append(hdr_pkt)
                if self.broadcast_fn:
                    self.broadcast_fn(hdr_pkt)
            logger.info(f"🎙️ [DirectDMR] 🔴 Started Direct DMR TX: {callsign} ({dmr_id}) -> TG {tg} (TS{slot}, Stream {self.current_stream_id}, 3x Preamble sent)")

        # Accumulate PCM and encode 960-byte (60ms) superframes
        if pcm_chunk:
            self.tx_pcm_buffer.extend(pcm_chunk)
            self.last_tx_ts = now

            while len(self.tx_pcm_buffer) >= BURST_PCM_BYTES:
                burst_pcm = bytes(self.tx_pcm_buffer[:BURST_PCM_BYTES])
                del self.tx_pcm_buffer[:BURST_PCM_BYTES]

                # Check amplitude & apply adaptive speech normalizer if mic level is quiet
                samples = list(struct.unpack("<480h", burst_pcm))
                peak = max(abs(s) for s in samples)
                if 20 < peak < 10000:
                    scale = min(3.5, 16000.0 / max(peak, 500))
                    boosted = [max(-32767, min(32767, int(s * scale))) for s in samples]
                    burst_pcm = struct.pack("<480h", *boosted)

                burst = self.vocoder.encode_burst(burst_pcm, self.current_burst_seq, self.current_emblc)
                if self.current_burst_seq == 0 or (self.current_burst_seq == 1 and peak > 0):
                    logger.info(f"🎙️ [DirectDMR] Ingress PCM peak: {peak}/32767 | AMBE payload: {burst[:6].hex()}... (Seq: {self.current_burst_seq})")
                pkt = build_dmrd_packet(
                    src_id=self.current_src_id,
                    dst_tg=self.current_tg,
                    slot=self.current_slot,
                    data_type=1,
                    seq=self.current_burst_seq,
                    payload=burst,
                    rpt_id=self.rpt_id,
                    stream_id=self.current_stream_id
                )
                out_packets.append(pkt)
                if self.broadcast_fn:
                    self.broadcast_fn(pkt)

                self.current_burst_seq = (self.current_burst_seq + 1) % 6

        return out_packets

    def decode_dmr_burst(self, burst_33: bytes) -> bytes:
        """Decodes 33-byte DMR AMBE burst into 960 bytes of 8kHz 16-bit PCM."""
        if not burst_33 or len(burst_33) < BURST_BYTES:
            return b""
        return self.vocoder.decode_burst(burst_33)
