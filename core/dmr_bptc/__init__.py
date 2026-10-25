import sys
import types

# 1. Fallback pure-Python bitarray class if bitarray C extension is not installed
if "bitarray" not in sys.modules:
    try:
        import bitarray
    except ImportError:
        class _bitarray(list):
            def __init__(self, initial=None, endian='big'):
                super().__init__()
                self.endian = endian
                if initial is None:
                    pass
                elif isinstance(initial, int):
                    self.extend([0] * initial)
                elif isinstance(initial, str):
                    self.extend([int(c) for c in initial if c in '01'])
                elif isinstance(initial, (list, tuple)):
                    self.extend([int(x) for x in initial])
                elif isinstance(initial, (bytes, bytearray)):
                    self.frombytes(initial)

            def frombytes(self, data: bytes):
                for b in data:
                    for i in range(7, -1, -1):
                        self.append((b >> i) & 1)

            def tobytes(self) -> bytes:
                out = bytearray((len(self) + 7) // 8)
                for i, bit in enumerate(self):
                    if bit:
                        out[i // 8] |= (1 << (7 - (i % 8)))
                return bytes(out)

            def to01(self) -> str:
                return "".join(str(b) for b in self)

            def copy(self):
                return _bitarray(self, endian=self.endian)

            def __getitem__(self, item):
                if isinstance(item, slice):
                    res = _bitarray(endian=self.endian)
                    res.extend(super().__getitem__(item))
                    return res
                return super().__getitem__(item)

            def __add__(self, other):
                res = _bitarray(self, endian=self.endian)
                if isinstance(other, _bitarray):
                    res.extend(other)
                elif isinstance(other, (list, tuple)):
                    res.extend(other)
                return res

        ba_mod = types.ModuleType("bitarray")
        ba_mod.bitarray = _bitarray
        sys.modules["bitarray"] = ba_mod

# 2. Register alias for dmr_utils3 to this module
_this_mod = sys.modules[__name__]
sys.modules["dmr_utils3"] = _this_mod

# 3. Import and populate submodules in dependency order
from . import rs129
_this_mod.rs129 = rs129
from . import hamming
_this_mod.hamming = hamming
from . import crc
_this_mod.crc = crc
from . import const
_this_mod.const = const
from . import bptc
_this_mod.bptc = bptc

__all__ = ["rs129", "hamming", "crc", "const", "bptc"]