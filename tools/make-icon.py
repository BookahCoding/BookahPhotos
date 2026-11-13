"""Generates the launcher icon artwork (Android vector drawables + an SVG preview). Run: python tools/make-icon.py [preview.svg]
Retro badge: a dragon circles the rim from the sea (bottom left) up to the mountains (top right); inside, sun, mountains and sea."""
import math, sys, pathlib
C = 54.0
ORANGE, ORANGE_D, SUN, TEAL, TEAL_D, TEAL_L, CREAM = "#F58A1F", "#E2561B", "#FFD27A", "#12858C", "#0A4B55", "#2FB0A8", "#FFF1D2"

def pt(r, deg): a = math.radians(deg); return C + r * math.cos(a), C + r * math.sin(a)
def f(v): return f"{v:.1f}".rstrip("0").rstrip(".")
def poly(points): return "M" + "L".join(f"{f(x)},{f(y)}" for x, y in points) + "Z"

# dragon body: centre line along the rim, wiggling in radius, thick at the head and thin at the tail
T0, T1 = 125.0, 335.0
N = 140
centre = []
for i in range(N + 1):
    t = i / N; deg = T0 + (T1 - T0) * t
    r = 25.8 + 1.7 * math.sin(t * 2 * math.pi * 2.6 + 0.6) * (0.35 + 0.65 * t)
    w = 0.6 + 2.3 * t ** 0.9
    centre.append((deg, r, w))
outer = [pt(r + w, d) for d, r, w in centre]; inner = [pt(r - w, d) for d, r, w in centre][::-1]
body = poly(outer + inner)
# back spikes (outer side), every few steps from the middle to the neck
spikes = []
for i in range(30, N - 6, 7):
    d, r, w = centre[i]; d2 = centre[i + 2][0]
    spikes.append(poly([pt(r + w - 0.3, d), pt(r + w + 3.0 + 1.6 * i / N, (d + d2) / 2 + 2.0), pt(r + w - 0.3, d2)]))
# head at the end of the body, pointing along the rim (clockwise = direction of increasing angle)
hd, hr, hw = centre[-1]
tx = lambda r, d: pt(r, d)
head = poly([tx(hr - hw, hd - 4), tx(hr + hw + 0.5, hd - 3), tx(hr + hw + 1.0, hd + 4), tx(hr + 1.0, hd + 11), tx(hr - 1.8, hd + 10), tx(hr - hw - 0.5, hd + 3)])
horn1 = poly([tx(hr + hw, hd - 2), tx(hr + hw + 6.0, hd - 9), tx(hr + hw + 2.2, hd + 2)])
horn2 = poly([tx(hr + hw, hd + 1), tx(hr + hw + 5.0, hd - 3), tx(hr + hw + 1.2, hd + 5)])
whisker = f"M{f(tx(hr - 1.2, hd + 10)[0])},{f(tx(hr - 1.2, hd + 10)[1])}Q{f(tx(hr - 6, hd + 14)[0])},{f(tx(hr - 6, hd + 14)[1])} {f(tx(hr - 8, hd + 9)[0])},{f(tx(hr - 8, hd + 9)[1])}"
eye = tx(hr + 0.6, hd + 3)
# tail fin
td, tr, tw = centre[0]
tail = poly([tx(tr, td + 1), tx(tr + 4.5, td - 7), tx(tr + 0.5, td - 7.5), tx(tr - 4.5, td - 6), tx(tr - 0.5, td - 1)])

