"""DMR over-the-air burst <-> canonical AMBE+2 frame conversion.

This module converts between the 33-byte DMRD voice burst (132 dibits, the
on-air representation used by HomeBrew/MMDVM) and the three 72-bit canonical
(DVSI-order) AMBE+2 frames accepted by OpenDMR's C API.

Canonical frame layout (9 bytes / 72 bits, MSB first per byte):
    bits  0..23 : A block  = Golay(24,12) codeword of the 12-bit C0 vector
    bits 24..46 : B block  = Golay(23,12) codeword of the 12-bit C1 vector,
                             scrambled with the C0-derived PRNG
    bits 47..71 : C block  = raw 11-bit C2 followed by raw 14-bit C3

The on-air burst uses the ETSI TS 102 361-1 Annex B interleave. The
permutation tables (R_W/R_X/R_Y/R_Z) map each on-air dibit into mbelib's
``ambe_fr[4][24]`` bitplane layout; the mapping below reorders those planes
into the canonical A/B/C order (this was validated bit-exactly against
OpenDMR's own decode path).
"""

from typing import List, Tuple

# ETSI TS 102 361-1 Annex B interleave schedule (36 dibits per AMBE frame).
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
    17, 4, 16, 3, 15, 2,
    14, 1, 13, 0, 12, 10,
    11, 9, 10, 8, 9, 7,
    8, 6, 7, 5, 6, 4,
]
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
SYNC_DIBIT_OFFSET = 54
SYNC_DIBITS = 24
FRAME_BYTES = 9
FRAME_BITS = 72


def _bytes_to_dibits(payload: bytes) -> List[int]:
    dibits = [0] * DIBITS_PER_BURST
    for i in range(min(BURST_BYTES, len(payload))):
        b = payload[i]
        dibits[i * 4 + 0] = (b >> 6) & 0x03
        dibits[i * 4 + 1] = (b >> 4) & 0x03
        dibits[i * 4 + 2] = (b >> 2) & 0x03
        dibits[i * 4 + 3] = b & 0x03
    return dibits


def _dibits_to_bytes(dibits: List[int]) -> bytes:
    out = bytearray(BURST_BYTES)
    for i in range(BURST_BYTES):
        d0, d1, d2, d3 = dibits[i * 4:i * 4 + 4]
        out[i] = ((d0 & 3) << 6) | ((d1 & 3) << 4) | ((d2 & 3) << 2) | (d3 & 3)
    return bytes(out)


def _fr_get_bit(fr: bytearray, row: int, col: int) -> int:
    return fr[row * 24 + col] & 1


def _fr_set_bit(fr: bytearray, row: int, col: int, value: int) -> None:
    fr[row * 24 + col] = value & 1


def _canonical_to_fr(frame: bytes) -> bytearray:
    """Map a 9-byte canonical frame into mbelib's ambe_fr[4][24] (96 bytes)."""
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


def _fr_to_canonical(fr: bytearray) -> bytes:
    """Map mbelib's ambe_fr[4][24] into a 9-byte canonical frame."""
    out = bytearray(9)

    def set_bit(c: int, value: int) -> None:
        if value:
            out[c // 8] |= 1 << (7 - (c % 8))

    for k in range(24):
        set_bit(23 - k, _fr_get_bit(fr, 0, k))
    for k in range(23):
        set_bit(46 - k, _fr_get_bit(fr, 1, k))
    for k in range(11):
        set_bit(57 - k, _fr_get_bit(fr, 2, k))
    for k in range(14):
        set_bit(71 - k, _fr_get_bit(fr, 3, k))
    return bytes(out)


def _frame_fr_from_segments(dibits: List[int],
                            segments: List[Tuple[int, int]]) -> bytearray:
    """Populate ambe_fr from one or more (start, count) dibit segments.

    The frame-local index ``i`` (0..35) drives the R_W/R_X/R_Y/R_Z tables, so
    the middle frame's two 18-dibit halves around the sync are handled
    correctly.
    """
    fr = bytearray(96)
    i = 0
    for start, count in segments:
        for j in range(count):
            idx = start + j
            if idx >= len(dibits):
                return fr
            d = dibits[idx]
            _fr_set_bit(fr, R_W[i], R_X[i], (d >> 1) & 1)
            _fr_set_bit(fr, R_Y[i], R_Z[i], d & 1)
            i += 1
    return fr


def deinterleave_burst(payload: bytes) -> Tuple[bytes, bytes, bytes]:
    """Convert a 33-byte DMR voice burst into three canonical 9-byte frames.

    The 24 sync/embedded-signalling dibits in the middle of the burst are
    ignored.
    """
    if len(payload) < BURST_BYTES:
        raise ValueError("payload must be at least 33 bytes")
    dibits = _bytes_to_dibits(payload)

    fr1 = _frame_fr_from_segments(dibits, [(0, 36)])
    fr2 = _frame_fr_from_segments(dibits, [(36, 18), (78, 18)])
    fr3 = _frame_fr_from_segments(dibits, [(96, 36)])

    return (
        _fr_to_canonical(fr1),
        _fr_to_canonical(fr2),
        _fr_to_canonical(fr3),
    )


def deinterleave_burst_fr(payload: bytes) -> Tuple[bytearray, bytearray, bytearray]:
    """Like :func:`deinterleave_burst` but returns raw ambe_fr bitplanes."""
    dibits = _bytes_to_dibits(payload)
    return (
        _frame_fr_from_segments(dibits, [(0, 36)]),
        _frame_fr_from_segments(dibits, [(36, 18), (78, 18)]),
        _frame_fr_from_segments(dibits, [(96, 36)]),
    )


def _encode_frame_dibits(frame: bytes) -> List[int]:
    fr = _canonical_to_fr(frame)
    dibits = [0] * 36
    for i in range(36):
        hi = _fr_get_bit(fr, R_W[i], R_X[i])
        lo = _fr_get_bit(fr, R_Y[i], R_Z[i])
        dibits[i] = ((hi & 1) << 1) | (lo & 1)
    return dibits


def interleave_burst(frames: Tuple[bytes, bytes, bytes],
                     sync_dibits: List[int] = None) -> bytes:
    """Convert three canonical 9-byte frames into a 33-byte DMR voice burst.

    ``sync_dibits`` supplies the 24 embedded-signalling dibits (defaults to
    all zeros; callers handling real traffic should pass a valid sync or
    voice-LC pattern).
    """
    if len(frames) != 3:
        raise ValueError("expected exactly 3 canonical frames")

    dibits = [0] * DIBITS_PER_BURST
    f1 = _encode_frame_dibits(frames[0])
    f2 = _encode_frame_dibits(frames[1])
    f3 = _encode_frame_dibits(frames[2])

    dibits[0:36] = f1
    dibits[36:54] = f2[0:18]
    # sync / embedded signalling occupies dibits 54..77
    if sync_dibits is not None:
        for i in range(SYNC_DIBITS):
            dibits[SYNC_DIBIT_OFFSET + i] = sync_dibits[i] & 3
    dibits[78:96] = f2[18:36]
    dibits[96:132] = f3

    return _dibits_to_bytes(dibits)
