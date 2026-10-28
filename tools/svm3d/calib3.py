"""Calibration refinement by correspondences: warp each fisheye onto the top view with the current
estimate, measure the leftover misalignment with optical flow, turn that into ground-point to
fisheye-pixel matches, and solve all poses plus a shared lens by least squares. Repeat."""
import json, sys
import numpy as np, cv2
from scipy.optimize import least_squares
sys.path.insert(0, sys.argv[1])
from calib_model import project

D = sys.argv[1]
cal = json.load(open(D + '/calib.json'))
S, CU, CV = cal['S'], cal['CU'], cal['CV']
L, W = 4.655, 1.89
ICON = (246, 384, 204, 510)
NAMES = ['front', 'rear', 'left', 'right']
top = cv2.cvtColor(cv2.imread(D + '/in/top.jpg'), cv2.COLOR_BGR2GRAY)
H, Wt = top.shape
uu, vv = np.meshgrid(np.arange(Wt, dtype=np.float32), np.arange(H, dtype=np.float32))
X, Y = (uu - CU) / S, -(vv - CV) / S
icon = (uu > ICON[0]) & (uu < ICON[1]) & (vv > ICON[2]) & (vv < ICON[3])

def region(name):
    xn, yn = X / (W / 2), Y / (L / 2)
    a, b = np.abs(xn), np.abs(yn)
    m = {'front': (yn > 0) & (b > a + 0.2), 'rear': (yn < 0) & (b > a + 0.2),
         'left': (xn < 0) & (a > b + 0.2), 'right': (xn > 0) & (a > b + 0.2)}[name]
    return m & ~icon

FISH = {n: cv2.cvtColor(cv2.imread(D + '/in/' + n + '.jpg'), cv2.COLOR_BGR2GRAY) for n in NAMES}
dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)

def matches(name, p):
    P = np.stack([X.ravel(), Y.ravel(), np.zeros(X.size)], 1)
    u, v, ok = project(P, p)
    mu = np.where(ok, u, -1).reshape(H, W_).astype(np.float32)
    mv = np.where(ok, v, -1).reshape(H, W_).astype(np.float32)
    warped = cv2.remap(FISH[name], mu, mv, cv2.INTER_LINEAR, borderValue=0)
    reg = region(name) & (warped > 8)
    a = cv2.equalizeHist(np.where(reg, top, 0).astype(np.uint8))
    b = cv2.equalizeHist(np.where(reg, warped, 0).astype(np.uint8))
    f = dis.calc(a, b, None)        # top(x) ~ warped(x + f)
    fb = dis.calc(b, a, None)
    xs, ys = uu + f[..., 0], vv + f[..., 1]
    back = cv2.remap(fb, xs, ys, cv2.INTER_LINEAR)
    consistent = np.hypot(f[..., 0] + back[..., 0], f[..., 1] + back[..., 1]) < 1.0
    g = cv2.Sobel(top, cv2.CV_32F, 1, 0) ** 2 + cv2.Sobel(top, cv2.CV_32F, 0, 1) ** 2
    good = reg & consistent & (g > np.percentile(g[reg], 60)) & (np.hypot(*f.transpose(2, 0, 1)) < 40)
    good &= cv2.erode(reg.astype(np.uint8), np.ones((15, 15))) > 0
    ys_, xs_ = np.nonzero(good)
    sel = np.arange(len(xs_))[::7]
    ys_, xs_ = ys_[sel], xs_[sel]
    # Fisheye pixel that the matched warped location came from.
    fu = cv2.remap(mu, xs[ys_, xs_].reshape(-1, 1), ys[ys_, xs_].reshape(-1, 1), cv2.INTER_LINEAR).ravel()
    fv = cv2.remap(mv, xs[ys_, xs_].reshape(-1, 1), ys[ys_, xs_].reshape(-1, 1), cv2.INTER_LINEAR).ravel()
    ok = (fu > 0) & (fv > 0)
    G = np.stack([X[ys_, xs_], Y[ys_, xs_], np.zeros(len(xs_))], 1)[ok]
    med = float(np.median(np.hypot(f[..., 0], f[..., 1])[good])) if good.any() else -1
    return G, np.stack([fu[ok], fv[ok]], 1), med

W_ = Wt
def unpack(q):
    lens = list(q[:6])
    return {n: list(q[6 + 6 * i: 12 + 6 * i]) + lens for i, n in enumerate(NAMES)}

q = list(cal['cams']['front']['params'][6:12])
for n in NAMES:
    q += cal['cams'][n]['params'][:6]
q = np.array(q, float)

for it in range(4):
    cams = unpack(q)
    M = {}
    for n in NAMES:
        G, F, med = matches(n, cams[n])
        M[n] = (G, F)
        print(f'iter {it} {n}: {len(G)} matches, median flow {med:.1f}px', flush=True)

    def resid(q):
        cams = unpack(q)
        r = []
        for n in NAMES:
            G, F = M[n]
            u, v, _ = project(G, cams[n])
            r.append(u - F[:, 0]); r.append(v - F[:, 1])
        lens = q[:6]
        r.append(np.array([(lens[4]) / 0.02, (lens[5] - 1) / 0.02]))
        return np.concatenate(r)
    sol = least_squares(resid, q, loss='huber', f_scale=2.0, max_nfev=400)
    q = sol.x
    res = resid(q)[:-2]
    print(f'iter {it}: rms {np.sqrt(np.mean(res ** 2)):.2f}px, median abs {np.median(np.abs(res)):.2f}px', flush=True)

cams = unpack(q)
for n in NAMES:
    print(n, 'pos', np.round(cams[n][:3], 3).tolist(), 'ypr', np.round(np.degrees(cams[n][3:6]), 1).tolist())
print('lens', np.round(q[:6], 3).tolist())
cal['cams'] = {n: {'params': list(map(float, cams[n]))} for n in NAMES}
json.dump(cal, open(D + '/calib.json', 'w'), indent=1)
