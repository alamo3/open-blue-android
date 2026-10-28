"""Depth for each camera: flatten the fisheye into ordinary perspective crops, run Depth Anything V2,
then scale its relative depth to metres using the ground, whose distance we know exactly from
the calibration. Writes crops/<cam>_<k>.npz and preview images.

usage: depth.py DIR [model]
"""
import sys, os, json
import numpy as np, cv2, onnxruntime as ort
sys.path.insert(0, sys.argv[1])
from calib_model import project, rot

D = sys.argv[1]
MODEL = sys.argv[2] if len(sys.argv) > 2 else 'vitl'
cal = json.load(open(D + '/calib.json'))
os.makedirs(D + '/crops', exist_ok=True)
sess = ort.InferenceSession(f'{D}/models/depth_anything_v2_{MODEL}.onnx', providers=['CPUExecutionProvider'])
N = 518
FOV = np.radians(100)
FP = (N / 2) / np.tan(FOV / 2)
L, W = 4.655, 1.89

def crop_rays(yaw, pitch):
    """Unit rays (N,N,3) in the car frame for a square pinhole view."""
    j, i = np.meshgrid(np.arange(N) + 0.5, np.arange(N) + 0.5)
    dc = np.stack([(j - N / 2) / FP, (i - N / 2) / FP, np.ones_like(j)], -1)
    dc /= np.linalg.norm(dc, axis=-1, keepdims=True)
    R = rot(yaw, pitch, 0.0)
    return dc @ R.T, R[:, 2]

