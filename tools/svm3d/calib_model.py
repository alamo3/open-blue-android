import json
import numpy as np

def rot(yaw, pitch, roll):
    f = np.array([np.sin(yaw) * np.cos(pitch), np.cos(yaw) * np.cos(pitch), np.sin(pitch)])
    r = np.cross(f, [0, 0, 1.0]); r /= np.linalg.norm(r)
    d = np.cross(f, r)
    c, s = np.cos(roll), np.sin(roll)
    return np.stack([c * r + s * d, -s * r + c * d, f], axis=1)

def project(P, p):
    x, y, z, yaw, pitch, roll, f, cx, cy, k1, k2, asp = p
    R = rot(yaw, pitch, roll)
    Pc = (np.asarray(P) - np.array([x, y, z])) @ R
    rho = np.hypot(Pc[:, 0], Pc[:, 1]) + 1e-9
    th = np.arctan2(rho, Pc[:, 2])
    r = f * (th + k1 * th ** 3 + k2 * th ** 5)
    return cx + r * Pc[:, 0] / rho, cy + asp * r * Pc[:, 1] / rho, th < np.radians(100)

def unproject(u, v, p):
    """Fisheye pixels -> unit rays in the car frame (inverting the lens numerically)."""
    x, y, z, yaw, pitch, roll, f, cx, cy, k1, k2, asp = p
    dx = (np.asarray(u, float) - cx); dy = (np.asarray(v, float) - cy) / asp
    r = np.hypot(dx, dy) + 1e-9
    th = r / f
    for _ in range(8):  # Newton on f*(th + k1 th^3 + k2 th^5) = r
        g = f * (th + k1 * th ** 3 + k2 * th ** 5) - r
        dg = f * (1 + 3 * k1 * th ** 2 + 5 * k2 * th ** 4)
        th = th - g / dg
    s = np.sin(th)
    dc = np.stack([s * dx / r, s * dy / r, np.cos(th)], -1)
    R = rot(yaw, pitch, roll)
    return dc @ R.T, np.array([x, y, z]), th

def load(D):
    return json.load(open(D + '/calib.json'))
