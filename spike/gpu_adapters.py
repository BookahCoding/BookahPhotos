"""List DXGI adapters in the same order DirectML uses for device_id, and find the RX 7600 by name."""
import ctypes
from ctypes import wintypes, byref, POINTER, c_void_p, c_uint, c_size_t, c_wchar, Structure

class GUID(Structure):
    _fields_ = [("a", ctypes.c_uint32), ("b", ctypes.c_uint16), ("c", ctypes.c_uint16), ("d", ctypes.c_ubyte * 8)]

def guid(s):
    import uuid
    u = uuid.UUID(s)
    g = GUID()
    g.a, g.b, g.c = u.time_low, u.time_mid, u.time_hi_version
    for i, byte in enumerate(u.bytes[8:]):
        g.d[i] = byte
    return g

class DESC1(Structure):
    _fields_ = [("Description", c_wchar * 128), ("VendorId", c_uint), ("DeviceId", c_uint), ("SubSysId", c_uint),
                ("Revision", c_uint), ("DedicatedVideoMemory", c_size_t), ("DedicatedSystemMemory", c_size_t),
                ("SharedSystemMemory", c_size_t), ("LuidLow", wintypes.DWORD), ("LuidHigh", wintypes.LONG), ("Flags", c_uint)]

def vcall(obj, index, restype, *argtypes):
    vtbl = ctypes.cast(ctypes.cast(obj, POINTER(c_void_p))[0], POINTER(c_void_p))
    return ctypes.WINFUNCTYPE(restype, c_void_p, *argtypes)(vtbl[index])

def list_adapters():
    dxgi = ctypes.windll.dxgi
    factory = c_void_p()
    IID_IDXGIFactory1 = guid("770aae78-f26f-4dba-a829-253c83d1b387")
    assert dxgi.CreateDXGIFactory1(byref(IID_IDXGIFactory1), byref(factory)) == 0
    out = []
    i = 0
    while True:
        adapter = c_void_p()
        hr = vcall(factory, 12, ctypes.c_long, c_uint, POINTER(c_void_p))(factory, i, byref(adapter))  # EnumAdapters1
        if hr != 0:
            break
        desc = DESC1()
        vcall(adapter, 10, ctypes.c_long, POINTER(DESC1))(adapter, byref(desc))  # GetDesc1
        out.append((i, desc.Description, desc.DedicatedVideoMemory / 2**30))
        i += 1
    return out

def find_by_name(fragment):
    for idx, name, vram in list_adapters():
        if fragment.lower() in name.lower():
            return idx
    raise RuntimeError(f"No adapter named like {fragment!r}")

if __name__ == "__main__":
    for idx, name, vram in list_adapters():
        print(f"DXGI adapter {idx}: {name}  dedicated VRAM {vram:.1f} GB")
    print("RX 7600 index by name:", find_by_name("RX 7600"))
