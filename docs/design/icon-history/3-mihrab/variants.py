# Historical copy, kept for reference: the sketch that drew three concepts side by side — the
# bead crescent (adopted, later refined in scripts/icon/generate.py), the mihrab and the ring of 33.
#     pip install cairosvg pillow && python3 variants.py OUTPUT_DIR
import math, io, sys, cairosvg
from PIL import Image, ImageDraw, ImageFont
CX, CY = 54.0, 54.0
WHITE, GOLD = "#FFFFFF", "#D8B562"
def f(v): return f"{v:.2f}"
def circ(x, y, r, fill, extra=""): return f'<circle cx="{f(x)}" cy="{f(y)}" r="{f(r)}" fill="{fill}" {extra}/>'
def crescent(mx, my, R, TH, D, r, fill=WHITE):
    kx, ky = mx + D*math.cos(TH), my + D*math.sin(TH)
    dx, dy = kx-mx, ky-my; dd = math.hypot(dx, dy)
    a = (R*R - r*r + dd*dd)/(2*dd); h = math.sqrt(R*R - a*a)
    px, py = mx + a*dx/dd, my + a*dy/dd
    t1 = (px + h*dy/dd, py - h*dx/dd); t2 = (px - h*dy/dd, py + h*dx/dd)
    return (f'<path d="M{f(t1[0])},{f(t1[1])} A{f(R)},{f(R)} 0 1,0 {f(t2[0])},{f(t2[1])} '
            f'A{f(r)},{f(r)} 0 0,1 {f(t1[0])},{f(t1[1])} Z" fill="{fill}"/>')

def tassel(x, y):
    """A short cord from the imam bead, ending in the fanned tuft a real tasbih has."""
    return (f'<path d="M{f(x)},{f(y)} L{f(x)},{f(y+3.4)}" stroke="{GOLD}" stroke-width="1.0" stroke-linecap="round"/>'
            f'<path d="M{f(x)},{f(y+2.8)} L{f(x-2.1)},{f(y+8.4)} Q{f(x)},{f(y+9.4)} {f(x+2.1)},{f(y+8.4)} Z" fill="{GOLD}"/>')

BG = ('<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#00897B"/>'
      '<stop offset="1" stop-color="#00594E"/></linearGradient></defs><rect width="108" height="108" fill="url(#g)"/>')

# ---------- A: the crescent IS the tasbih — beads sized to the crescent's own thickness
def variant_a():
    R, TH, D, r = 25.0, math.radians(-40), 9.0, 21.0
    KX, KY = CX + D*math.cos(TH), CY + D*math.sin(TH)
    def inner_dist(phi):
        # distance along the ray from the moon's centre to the cut circle, or None if the ray misses it
        ux, uy = math.cos(phi), math.sin(phi); ox, oy = CX-KX, CY-KY
        b = ox*ux + oy*uy; c = ox*ox + oy*oy - r*r; disc = b*b - c
        if disc < 0: return None
        return -b + math.sqrt(disc)       # far intersection = the cut's edge facing the crescent
    samples = []
    for i in range(2000):
        phi = TH + math.pi - math.pi + 2*math.pi*i/2000
        di = inner_dist(phi)
        if di is None or di >= R: continue
        t = R - di
        if t > 1.2: samples.append((phi, (R+di)/2, t))
    # walk the midline, placing beads sized to the local thickness with a fixed gap
    samples.sort(key=lambda s: ((s[0]-(TH+math.pi)+math.pi) % (2*math.pi)))
    beads, last = [], None
    for phi, m, t in samples:
        br = min(0.40*t, 3.4)
        x, y = CX + m*math.cos(phi), CY + m*math.sin(phi)
        if br < 1.35: continue
        if last is None or math.hypot(x-last[0], y-last[1]) >= last[2] + br + 0.9:
            beads.append((x, y, br)); last = (x, y, br)
    # the string ends at the lower tip: drop the last small beads there, then hang the imam bead
    lower_first = beads[-1][1] > beads[0][1]
    if not lower_first: beads.reverse()
    beads = beads[:-2]
    tip = beads[-1]
    ix, iy = tip[0] + 1.2, tip[1] + tip[2] + 3.2
    # centre the whole figure — beads, imam bead and tassel — on the icon
    xs = [x - br for x, y, br in beads] + [x + br for x, y, br in beads] + [ix - 2.3, ix + 2.3]
    ys = [y - br for x, y, br in beads] + [y + br for x, y, br in beads] + [iy + 2.3 + 9.4]
    ox, oy = CX - (min(xs) + max(xs))/2, CY - (min(ys) + max(ys))/2
    beads = [(x + ox, y + oy, br) for x, y, br in beads]; ix += ox; iy += oy; tip = beads[-1]
    out = [BG]
    for x, y, br in beads: out.append(circ(x, y, br, WHITE))
    out.append(f'<path d="M{f(tip[0])},{f(tip[1])} L{f(ix)},{f(iy)}" stroke="{GOLD}" stroke-width="0.8"/>')
    out.append(circ(ix, iy, 2.3, GOLD))
    out.append(tassel(ix, iy + 2.3))
    print("A extent", f(max(math.hypot(x-CX, y-CY)+br for x, y, br in beads)))
    return "".join(out)

