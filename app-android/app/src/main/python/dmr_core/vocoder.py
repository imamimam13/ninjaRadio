"""ctypes binding for the OpenDMR AMBE+2 vocoder.

This is the default vocoder backend. It wraps ``libopendmr`` and exposes a
small, backend-agnostic interface used by the session layer:

* :meth:`OpenDmrVocoder.encode_frame`  PCM (160 samples) -> 9-byte canonical frame
* :meth:`OpenDmrVocoder.decode_frame`  9-byte canonical frame -> PCM (160 samples)
* :meth:`OpenDmrVocoder.decode_burst`  33-byte DMR burst -> PCM (480 samples)
* :meth:`OpenDmrVocoder.encode_burst`  PCM (480 samples) -> 33-byte DMR burst

A different backend (e.g. md380) can be dropped in by implementing the same
method names and assigning it to :data:`dmr_core.session` via the factory.
"""

import ctypes
import logging
import os
import struct
from typing import List, Optional

from . import interleave

logger = logging.getLogger("NinjaDMR-Vocoder")

FRAME_BYTES = 9
FRAME_BITS = 72
PCM_SAMPLES = 160
PCM_BYTES = PCM_SAMPLES * 2
BURST_BYTES = 33
BURST_SAMPLES = 480
BURST_PCM_BYTES = BURST_SAMPLES * 2

#: Set by the Kotlin bridge with the app's ``nativeLibraryDir`` on Android.
_native_lib_dir: Optional[str] = None


def set_native_lib_dir(path: str) -> None:
    """Register the directory containing ``libopendmr.so`` (Android jniLibs)."""
    global _native_lib_dir
    if path:
        _native_lib_dir = path
        logger.debug("native lib dir -> %s", path)


def _candidate_library_paths() -> List[str]:
    names = ["libopendmr.so", "libopendmr.dylib", "libopendmr.so.1"]
    paths: List[str] = []

    env = os.environ.get("OPENDMR_LIB")
    if env:
        paths.append(env)

    # Directory of this file and the package root (host testing).
    here = os.path.dirname(os.path.abspath(__file__))
    for name in names:
        paths.append(os.path.join(here, name))
        paths.append(os.path.join(here, "..", name))

    # Android native library dir (chaquopy / jniLibs), set by the Kotlin bridge.
    if _native_lib_dir:
        for name in names:
            paths.append(os.path.join(_native_lib_dir, name))

    for base in filter(None, [os.environ.get("ANDROID_NATIVE_LIBS")]):
        for name in names:
            paths.append(os.path.join(base, name))

    # Bare names: lets the platform linker resolve them (Android default path).
    paths.extend(names)
    return paths


def load_opendmr() -> ctypes.CDLL:
    last_err: Optional[Exception] = None
    for path in _candidate_library_paths():
        try:
            lib = ctypes.CDLL(path)
            logger.info("Loaded OpenDMR vocoder from %s", path)
            return lib
        except OSError as exc:  # noqa: PERF203
            last_err = exc
    raise OSError(f"could not load libopendmr: {last_err}")


class OpenDmrVocoder:
    """Stateful OpenDMR encoder/decoder pair."""

    def __init__(self, lib: Optional[ctypes.CDLL] = None):
        self.lib = lib or load_opendmr()
        self._bind()

        self._dec = self.lib.opendmr_decoder_create()
        self._enc = self.lib.opendmr_encoder_create()
        if not self._dec or not self._enc:
            raise RuntimeError("failed to create OpenDMR codec instances")

        self._pcm_out = (ctypes.c_int16 * PCM_SAMPLES)()
        self._pcm_in = (ctypes.c_int16 * PCM_SAMPLES)()
        self._frame_out = (ctypes.c_uint8 * FRAME_BYTES)()
        self._errs = ctypes.c_int(0)

    def _bind(self) -> None:
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
        lib.opendmr_encoder_set_gain.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.opendmr_encode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.POINTER(ctypes.c_uint8),
        ]
        lib.opendmr_encode.restype = ctypes.c_bool

        lib.opendmr_version.restype = ctypes.c_char_p

    def version(self) -> str:
        try:
            raw = self.lib.opendmr_version()
        except AttributeError:
            return "unknown"
        if not raw:
            return "unknown"
        return raw.decode("utf-8", "replace")

    # -- frame level -----------------------------------------------------

    def decode_frame(self, frame: bytes) -> bytes:
        if len(frame) != FRAME_BYTES:
            raise ValueError("frame must be 9 bytes")
        buf = (ctypes.c_uint8 * FRAME_BYTES).from_buffer_copy(frame)
        ok = self.lib.opendmr_decode(
            self._dec, buf, self._pcm_out, ctypes.byref(self._errs)
        )
        if not ok:
            return b""
        return struct.pack("<%dh" % PCM_SAMPLES, *self._pcm_out)

    def encode_frame(self, pcm: bytes) -> bytes:
        if len(pcm) != PCM_BYTES:
            raise ValueError("pcm must be 320 bytes")
        samples = struct.unpack("<%dh" % PCM_SAMPLES, pcm)
        for i, s in enumerate(samples):
            self._pcm_in[i] = s
        ok = self.lib.opendmr_encode(self._enc, self._pcm_in, self._frame_out)
        if not ok:
            return b""
        return bytes(self._frame_out)

    # -- burst level -----------------------------------------------------

    def decode_burst(self, payload: bytes) -> bytes:
        frames = interleave.deinterleave_burst(payload[:BURST_BYTES])
        out = bytearray()
        for frame in frames:
            out += self.decode_frame(frame)
        return bytes(out)

    def encode_burst(self, pcm: bytes, sync_dibits: Optional[List[int]] = None) -> bytes:
        if len(pcm) != BURST_PCM_BYTES:
            raise ValueError("pcm must be 960 bytes (480 samples)")
        frames = []
        for i in range(3):
            chunk = pcm[i * PCM_BYTES:(i + 1) * PCM_BYTES]
            frames.append(self.encode_frame(chunk))
        return interleave.interleave_burst(tuple(frames), sync_dibits)

    # -- control ---------------------------------------------------------

    def reset_decoder(self) -> None:
        self.lib.opendmr_decoder_reset(self._dec)

    def reset_encoder(self) -> None:
        self.lib.opendmr_encoder_reset(self._enc)

    def set_gain(self, gain_db: int) -> None:
        self.lib.opendmr_encoder_set_gain(self._enc, int(gain_db))

    def close(self) -> None:
        if getattr(self, "_dec", None):
            self.lib.opendmr_decoder_destroy(self._dec)
            self._dec = None
        if getattr(self, "_enc", None):
            self.lib.opendmr_encoder_destroy(self._enc)
            self._enc = None

    def __enter__(self) -> "OpenDmrVocoder":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()
