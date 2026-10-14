"""NinjaDMR core: DMR vocoder, burst framing and network protocols.

The package is deliberately dependency-free (stdlib only) so it can run both on
the host (for tests) and on Android via Chaquopy.
"""

__version__ = "0.1.0"

__all__ = ["__version__", "self_test"]


def self_test() -> dict:
    """Smoke-test the vocoder backend and return a status dict.

    Used by the Android bridge to confirm that ``libopendmr`` is present and the
    encode/decode path works before the radio session starts.
    """
    from .vocoder import OpenDmrVocoder

    info = {"version": __version__, "ok": False, "lib": None, "codec": None}
    vocoder = None
    try:
        vocoder = OpenDmrVocoder()
        info["codec"] = vocoder.version()

        import struct

        pcm = struct.pack("<160h", *([0] * 160))
        frame = vocoder.encode_frame(pcm)
        if len(frame) != 9:
            raise RuntimeError("encoder returned %d bytes" % len(frame))
        vocoder.reset_decoder()
        decoded = vocoder.decode_frame(frame)
        if len(decoded) != 320:
            raise RuntimeError("decoder returned %d bytes" % len(decoded))
        info["ok"] = True
    except Exception as exc:  # noqa: BLE001
        info["error"] = "%s: %s" % (type(exc).__name__, exc)
    finally:
        if vocoder is not None:
            vocoder.close()
    return info