# ---------- B: a mihrab arch, the crescent at its crown, the tasbih hanging like a lamp
def variant_b():
    out = [BG]
    L, Rr, top, bot = 35.5, 72.5, 25.0, 81.0
    spring = 50.0   # where the straight sides turn into the pointed arch
    arch = (f"M{f(L)},{f(bot)} L{f(L)},{f(spring)} "
            f"Q{f(L)},{f(top+8)} {f(CX)},{f(top)} Q{f(Rr)},{f(top+8)} {f(Rr)},{f(spring)} L{f(Rr)},{f(bot)}")
    out.append(f'<path d="{arch}" fill="#FFFFFF" fill-opacity="0.07" stroke="{GOLD}" stroke-width="1.4" stroke-linejoin="round"/>')
    out.append(crescent(CX, 43.5, 10.5, math.radians(-35), 4.6, 8.9))
    # tasbih: a hanging loop under the crescent, beads on a catenary-ish U
    # a U-shaped string, beads placed by arc length so the gaps are even
    w, depth, y0, n = 12.5, 12.5, 59.5, 11
    pts = [(CX + w*s_, y0 + depth*(1 - s_*s_)) for s_ in [-1 + 2*i/400 for i in range(401)]]
    seg = [0.0]
    for i in range(1, len(pts)): seg.append(seg[-1] + math.hypot(pts[i][0]-pts[i-1][0], pts[i][1]-pts[i-1][1]))
    beads = []
    for i in range(n):
        target = seg[-1]*i/(n-1)
        j = min(range(len(seg)), key=lambda k: abs(seg[k]-target)); beads.append(pts[j])
    out.append('<path d="M' + " L".join(f"{f(x)},{f(y)}" for x, y in pts[::10]) + f'" fill="none" stroke="{GOLD}" stroke-opacity="0.5" stroke-width="0.6"/>')
    for i, (x, y) in enumerate(beads):
        out.append(circ(x, y, 2.1 if i == n//2 else 1.35, GOLD))
    mid = beads[n//2]
    out.append(tassel(mid[0], mid[1] + 2.1))
    return "".join(out)

# ---------- C: the crescent with the ninety-nine — a ring of 33 beads (a third of the tasbih) in which the moon waxes
def variant_c():
    out = [BG]
    ring = 25.5; n = 33
    for i in range(n):
        phi = -math.pi/2 + 2*math.pi*i/n
        big = (i == 0)
        out.append(circ(CX + ring*math.cos(phi), CY + ring*math.sin(phi), 2.1 if big else 1.15, GOLD))
    out.append(crescent(CX, CY, 17.0, math.radians(-38), 7.0, 14.6))
    return "".join(out)

def render(body, size=512):
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">{body}</svg>'
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size))).convert("RGBA")

if __name__ == "__main__":
    S = sys.argv[1]
    imgs = [render(v()) for v in (variant_a, variant_b, variant_c)]
    mask = Image.new("L", (512, 512), 0); ImageDraw.Draw(mask).rounded_rectangle((0, 0, 511, 511), radius=150, fill=255)
    sheet = Image.new("RGBA", (512*3 + 80, 512 + 150), (245, 245, 245, 255))
    for i, im in enumerate(imgs):
        sheet.paste(im, (i*552, 0), mask)
        sm = im.resize((96, 96), Image.LANCZOS); cm = Image.new("L", (96, 96), 0); ImageDraw.Draw(cm).ellipse((0, 0, 95, 95), fill=255)
        sheet.paste(sm, (i*552 + 208, 540), cm)
    sheet.save(f"{S}/variants.png")
    for i, im in enumerate(imgs): im.save(f"{S}/variant-{'abc'[i]}.png")
