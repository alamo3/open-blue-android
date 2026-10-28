"""Joint refinement: one lens model shared by all four cameras, per-camera pose, coarse-to-fine."""
import json, sys
import numpy as np, cv2
from scipy.optimize import minimize
sys.path.insert(0, sys.argv[1])
from calib_model import project

D = sys.argv[1]
cal = json.load(open(D + '/calib.json'))
S, CU, CV = cal['S'], cal['CU'], cal['CV']
L, W = 4.655, 1.89
ICON = (246, 384, 204, 510)
TOPC = cv2.imread(D + '/in/top.jpg')
TOP0 = cv2.cvtColor(TOPC, cv2.COLOR_BGR2GRAY).astype(np.float32)
H, Wt = TOP0.shape
uu, vv = np.meshgrid(np.arange(0, Wt, 3.0), np.arange(0, H, 3.0))
uu, vv = uu.ravel(), vv.ravel()
X, Y = (uu - CU) / S, -(vv - CV) / S
off_icon = ~((uu > ICON[0]) & (uu < ICON[1]) & (vv > ICON[2]) & (vv < ICON[3]))
NAMES = ['front', 'rear', 'left', 'right']

def region(name):
    xn, yn = X / (W / 2), Y / (L / 2)
    a, b = np.abs(xn), np.abs(yn)
    m = {'front': (yn > 0) & (b > a + 0.3), 'rear': (yn < 0) & (b > a + 0.3),
         'left': (xn < 0) & (a > b + 0.3), 'right': (xn > 0) & (a > b + 0.3)}[name]
    return m & off_icon

FISH = {n: cv2.cvtColor(cv2.imread(D + '/in/' + n + '.jpg'), cv2.COLOR_BGR2GRAY).astype(np.float32) for n in NAMES}
MASK = {n: cv2.erode((FISH[n] > 14).astype(np.uint8), np.ones((9, 9))) > 0 for n in NAMES}
REG = {n: region(n) for n in NAMES}

def ncc(a, b):
    a = a - a.mean(); b = b - b.mean()
    return float((a * b).sum() / (np.sqrt((a * a).sum() * (b * b).sum()) + 1e-9))

def prep(sigma):
    top = cv2.GaussianBlur(TOP0, (0, 0), sigma)
    tg = (cv2.Sobel(top, cv2.CV_32F, 1, 0), cv2.Sobel(top, cv2.CV_32F, 0, 1))
    fish = {}
    for n in NAMES:
        f = cv2.GaussianBlur(FISH[n], (0, 0), sigma * 1.2)
        fish[n] = (f, cv2.Sobel(f, cv2.CV_32F, 1, 0), cv2.Sobel(f, cv2.CV_32F, 0, 1))
    return top, tg, fish

def cam_score(n, p, top, tg, fish):
    m = REG[n]
    P = np.stack([X[m], Y[m], np.zeros(m.sum())], 1)
    u, v, ok = project(P, p)
    f0 = fish[n][0]
    ok &= (u > 1) & (u < f0.shape[1] - 2) & (v > 1) & (v < f0.shape[0] - 2)
    if ok.sum() < 300: return -1.0, 0.0
    ok[ok] = MASK[n][v[ok].astype(int), u[ok].astype(int)]
    if ok.sum() < 300: return -1.0, 0.0
    ui, vi = u[ok].astype(np.float32), v[ok].astype(np.float32)
    k = len(ui); pad = (-k) % 256
    mu = np.pad(ui, (0, pad)).reshape(-1, 256); mv = np.pad(vi, (0, pad)).reshape(-1, 256)
    smp = lambda img: cv2.remap(img, mu, mv, cv2.INTER_LINEAR).ravel()[:k]
    tu, tv_ = uu[m][ok].astype(int), vv[m][ok].astype(int)
    c1 = ncc(smp(fish[n][0]), top[tv_, tu])
    c2 = 0.5 * (ncc(smp(fish[n][1]), tg[0][tv_, tu]) + ncc(smp(fish[n][2]), tg[1][tv_, tu]))
    return 0.35 * c1 + 0.65 * c2, ok.sum() / len(P)

# Parameter vector: shared lens [f, cx, cy, k1, k2, asp] + 4 x [x, y, z, yaw, pitch, roll]
def unpack(q):
    lens = q[:6]
    cams = {}
    for i, n in enumerate(NAMES):
        e = q[6 + 6 * i: 12 + 6 * i]
        cams[n] = list(e) + [lens[0], lens[1], lens[2], lens[3], lens[4], lens[5]]
    return cams

PRIOR = {'front': [0, 2.2, 0.65], 'rear': [0, -2.25, 0.95], 'left': [-0.97, 0.95, 1.0], 'right': [0.97, 0.95, 1.0]}

def total(q, prepd):
    cams = unpack(q)
    s = 0
    for n in NAMES:
        sc, cov = cam_score(n, cams[n], *prepd)
        s += sc + 0.1 * cov
        px = PRIOR[n]
        s -= 0.003 * (((cams[n][0] - px[0]) / 0.2) ** 2 + ((cams[n][1] - px[1]) / 0.3) ** 2 + ((cams[n][2] - px[2]) / 0.2) ** 2)
    lens = q[:6]
    s -= 0.003 * (((lens[1] - 480) / 20) ** 2 + ((lens[2] - 360) / 20) ** 2 + (lens[4] / 0.05) ** 2 + ((lens[5] - 1) / 0.04) ** 2)
    return -s / 4

old = {n: cal['cams'][n]['params'] for n in NAMES}
# Start: lens from the front camera (best constrained), poses from the first pass; try the
# right camera both as found and as the mirror image of the left.
lens0 = [290.0, 480.0, 360.0, 0.0, 0.0, 1.0]
def q_from(right_pose):
    q = list(lens0)
    for n in NAMES:
        q += (right_pose if n == 'right' else old[n][:6])
    return np.array(q, float)
lp = old['left'][:6]
mirror = [-lp[0], lp[1], lp[2], -lp[3], lp[4], -lp[5]]
starts = [q_from(old['right'][:6]), q_from(mirror)]

best = None
for q in starts:
    for sigma in [6.0, 3.0, 1.5]:
        prepd = prep(sigma)
        r = minimize(total, q, args=(prepd,), method='Powell', options={'maxiter': 20000, 'xtol': 1e-4, 'ftol': 1e-6})
        q = r.x
        print('sigma', sigma, 'cost', round(r.fun, 4), flush=True)
    if best is None or r.fun < best[0]:
        best = (r.fun, q)
q = best[1]
cams = unpack(q)
prepd = prep(1.5)
for n in NAMES:
    print(n, 'score', round(cam_score(n, cams[n], *prepd)[0], 3),
          'pos', np.round(cams[n][:3], 2).tolist(), 'yaw/pitch/roll', np.round(np.degrees(cams[n][3:6]), 1).tolist())
print('lens', np.round(q[:6], 3).tolist())
cal['cams'] = {n: {'params': cams[n]} for n in NAMES}
json.dump(cal, open(D + '/calib.json', 'w'), indent=1)
