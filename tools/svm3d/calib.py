"""Calibrate the four SVM fisheye cameras against the car's stitched top view.

The top view is the car's own projection of the fisheye images onto the ground, so every top
view pixel, ground or not, tells us where that ground point lands in one of the fisheyes.
We solve each camera's pose and lens by maximising the match between the top view and the
fisheye sampled at the projected ground points.

Car frame: X right, Y forward, Z up, origin on the ground under the car's centre.
"""
import json, sys
import numpy as np, cv2
from scipy.optimize import minimize

D = sys.argv[1]
TOP = cv2.imread(D + '/in/top.jpg')
TOPG = cv2.GaussianBlur(cv2.cvtColor(TOP, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5)
S = 64.0                     # top view pixels per metre (from the car icon vs IONIQ 5 size)
CU, CV = 315.0, 357.0        # car centre in the top view
L, W = 4.655, 1.89
ICON = (246, 384, 204, 510)  # x0, x1, y0, y1 of the car icon, with a margin

def top_to_ground(u, v):
    return (u - CU) / S, -(v - CV) / S

def rot(yaw, pitch, roll):
    """Camera-to-car rotation: columns are the camera's x (right), y (down), z (forward) axes."""
    f = np.array([np.sin(yaw) * np.cos(pitch), np.cos(yaw) * np.cos(pitch), np.sin(pitch)])
    up = np.array([0, 0, 1.0])
    r = np.cross(f, up); r /= np.linalg.norm(r)
    d = np.cross(f, r)
    # roll about the optical axis
    c, s = np.cos(roll), np.sin(roll)
    r2 = c * r + s * d
    d2 = -s * r + c * d
    return np.stack([r2, d2, f], axis=1)

def project(P, p):
    """Car-frame points (N,3) -> fisheye pixels (Kannala-Brandt). Returns u, v, valid."""
    x, y, z, yaw, pitch, roll, f, cx, cy, k1, k2, asp = p
    R = rot(yaw, pitch, roll)
    Pc = (P - np.array([x, y, z])) @ R
    rho = np.hypot(Pc[:, 0], Pc[:, 1]) + 1e-9
    th = np.arctan2(rho, Pc[:, 2])
    r = f * (th + k1 * th ** 3 + k2 * th ** 5)
    u = cx + r * Pc[:, 0] / rho
    v = cy + asp * r * Pc[:, 1] / rho
    return u, v, th < np.radians(100)

def region(name, X, Y):
    """Top view area each camera is responsible for, kept away from the blend seams."""
    xn, yn = X / (W / 2), Y / (L / 2)
    a, b = np.abs(xn), np.abs(yn)
    if name == 'front': m = (yn > 0) & (b > a + 0.35)
    elif name == 'rear': m = (yn < 0) & (b > a + 0.35)
    elif name == 'left': m = (xn < 0) & (a > b + 0.35)
    else: m = (xn > 0) & (a > b + 0.35)
    return m

H, Wt = TOPG.shape
uu, vv = np.meshgrid(np.arange(0, Wt, 2.0), np.arange(0, H, 2.0))
uu, vv = uu.ravel(), vv.ravel()
off_icon = ~((uu > ICON[0]) & (uu < ICON[1]) & (vv > ICON[2]) & (vv < ICON[3]))
X, Y = top_to_ground(uu, vv)

def ncc(a, b):
    a = a - a.mean(); b = b - b.mean()
    return float((a * b).sum() / (np.sqrt((a * a).sum() * (b * b).sum()) + 1e-9))

def make_cost(name, fish, prior):
    m = region(name, X, Y) & off_icon
    P = np.stack([X[m], Y[m], np.zeros(m.sum())], 1)
    tv = TOPG[vv[m].astype(int), uu[m].astype(int)]
    tgx = cv2.Sobel(TOPG, cv2.CV_32F, 1, 0)[vv[m].astype(int), uu[m].astype(int)]
    tgy = cv2.Sobel(TOPG, cv2.CV_32F, 0, 1)[vv[m].astype(int), uu[m].astype(int)]
    fg = cv2.GaussianBlur(fish, (0, 0), 1.5)
    fgx = cv2.Sobel(fg, cv2.CV_32F, 1, 0)
    fgy = cv2.Sobel(fg, cv2.CV_32F, 0, 1)
    mask = (cv2.erode((fish > 14).astype(np.uint8), np.ones((9, 9))) > 0)

    def cost(p):
        u, v, ok = project(P, p)
        ok &= (u > 1) & (u < fish.shape[1] - 2) & (v > 1) & (v < fish.shape[0] - 2)
        if ok.sum() < 500:
            return 2.0
        ui, vi = u[ok].astype(np.float32), v[ok].astype(np.float32)
        ok2 = mask[vi.astype(int), ui.astype(int)]
        if ok2.sum() < 500:
            return 2.0
        ui, vi = ui[ok2], vi[ok2]
        n = len(ui); pad = (-n) % 256
        mu = np.pad(ui, (0, pad)).reshape(-1, 256); mv = np.pad(vi, (0, pad)).reshape(-1, 256)
        smp = lambda img: cv2.remap(img, mu, mv, cv2.INTER_LINEAR).ravel()[:n]
        idx = np.nonzero(ok)[0][ok2]
        c1 = ncc(smp(fg), tv[idx])
        c2 = 0.5 * (ncc(smp(fgx), tgx[idx]) + ncc(smp(fgy), tgy[idx]))
        cover = ok2.sum() / max(1, len(P))
        # Soft priors: where the cameras physically are, and a plausible lens.
        pr = ((p[0] - prior[0]) / 0.25) ** 2 + ((p[1] - prior[1]) / 0.3) ** 2 + ((p[2] - prior[2]) / 0.25) ** 2
        pr += ((p[7] - 480) / 25) ** 2 + ((p[8] - 360) / 25) ** 2 + (p[10] / 0.05) ** 2 + ((p[11] - 1) / 0.05) ** 2
        return -(0.4 * c1 + 0.6 * c2) - 0.15 * cover + 0.004 * pr
    return cost

INIT = {
    'front': [0.0, 2.25, 0.65, 0.0, -0.45, 0.0],
    'rear': [0.0, -2.25, 0.95, np.pi, -0.6, 0.0],
    'left': [-0.98, 0.9, 1.0, -np.pi / 2, -1.0, 0.0],
    'right': [0.98, 0.9, 1.0, np.pi / 2, -1.0, 0.0],
}

results = {}
for name in ['front', 'rear', 'left', 'right']:
    fish = cv2.cvtColor(cv2.imread(D + '/in/' + name + '.jpg'), cv2.COLOR_BGR2GRAY).astype(np.float32)
    cost = make_cost(name, fish, INIT[name][:3])
    best = None
    x0, y0, z0, yaw0, pitch0, roll0 = INIT[name]
    # Coarse multi-start over the angles that matter most.
    for dyaw in np.radians([-20, 0, 20]):
        for pitch in pitch0 + np.radians([-25, -10, 5, 20]):
            for f in [240, 280, 320, 360]:
                p = [x0, y0, z0, yaw0 + dyaw, pitch, 0.0, f, 480, 360, 0.0, 0.0, 1.0]
                c = cost(p)
                if best is None or c < best[0]:
                    best = (c, p)
    p = np.array(best[1])
    print(name, 'start', round(best[0], 3), flush=True)
    for it in range(3):
        r = minimize(cost, p, method='Powell', options={'maxiter': 6000, 'xtol': 1e-4, 'ftol': 1e-5})
        p = r.x
        print(name, 'pass', it, round(r.fun, 4), flush=True)
    results[name] = {'params': p.tolist(), 'score': float(-r.fun)}
    print(name, np.round(p, 3).tolist(), flush=True)

json.dump({'S': S, 'CU': CU, 'CV': CV, 'cams': results}, open(D + '/calib.json', 'w'), indent=1)
