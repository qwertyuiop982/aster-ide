"""Bounded file snapshots reused while inode, size and nanosecond times match."""
from collections import OrderedDict
import hashlib
from pathlib import Path
import threading

_LOCK = threading.RLock()
_FILES = OrderedDict()
_BYTES = 0
MAX_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 10000


def signature(path):
    info = path.stat()
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def read_snapshot(path, *, retain=False):
    global _BYTES
    path = Path(path)
    key = str(path)
    before = signature(path)
    with _LOCK:
        entry = _FILES.get(key)
        if entry and entry[0] == before and (not retain or entry[2] is not None):
            _FILES.move_to_end(key)
            return entry[1], entry[2]
    # Check after reading as well: edits concurrent with a scan must not become cached.
    data = path.read_bytes()
    after = signature(path)
    digest = hashlib.sha256(data).hexdigest()
    if before != after:
        return digest, data if retain else None
    stored = data if retain and len(data) <= 1024 * 1024 else None
    with _LOCK:
        previous = _FILES.pop(key, None)
        if previous and previous[2] is not None:
            _BYTES -= len(previous[2])
        _FILES[key] = (after, digest, stored)
        if stored is not None:
            _BYTES += len(stored)
        while len(_FILES) > MAX_ENTRIES or _BYTES > MAX_BYTES:
            _, removed = _FILES.popitem(last=False)
            if removed[2] is not None:
                _BYTES -= len(removed[2])
    return digest, data if retain else None


def digest(path):
    return read_snapshot(path)[0]


def content(path):
    return read_snapshot(path, retain=True)[1]