# small gulls in the sky: (x offset, y offset, half-width)
def gull(x, y, h): return f"M{f(x - h)},{f(y)}q{f(h * .5)},{f(-h * .75)} {f(h)},{f(h * .1)}q{f(h * .5)},{f(-h * .85)} {f(h)},{f(-h * .1)}"
BIRDS = [gull(C - 10, C - 13, 3.4), gull(C + 0.5, C - 17, 2.5), gull(C - 15, C - 6, 2.2), gull(C + 9, C - 11.5, 2.0), gull(C - 5, C - 8.5, 1.5)]
# scene inside the ring (radius 21 disc)
R = 20.5
disc = f"M{f(C - R)},{f(C)}a{R},{R} 0 1 0 {f(2 * R)},0a{R},{R} 0 1 0 {f(-2 * R)},0z"
sun_c = (C - 1.5, C - 3.5)
stripes = "".join(f'<rect x="{f(C - 16)}" y="{f(sun_c[1] + 2.5 + k * 3.2)}" width="32" height="{f(0.5 + 0.55 * k)}" fill="{ORANGE_D}"/>' for k in range(3))
mount1 = poly([(C + 2, C + 12), (C + 12.5, C - 8), (C + 18, C + 1), (C + 24, C + 12)])
mount2 = poly([(C - 6, C + 12), (C + 5, C - 1), (C + 11, C + 12)])
snow = poly([(C + 12.5, C - 8), (C + 15.3, C - 2.6), (C + 13.4, C - 3.6), (C + 12.5, C - 2), (C + 11.2, C - 3.8), (C + 9.7, C - 2.9)])
wave = lambda y, x0: f"M{f(C - 24)},{f(y)}" + "".join(f"q{f(2.5)},{f(-2.6)} {f(5)},0t{f(5)},0" for _ in range(10))
sea = f"M{f(C - 24)},{f(C + 6)}" + "".join(f"q2.5,-2.2 5,0t5,0" for _ in range(10)) + f"V{f(C + 24)}H{f(C - 24)}z"
waves = "".join(f'<path d="{wave(C + 10 + 4.2 * k, 0)}" fill="none" stroke="{TEAL_L if k else CREAM}" stroke-width="0.9" stroke-linecap="round" opacity="0.9"/>' for k in range(3))

svg_parts = f'''
<defs><clipPath id="d"><path d="{disc}"/></clipPath>
<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFB347"/><stop offset="1" stop-color="{ORANGE_D}"/></linearGradient>
<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1C9CA0"/><stop offset="0.6" stop-color="#0E6670"/><stop offset="1" stop-color="{TEAL_D}"/></linearGradient></defs>
'''
def foreground_svg():
    return f'''<g transform="translate(54 54) scale(1.4) translate(-54 -54)">
<circle cx="{C}" cy="{C}" r="22.6" fill="none" stroke="{CREAM}" stroke-width="1.3"/>
<g clip-path="url(#d)"><rect width="108" height="108" fill="url(#sky)"/>
<circle cx="{f(sun_c[0])}" cy="{f(sun_c[1])}" r="12.5" fill="{SUN}"/>{stripes}
<path d="{mount2}" fill="{TEAL}"/><path d="{mount1}" fill="{TEAL_D}"/><path d="{snow}" fill="{CREAM}"/>
<path d="{sea}" fill="{TEAL}"/>{waves}{"".join(f'<path d="{b}" fill="none" stroke="{TEAL_D}" stroke-width="0.95" stroke-linecap="round" stroke-linejoin="round"/>' for b in BIRDS)}</g>
</g>'''

if len(sys.argv) > 1:
    pathlib.Path(sys.argv[1]).write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="540" height="540">{svg_parts}<rect width="108" height="108" fill="url(#bg)"/>{foreground_svg()}</svg>', encoding="utf-8")


