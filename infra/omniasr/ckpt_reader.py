"""Read tensors from a torch .pt (zip) checkpoint one at a time, then drop their pages.

torch.load(mmap=True) keeps every page it has touched mapped for as long as any tensor of the
checkpoint lives; on the GB10 those mapped pages count against MemAvailable (a streamed 1B run
measured ~6 GB at load). Here each tensor is read from its zip entry (torch stores them
uncompressed), copied to the target device/dtype, and its pages are released at once
(madvise(MADV_DONTNEED) on our mapping + posix_fadvise(DONTNEED) on the page cache), so only the
tensor being copied is resident. Metadata comes from data.pkl through a restricted unpickler that
builds descriptors only (no code from the checkpoint runs; unknown globals are refused).
"""

import builtins
import collections
import mmap
import os
import pickle
import warnings
import zipfile

import numpy as np
import torch

_DTYPES = {
    "FloatStorage": (torch.float32, np.float32), "HalfStorage": (torch.float16, np.float16),
    "BFloat16Storage": (torch.bfloat16, np.uint16), "DoubleStorage": (torch.float64, np.float64),
    "LongStorage": (torch.int64, np.int64), "IntStorage": (torch.int32, np.int32),
    "BoolStorage": (torch.bool, np.bool_), "ByteStorage": (torch.uint8, np.uint8),
    "CharStorage": (torch.int8, np.int8), "ShortStorage": (torch.int16, np.int16),
}
_PAGE = mmap.PAGESIZE


class _Storage:
    def __init__(self, name):
        self.name = name


class _Desc:
    __slots__ = ("key", "stype", "offset", "size", "stride")

    def __init__(self, storage, offset, size, stride):
        self.key, self.stype = storage
        self.offset, self.size, self.stride = offset, tuple(size), tuple(stride)


def _rebuild_tensor_v2(storage, offset, size, stride, *_args):
    return _Desc(storage, offset, size, stride)


def _rebuild_parameter(data, *_args):
    return data


class _Unpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if module == "torch._utils" and name == "_rebuild_tensor_v2":
            return _rebuild_tensor_v2
        if module == "torch._utils" and name in ("_rebuild_parameter",
                                                 "_rebuild_parameter_with_state"):
            return _rebuild_parameter
        if module == "torch" and name in _DTYPES:
            return _Storage(name)
        if module == "collections" and name == "OrderedDict":
            return collections.OrderedDict
        if module == "builtins" and name in ("dict", "list", "tuple", "set", "int", "float",
                                             "str", "bool"):
            return getattr(builtins, name)
        raise pickle.UnpicklingError(f"refused global {module}.{name}")

    def persistent_load(self, pid):
        # ('storage', storage_type, key, location, numel)
        _tag, stype, key, _loc, _numel = pid
        return (key, stype.name if isinstance(stype, _Storage) else str(stype))


class Checkpoint:
    def __init__(self, path):
        self.path = path
        self.fd = os.open(path, os.O_RDONLY)
        self.mm = mmap.mmap(self.fd, 0, access=mmap.ACCESS_READ)
        zf = zipfile.ZipFile(path)
        self.entries = {}
        pkl_name = None
        for info in zf.infolist():
            if info.filename.endswith("/data.pkl"):
                pkl_name = info.filename
            elif "/data/" in info.filename:
                if info.compress_type != zipfile.ZIP_STORED:
                    raise ValueError(f"compressed entry {info.filename}")
                h = self.mm[info.header_offset:info.header_offset + 30]
                n, e = int.from_bytes(h[26:28], "little"), int.from_bytes(h[28:30], "little")
                self.entries[info.filename.rsplit("/", 1)[1]] = info.header_offset + 30 + n + e
        obj = _Unpickler(zf.open(pkl_name)).load()
        zf.close()
        self.state = obj["model"] if isinstance(obj, dict) and "model" in obj else obj

    def keys(self):
        return [k for k, v in self.state.items() if isinstance(v, _Desc)]

    def get(self, name, device, dtype=None):
        d = self.state[name]
        tdt, ndt = _DTYPES[d.stype]
        numel = int(np.prod(d.size)) if d.size else 1
        exp, s = [], 1
        for dim in reversed(d.size):
            exp.append(s)
            s *= dim
        if tuple(reversed(exp)) != d.stride and numel > 1:
            raise ValueError(f"{name}: non-contiguous stride {d.stride}")
        item = np.dtype(ndt).itemsize
        start = self.entries[d.key] + d.offset * item
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")  # read-only buffer: we copy right away
            arr = np.frombuffer(self.mm, dtype=ndt, count=numel, offset=start)
            t = torch.from_numpy(arr)
        if tdt == torch.bfloat16:
            t = t.view(torch.bfloat16)
        out = t.reshape(d.size).to(device=device, dtype=dtype or tdt, copy=True)
        if out.device.type == "cuda":
            torch.cuda.synchronize()
        del t, arr
        lo = (start // _PAGE) * _PAGE
        length = start + numel * item - lo
        try:
            self.mm.madvise(mmap.MADV_DONTNEED, lo, length)
        except (OSError, ValueError, AttributeError):
            pass
        os.posix_fadvise(self.fd, lo, length, os.POSIX_FADV_DONTNEED)
        return out

    def subset(self, prefix, device, dtype=None, strip=True):
        return {(k[len(prefix):] if strip else k): self.get(k, device, dtype)
                for k in self.keys() if k.startswith(prefix)}

    def close(self):
        self.mm.close()
        os.close(self.fd)
