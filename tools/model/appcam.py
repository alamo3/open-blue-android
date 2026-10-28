"""Python mirror of the app's orbit camera (ui/CarCamera.kt), for rendering the car as the app frames it."""
import math
import numpy as np

FOV_Y = 30.0
TARGET = (0.0, 0.58, -0.05)
BOX = [(x, y, z) for x in (-0.98, 0.98) for y in (0.0, 1.55) for z in (-2.36, 2.35)]


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = map(np.array, (eye, target, up))
    f = target - eye; f /= np.linalg.norm(f)
    s = np.cross(f, up); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    m = np.identity(4)
    m[0, :3], m[1, :3], m[2, :3] = s, u, -f
    m[:3, 3] = -m[:3, :3] @ eye
    return m


def perspective(fovy, aspect, near=0.1, far=50.0):
    f = 1 / math.tan(math.radians(fovy) / 2)
    m = np.zeros((4, 4))
    m[0, 0] = f / aspect; m[1, 1] = f
    m[2, 2] = (far + near) / (near - far); m[2, 3] = 2 * far * near / (near - far)
    m[3, 2] = -1
    return m


def eye(az, el, d):
    a, e = math.radians(az), math.radians(el)
    return (TARGET[0] + d * math.cos(e) * math.sin(a), TARGET[1] + d * math.sin(e), TARGET[2] + d * math.cos(e) * math.cos(a))


def fits(az, el, d, aspect, mx=1.0, my=0.92):
    vp = perspective(FOV_Y, aspect) @ look_at(eye(az, el, d), TARGET)
    for p in BOX:
        c = vp @ np.array([*p, 1.0])
        if c[3] <= 0: return False
        x, y = c[0] / c[3], c[1] / c[3]
        if abs(x) > mx or abs(y) > my: return False
    return True


def fit_distance(aspect):
    """Smallest distance at which the car fits at every angle (so orbiting never clips it)."""
    lo, hi = 2.0, 30.0
    for _ in range(40):
        mid = (lo + hi) / 2
        if all(fits(az, el, mid, aspect) for el in (12.0,) for az in range(0, 360, 15)): hi = mid
        else: lo = mid
    return hi


def project(points, az, el, d, w, h):
    vp = perspective(FOV_Y, w / h) @ look_at(eye(az, el, d), TARGET)
    out = []
    for p in points:
        c = vp @ np.array([*p, 1.0])
        out.append(((c[0] / c[3] * 0.5 + 0.5) * w, (0.5 - c[1] / c[3] * 0.5) * h))
    return out