def run_depth(rgb):
    x = cv2.resize(rgb, (N, N)).astype(np.float32) / 255.0
    x = (x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    x = x.transpose(2, 0, 1)[None].astype(np.float32)
    return sess.run(None, {sess.get_inputs()[0].name: x})[0][0]

def fit_affine(disp, invz, rng):
    """Robust fit disp ~ a*invz + b (RANSAC, then least squares on inliers)."""
    best, n = None, len(disp)
    tol = 0.04 * (np.percentile(disp, 95) - np.percentile(disp, 5) + 1e-6)
    for _ in range(400):
        i, k = rng.integers(0, n, 2)
        if abs(invz[i] - invz[k]) < 1e-3: continue
        a = (disp[i] - disp[k]) / (invz[i] - invz[k])
        if a <= 0: continue
        b = disp[i] - a * invz[i]
        inl = np.abs(a * invz + b - disp) < tol
        if best is None or inl.sum() > best[0]:
            best = (inl.sum(), inl)
    inl = best[1]
    A = np.stack([invz[inl], np.ones(inl.sum())], 1)
    a, b = np.linalg.lstsq(A, disp[inl], rcond=None)[0]
    return a, b, inl.mean()

# Which way to look from each camera: a few crops spread around its view.
PLAN = {
    'front': [(-60, -12), (0, -12), (60, -12)],
    'rear': [(-60, -22), (0, -22), (60, -22)],
    'left': [(-55, -30), (0, -38), (55, -30)],
    'right': [(-55, -30), (0, -38), (55, -30)],
}
rng = np.random.default_rng(0)
for name, plan in PLAN.items():
    p = cal['cams'][name]['params']
    C = np.array(p[:3])
    fish = cv2.cvtColor(cv2.imread(f'{D}/in/{name}.jpg'), cv2.COLOR_BGR2RGB)
    # The lens's valid area: everything except the black border (found by flooding in from the
    # edges, so dark things inside the picture, like tyres at night, stay valid).
    dark = (fish.max(2) <= 14).astype(np.uint8)
    nlab, lab = cv2.connectedComponents(dark)
    edge = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    border = np.isin(lab, list(edge)) & (dark > 0)
    fmask = cv2.erode((~border).astype(np.uint8), np.ones((9, 9))) > 0
    base_yaw = p[3]
    for k, (dy, pt) in enumerate(plan):
        rays, axis = crop_rays(base_yaw + np.radians(dy), np.radians(pt))
        P = C + rays.reshape(-1, 3)
        u, v, ok = project(P, p)
        ok &= (u >= 0) & (u < fish.shape[1] - 1) & (v >= 0) & (v < fish.shape[0] - 1)
        ui = np.clip(u, 0, fish.shape[1] - 1).astype(np.float32).reshape(N, N)
        vi = np.clip(v, 0, fish.shape[0] - 1).astype(np.float32).reshape(N, N)
        img = cv2.remap(fish, ui, vi, cv2.INTER_LINEAR)
        valid = ok.reshape(N, N) & cv2.remap(fmask.astype(np.uint8), ui, vi, cv2.INTER_NEAREST).astype(bool)
        # Rays that land on our own car (ground hit inside the footprint, or the body itself).
        dz = rays[..., 2]
        tg = np.where(dz < -1e-3, -C[2] / np.minimum(dz, -1e-3), np.inf)
        gx, gy = C[0] + rays[..., 0] * tg, C[1] + rays[..., 1] * tg
        mx, my = (0.08, 0.08) if name in ('left', 'right') else (0.08, 0.22)
        body = (np.abs(gx) < W / 2 + mx) & (np.abs(gy) < L / 2 + my)
        # Also rays that run into the car's body box (e.g. a mirror camera looking along the flank).
        lo = np.array([-W / 2 - 0.02, -L / 2, 0.0]); hi = np.array([W / 2 + 0.02, L / 2, 1.6])
        if name == 'front': hi[1] = min(hi[1], C[1] - 0.05)
        if name == 'rear': lo[1] = max(lo[1], C[1] + 0.05)
        if name == 'left': lo[0] = max(lo[0], C[0] + 0.08)
        if name == 'right': hi[0] = min(hi[0], C[0] - 0.08)
        with np.errstate(divide='ignore', invalid='ignore'):
            t1 = (lo - C) / rays; t2 = (hi - C) / rays
        tmin = np.nanmax(np.minimum(t1, t2), -1); tmax = np.nanmin(np.maximum(t1, t2), -1)
        body |= (tmax >= np.maximum(tmin, 0)) & (tmax > 0)
        valid &= ~body
        if valid.mean() < 0.1:
            continue
        disp = run_depth(img)
        disp = cv2.resize(disp, (N, N))
        cosax = rays @ axis
        horiz = np.hypot(gx - C[0], gy - C[1])
        ground = valid & np.isfinite(tg) & (horiz > 0.6) & (horiz < 9)
        zg = tg * cosax
        a, b, frac = fit_affine(disp[ground], 1 / zg[ground], rng)
        # Refine: the model's depth drifts a little across a wide crop, so let the offset vary
        # smoothly with position (fitted on the ground, where the truth is known).
        jj_, ii_ = np.meshgrid(np.arange(N) / N - 0.5, np.arange(N) / N - 0.5)
        gsel = ground & np.isfinite(zg)
        inl = gsel & (np.abs(a / zg + b - disp) < 0.06 * (np.percentile(disp[gsel], 95) - np.percentile(disp[gsel], 5)))
        for _ in range(3):
            A = np.stack([1 / zg[inl], np.ones(inl.sum()), ii_[inl], jj_[inl], ii_[inl] * jj_[inl], ii_[inl] ** 2, jj_[inl] ** 2], 1)
            coef = np.linalg.lstsq(A, disp[inl], rcond=None)[0]
            off = coef[1] + coef[2] * ii_ + coef[3] * jj_ + coef[4] * ii_ * jj_ + coef[5] * ii_ ** 2 + coef[6] * jj_ ** 2
            pred = coef[0] / np.where(np.isfinite(zg), zg, 1e9) + off
            spread = np.percentile(disp[gsel], 95) - np.percentile(disp[gsel], 5)
            inl = gsel & (np.abs(pred - disp) < 0.05 * spread)
        a = coef[0]
        with np.errstate(divide='ignore', invalid='ignore'):
            z = a / (disp - off)
        z = np.where((disp > off) & (z > 0), z, 1e3)
        frac = inl.sum() / max(1, gsel.sum())
        t = np.minimum(z / cosax, 60.0)
        print(f'{name}[{k}] ground pts {ground.sum()} inliers {frac:.2f} a={a:.3f} b={b:.3f} median t={np.median(t[valid]):.1f}m', flush=True)
        np.savez_compressed(f'{D}/crops/{name}_{k}.npz', img=img, rays=rays.astype(np.float32), t=t.astype(np.float32),
                            valid=valid, C=C, tg=tg.astype(np.float32),
                            center_az=np.degrees(base_yaw + np.radians(dy)),
                            sibling_az=np.array([np.degrees(base_yaw + np.radians(o)) for o, _ in plan if o != dy]))
        vis = np.clip(1 - np.log(np.maximum(t, 0.5) / 0.5) / np.log(60), 0, 1)
        vis = cv2.applyColorMap((vis * 255).astype(np.uint8), cv2.COLORMAP_INFERNO)
        vis[~valid] = 0
        cv2.imwrite(f'{D}/crops/{name}_{k}.jpg', np.hstack([cv2.cvtColor(img, cv2.COLOR_RGB2BGR), vis]))
