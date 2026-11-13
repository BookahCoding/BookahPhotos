import json, glob, os, collections
D = r"C:\BookahPC\bookahphotos\spike"
f = sorted(glob.glob(r"C:\Users\<you>\Downloads\faces-review-results*.json"), key=os.path.getmtime)[-1]; print("file", f)
st = json.load(open(f)); stats = json.load(open(D + r"\out\faces_stats.json"))
for kind in ("bl", "bs", "yunet"):
    names = {k.split("|", 2)[2]: v for k, v in st.items() if k.startswith(f"name|{kind}|") and v.strip()}
    shown = collections.Counter(); bad = collections.Counter()
    for k, v in st.items():
        if k.startswith("name|"): continue
        kk, g, face = k.split("|")
        if kk != kind: continue
        bad[int(g)] += bool(v)
    # faces shown per group is min(18, size); count from the page ordering: we only know clicked ones, so recompute from stats
    named = {int(g): n.strip().lower() for g, n in names.items()}
    print(f"\n== {kind}: groups named {len(named)}; groups: ", end="")
    by_person = collections.defaultdict(list)
    for g, n in named.items(): by_person[n].append(g)
    print({n: [g + 1 for g in gs] for n, gs in by_person.items()})
    print("   wrong faces clicked per group:", {g + 1: bad[g] for g in sorted(set(bad) | set(named))})