# ---- Android vector drawables ----
def run_xml(res):
    ring = lambda r, w, col, a="FF": f'<path android:fillColor="#00000000" android:strokeColor="#{a}{col[1:]}" android:strokeWidth="{w}" android:pathData="M{f(C - r)},{f(C)}a{r},{r} 0 1 0 {f(2 * r)},0a{r},{r} 0 1 0 {f(-2 * r)},0z"/>'
    fill = lambda d, col: f'<path android:fillColor="{col}" android:pathData="{d}"/>'
    head_xml = '<?xml version="1.0" encoding="utf-8"?>\n<!-- GENERATED by tools/make-icon.py: do not edit by hand. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
    grad = lambda a, b, c: f'<path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"><item android:color="{a}" android:offset="0.0"/><item android:color="{b}" android:offset="0.6"/><item android:color="{c}" android:offset="1.0"/></gradient></aapt:attr></path>'
    (res / "ic_launcher_background.xml").write_text(head_xml + grad("#FF1C9CA0", "#FF0E6670", "#FF0A4B55") + "\n</vector>\n", encoding="utf-8")
    sky = f'<path android:pathData="{disc}"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="{f(C - R)}" android:endX="0" android:endY="{f(C + R)}"><item android:color="#FFFFB347" android:offset="0.0"/><item android:color="#FF{ORANGE_D[1:]}" android:offset="1.0"/></gradient></aapt:attr></path>'
    clip = f'<clip-path android:pathData="{disc}"/>'
    stripe_d = lambda k: f"M{f(C - 16)},{f(sun_c[1] + 2.5 + k * 3.2)}h32v{f(0.5 + 0.55 * k)}h-32z"
    sun_d = f"M{f(sun_c[0] - 12.5)},{f(sun_c[1])}a12.5,12.5 0 1 0 25,0a12.5,12.5 0 1 0 -25,0z"
    wave_d = lambda k: wave(C + 10 + 4.2 * k, 0)
    fg = ['<group android:scaleX="1.4" android:scaleY="1.4" android:pivotX="54" android:pivotY="54">', ring(22.6, 1.3, CREAM), sky, "<group>" + clip, fill(sun_d, "#FF" + SUN[1:])]
    fg += [fill(stripe_d(k), "#FF" + ORANGE_D[1:]) for k in range(3)]
    fg += [fill(mount2, "#FF" + TEAL[1:]), fill(mount1, "#FF" + TEAL_D[1:]), fill(snow, "#FF" + CREAM[1:]), fill(sea, "#FF" + TEAL[1:])]
    fg += [f'<path android:fillColor="#00000000" android:strokeColor="#FF{(TEAL_L if k else CREAM)[1:]}" android:strokeWidth="0.9" android:strokeLineCap="round" android:pathData="{wave_d(k)}"/>' for k in range(3)]
    fg += [f'<path android:fillColor="#00000000" android:strokeColor="#FF{TEAL_D[1:]}" android:strokeWidth="0.95" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{b}"/>' for b in BIRDS]
    fg += ["</group>", "</group>"]
    (res / "ic_launcher_foreground.xml").write_text(head_xml + "\n".join(fg) + "\n</vector>\n", encoding="utf-8")
    # themed: one colour. The dragon and the inner ring solid; inside the disc only the sun is cut out of a solid mountain-and-sea silhouette.
    mono = ['<group android:scaleX="1.4" android:scaleY="1.4" android:pivotX="54" android:pivotY="54">', ring(22.6, 1.3, "#000000")]
    mono += [f'<path android:fillColor="#00000000" android:strokeColor="#FF000000" android:strokeWidth="1.1" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{b}"/>' for b in BIRDS]
    mono += ["<group>" + clip, f'<path android:fillColor="#FF000000" android:pathData="{mount1}{mount2}"/>', fill(sea, "#FF000000"), f'<path android:fillColor="#FF000000" android:pathData="M{f(sun_c[0] - 8)},{f(sun_c[1] + 6)}a8,8 0 1 1 16,0z"/>', "</group>", "</group>"]
    (res / "ic_launcher_monochrome.xml").write_text(head_xml + "\n".join(mono) + "\n</vector>\n", encoding="utf-8")

if len(sys.argv) > 2 and sys.argv[2] == "--write":
    run_xml(pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/res/drawable"); print("wrote the three icon drawables")
