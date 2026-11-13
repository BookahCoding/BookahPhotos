"""The graphics card is shared with other jobs on BookahPC (llama.cpp, Ollama, OCR, Whisper). The indexer picks the RX 7600 by NAME (never by
number: the numbers change) and checks that nobody else is using the card before it loads a model and between batches. It gives way, it never fights.
"""
from __future__ import annotations

import ctypes
import json
import subprocess
import urllib.request
from ctypes import POINTER, Structure, byref, c_size_t, c_uint, c_void_p, c_wchar, wintypes
from dataclasses import dataclass
from typing import Callable

ADAPTER_NAME = "RX 7600"


# ---- finding the card by name (DXGI adapter order = DirectML device_id order) ----
class _GUID(Structure):
    _fields_ = [("a", ctypes.c_uint32), ("b", ctypes.c_uint16), ("c", ctypes.c_uint16), ("d", ctypes.c_ubyte * 8)]


class _DESC1(Structure):
    _fields_ = [("Description", c_wchar * 128), ("VendorId", c_uint), ("DeviceId", c_uint), ("SubSysId", c_uint), ("Revision", c_uint),
                ("DedicatedVideoMemory", c_size_t), ("DedicatedSystemMemory", c_size_t), ("SharedSystemMemory", c_size_t),
                ("LuidLow", wintypes.DWORD), ("LuidHigh", wintypes.LONG), ("Flags", c_uint)]


def _vcall(obj, index, restype, *argtypes):
    vtbl = ctypes.cast(ctypes.cast(obj, POINTER(c_void_p))[0], POINTER(c_void_p))
    return ctypes.WINFUNCTYPE(restype, c_void_p, *argtypes)(vtbl[index])


def list_adapters() -> list[tuple[int, str, float]]:
    """(index, name, GB of video memory) of every graphics adapter, in the order DirectML numbers them."""
    import uuid
    u = uuid.UUID("770aae78-f26f-4dba-a829-253c83d1b387")           # IDXGIFactory1
    g = _GUID(); g.a, g.b, g.c = u.time_low, u.time_mid, u.time_hi_version
    for i, byte in enumerate(u.bytes[8:]):
        g.d[i] = byte
    factory = c_void_p()
    if ctypes.windll.dxgi.CreateDXGIFactory1(byref(g), byref(factory)) != 0:
        raise RuntimeError("cannot open DXGI")
    out, i = [], 0
    while True:
        adapter = c_void_p()
        if _vcall(factory, 12, ctypes.c_long, c_uint, POINTER(c_void_p))(factory, i, byref(adapter)) != 0:
            break
        desc = _DESC1()
        _vcall(adapter, 10, ctypes.c_long, POINTER(_DESC1))(adapter, byref(desc))
        out.append((i, desc.Description, desc.DedicatedVideoMemory / 2 ** 30))
        i += 1
    return out


def find_adapter(fragment: str = ADAPTER_NAME, adapters: list[tuple[int, str, float]] | None = None) -> int:
    """The DirectML device id of the adapter whose name contains [fragment]. Raises when there is none (never falls back to another card)."""
    for idx, name, _ in (adapters if adapters is not None else list_adapters()):
        if fragment.lower() in name.lower():
            return idx
    raise RuntimeError(f"no graphics adapter named like {fragment!r}")


# ---- is somebody else using the card? ----
@dataclass
class GpuStatus:
    free: bool
    reasons: list[str]


def _ollama_models(timeout: float = 2.0) -> list[str]:
    """Models Ollama has loaded right now (it keeps them in video memory)."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/ps", timeout=timeout) as r:
            return [m.get("name", "?") for m in json.load(r).get("models", [])]
    except Exception:
        return []          # Ollama not running: nothing loaded


def _gpu_engine_percent() -> float:
    """Highest utilisation of any 3D or compute engine in the last second, from Windows' own counters (all adapters; the integrated GPU is idle)."""
    ps = ("$s=(Get-Counter '\\GPU Engine(*)\\Utilization Percentage' -SampleInterval 1 -MaxSamples 2).CounterSamples | "
          "Where-Object { $_.InstanceName -match 'engtype_(3D|Compute)' }; if($s){ ($s | Measure-Object CookedValue -Maximum).Maximum } else { 0 }")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=20).stdout.strip()
        return float(out.replace(",", ".")) if out else 0.0
    except Exception:
        return 0.0


def _busy_processes() -> list[str]:
    try:
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=15).stdout.lower()
    except Exception:
        return []
    # llama-server (the llama.cpp router) is always running and idle: it is judged by the card's utilisation, not by existing.
    return [name for name in ("whisper", "tesseract") if name in out]


def gpu_status(max_percent: float = 35.0, ollama=_ollama_models, utilisation=_gpu_engine_percent, processes=_busy_processes) -> GpuStatus:
    """Is the card free for the indexer? The three signals are injectable so this is tested without a graphics card."""
    reasons: list[str] = []
    loaded = ollama()
    if loaded:
        reasons.append("Ollama has a model loaded: " + ", ".join(loaded))
    pct = utilisation()
    if pct > max_percent:
        reasons.append(f"the card is {pct:.0f}% busy")
    reasons += [f"{p} is running" for p in processes()]
    return GpuStatus(free=not reasons, reasons=reasons)


class GpuGuard:
    """Asked before every batch. When the card is taken it says so and the caller unloads its model and waits; it never takes the card by force."""

    def __init__(self, check: Callable[[], GpuStatus] = gpu_status) -> None:
        self._check = check

    def ensure_free(self) -> GpuStatus:
        return self._check()
