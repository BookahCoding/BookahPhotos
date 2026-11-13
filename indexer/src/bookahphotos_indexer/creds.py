"""Read an app password from Windows Credential Manager. The password is returned in memory only; never log it."""
from __future__ import annotations

import ctypes
from ctypes import wintypes


class _CREDENTIAL(ctypes.Structure):
    _fields_ = [("Flags", wintypes.DWORD), ("Type", wintypes.DWORD), ("TargetName", wintypes.LPWSTR), ("Comment", wintypes.LPWSTR),
                ("LastWritten", wintypes.FILETIME), ("CredentialBlobSize", wintypes.DWORD), ("CredentialBlob", ctypes.POINTER(ctypes.c_byte)),
                ("Persist", wintypes.DWORD), ("AttributeCount", wintypes.DWORD), ("Attributes", ctypes.c_void_p),
                ("TargetAlias", wintypes.LPWSTR), ("UserName", wintypes.LPWSTR)]


def read_credential(target: str) -> tuple[str, str]:
    """Return (user, password) stored under `target`, or raise LookupError."""
    adv = ctypes.WinDLL("advapi32", use_last_error=True)
    adv.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.POINTER(ctypes.POINTER(_CREDENTIAL))]
    adv.CredReadW.restype = wintypes.BOOL
    adv.CredFree.argtypes = [ctypes.c_void_p]
    ptr = ctypes.POINTER(_CREDENTIAL)()
    if not adv.CredReadW(target, 1, 0, ctypes.byref(ptr)):
        raise LookupError(f"no Windows credential named {target!r}")
    try:
        c = ptr.contents
        raw = ctypes.string_at(c.CredentialBlob, c.CredentialBlobSize)
        return c.UserName, raw.decode("utf-16-le")
    finally:
        adv.CredFree(ptr)
