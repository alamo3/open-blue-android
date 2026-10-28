"""Writes an original soft studio environment (equirectangular Radiance .hdr) for the 3D car's
reflections: overhead softbox, two side strip lights, light-grey horizon, darker floor."""
import math, struct, sys

W, H = 512, 256

def smooth(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)

def radiance(az, el):
    # az: -pi..pi (0 = car's front), el: -pi/2..pi/2
    if el < 0:
        g = 0.10 + 0.12 * smooth(-0.35, 0.0, el)              # floor, brightening to the horizon
        return (g, g, g * 1.02)
    base = 0.42 + 0.22 * smooth(0.0, 1.2, el)                   # sky dome
    r = g = b = base
    top = smooth(1.05, 1.25, el) * 5.0                           # overhead softbox
    strips = 0.0
    for c in (math.pi / 2, -math.pi / 2):                        # strip lights either side
        d = abs((az - c + math.pi) % (2 * math.pi) - math.pi)
        strips += smooth(0.30, 0.18, d) * smooth(0.05, 0.12, el) * smooth(0.75, 0.6, el) * 3.2
    key = smooth(0.55, 0.35, abs(az - 0.6)) * smooth(0.25, 0.4, el) * smooth(0.95, 0.75, el) * 2.0  # front key
    return (r + top + strips + key * 1.03, g + top + strips + key, b + top * 1.02 + strips + key * 0.97)

def rgbe(c):
    m = max(c)
    if m < 1e-32:
        return bytes((0, 0, 0, 0))
    f, e = math.frexp(m)
    s = f * 256.0 / m
    return bytes((int(c[0] * s), int(c[1] * s), int(c[2] * s), e + 128))

out = bytearray(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n" % (H, W))
for y in range(H):
    el = math.pi / 2 - (y + 0.5) / H * math.pi
    for x in range(W):
        az = (x + 0.5) / W * 2 * math.pi - math.pi
        out += rgbe(radiance(az, el))
open(sys.argv[1], "wb").write(out)
print("wrote", sys.argv[1], len(out))
