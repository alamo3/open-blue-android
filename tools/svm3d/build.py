"""Build the 3D surroundings from the depth crops: textured obstacle meshes, cut where the depth
jumps (so things stand up instead of smearing), plus a ground texture from the four cameras with
occlusion (ground hidden behind a car stays blank instead of showing the car smeared on it).

Writes scene/scene.obj (+ .mtl, textures). Car frame: X right, Y forward, Z up, metres.
"""
import sys, os, glob, json
import numpy as np, cv2
sys.path.insert(0, sys.argv[1])
from calib_model import project

D = sys.argv[1]
OUT = D + '/scene'
os.makedirs(OUT, exist_ok=True)
cal = json.load(open(D + '/calib.json'))
L, W = 4.655, 1.89
STEP = 3
FAR = 35.0
SECT = {'front': 0, 'right': 90, 'rear': 180, 'left': -90}

crops = []
for f in sorted(glob.glob(D + '/crops/*.npz')):
    z = dict(np.load(f))
    z['name'] = os.path.basename(f)[:-4]
    z['cam'] = z['name'].split('_')[0]
    crops.append(z)

def azim(P):
    return np.degrees(np.arctan2(P[..., 0], P[..., 1]))

def in_sector(cam, P, margin=8):
    d = (azim(P) - SECT[cam] + 180) % 360 - 180
    return np.abs(d) < 45 + margin

# ---- exposure: one gain per camera so the ground matches where cameras overlap
GR = 0.03; EXT = 12.0
n = int(2 * EXT / GR)
gx, gy = np.meshgrid((np.arange(n) + 0.5) * GR - EXT, EXT - (np.arange(n) + 0.5) * GR)
G = np.stack([gx.ravel(), gy.ravel(), np.zeros(gx.size)], 1)
outside = ~((np.abs(gx) < W / 2 + 0.05) & (np.abs(gy) < L / 2 + 0.05)).ravel()

# Height map of obstacles, for occlusion.
HR = 0.1; hn = int(2 * EXT / HR)
hmap = np.zeros((hn, hn), np.float32)
obstacle_pts = []
for c in crops:
    P = c['C'] + c['rays'] * c['t'][..., None]
    near_car = (np.abs(P[..., 0]) < W / 2 + 0.4) & (np.abs(P[..., 1]) < L / 2 + 0.4)
    keep = c['valid'] & (c['t'] < 25) & (P[..., 2] > 0.25) & in_sector(c['cam'], P) & ~near_car
    obstacle_pts.append(P[keep])
OP = np.concatenate(obstacle_pts)
ix = ((OP[:, 0] + EXT) / HR).astype(int); iy = ((EXT - OP[:, 1]) / HR).astype(int)
ok = (ix >= 0) & (ix < hn) & (iy >= 0) & (iy < hn)
np.maximum.at(hmap, (iy[ok], ix[ok]), np.minimum(OP[ok, 2], 3.0))
cnt = np.zeros((hn, hn), np.int32); np.add.at(cnt, (iy[ok], ix[ok]), 1)
hmap[cnt < 4] = 0  # a few stray points don't make a wall

def visible(Cpos, pts):
    """Is each ground point visible from the camera, given the obstacle height map?"""
    vis = np.ones(len(pts), bool)
    for s in np.linspace(0.04, 0.96, 28):
        q = Cpos[None, :2] * (1 - s) + pts[:, :2] * s
        h = Cpos[2] * (1 - s)
        qi = ((q[:, 0] + EXT) / HR).astype(int); qj = ((EXT - q[:, 1]) / HR).astype(int)
        inb = (qi >= 0) & (qi < hn) & (qj >= 0) & (qj < hn)
        hh = np.zeros(len(pts), np.float32)
        hh[inb] = hmap[qj[inb], qi[inb]]
        vis &= ~(hh > h + 0.05)
    return vis

def depth_visible(cam, Cpos, pts):
    """Shadow-map style test: a ground point is seen if the camera's depth along that ray reaches
    it (nothing nearer is in the way). Uses whichever of the camera's crops owns that direction."""
    from calib_model import rot
    vis = np.zeros(len(pts), bool); decided = np.zeros(len(pts), bool)
    d = pts - Cpos; dist = np.linalg.norm(d, axis=1); dn = d / dist[:, None]
    az = np.degrees(np.arctan2(dn[:, 0], dn[:, 1]))
    mine_crops = [c for c in crops if c['cam'] == cam]
    best = np.full(len(pts), 1e9)
    for c in mine_crops:
        N = c['t'].shape[0]
        # crop camera axes from its rays at the centre and edges
        ax = c['rays'][N // 2, N // 2]; rx = c['rays'][N // 2, -1] - c['rays'][N // 2, 0]; rx /= np.linalg.norm(rx)
        dy = np.cross(ax, rx)
        zc = dn @ ax
        fp = (N / 2) / np.tan(np.radians(50))
        j = fp * (dn @ rx) / np.maximum(zc, 1e-6) + N / 2
        i = fp * (dn @ dy) / np.maximum(zc, 1e-6) + N / 2
        inb = (zc > 0.1) & (i >= 0) & (i < N - 1) & (j >= 0) & (j < N - 1)
        off = np.abs((az - c['center_az'] + 180) % 360 - 180)
        use = inb & (off < best)
        if not use.any(): continue
        ii, jj = i[use].astype(int), j[use].astype(int)
        tv = c['t'][ii, jj]; vv_ = c['valid'][ii, jj]
        vis[use] = vv_ & (tv > dist[use] * 0.88)
        best[use] = off[use]
        decided[use] = True
    return vis & decided

samples = {}
for name in SECT:
    p = cal['cams'][name]['params']
    C = np.array(p[:3])
    fish = cv2.cvtColor(cv2.imread(f'{D}/in/{name}.jpg'), cv2.COLOR_BGR2RGB).astype(np.float32)
    dark = (fish.max(2) <= 14).astype(np.uint8)
    _, lab = cv2.connectedComponents(dark)
    edge = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    fmask = cv2.erode((~(np.isin(lab, list(edge)) & (dark > 0))).astype(np.uint8), np.ones((9, 9))) > 0
    u, v, ok = project(G, p)
    ok &= (u > 0) & (u < fish.shape[1] - 1) & (v > 0) & (v < fish.shape[0] - 1) & outside
    ok[ok] &= fmask[v[ok].astype(int), u[ok].astype(int)]
    dist = np.hypot(G[:, 0] - C[0], G[:, 1] - C[1])
    ok &= dist < 14
    ok[ok] &= depth_visible(name, C, G[ok])
    col = np.zeros((len(G), 3), np.float32)
    k = len(u[ok]); pad = (-k) % 256
    mu = np.pad(u[ok].astype(np.float32), (0, pad)).reshape(-1, 256)
    mv = np.pad(v[ok].astype(np.float32), (0, pad)).reshape(-1, 256)
    col[ok] = cv2.remap(fish, mu, mv, cv2.INTER_LINEAR).reshape(-1, 3)[:k]
    # Prefer the camera that sees a spot most closely and most head-on.
    w = np.where(ok, np.exp(-dist / 1.2), 0)
    samples[name] = (col, w, ok)
    print('ground', name, 'cells', ok.sum(), flush=True)

# Gains from overlaps (log-luminance least squares, front fixed at 1).
names = list(SECT)
rows, rhs = [], []
for i in range(4):
    for j in range(i + 1, 4):
        a, b = names[i], names[j]
        both = samples[a][2] & samples[b][2]
        if both.sum() < 200: continue
        la = np.log(samples[a][0][both].mean(1) + 5).mean(); lb = np.log(samples[b][0][both].mean(1) + 5).mean()
        r = np.zeros(4); r[i], r[j] = 1, -1
        rows.append(r); rhs.append(lb - la)
rows.append(np.eye(4)[0]); rhs.append(0)
lg = np.linalg.lstsq(np.array(rows), np.array(rhs), rcond=None)[0]
gain = {nm: float(np.exp(g)) for nm, g in zip(names, lg)}
print('gains', {k: round(v, 2) for k, v in gain.items()})

acc = np.zeros((len(G), 3)); wsum = np.zeros(len(G))
for name, (col, w, ok) in samples.items():
    acc += col * gain[name] * w[:, None]; wsum += w
seen = wsum > 1e-4
ground = np.zeros((len(G), 3))
ground[seen] = acc[seen] / wsum[seen, None]
base = np.median(ground[seen], 0) * 0.55
alpha = cv2.GaussianBlur(seen.reshape(n, n).astype(np.float32), (0, 0), 2.0).ravel()[:, None]
ground = ground * alpha + base * (1 - alpha)
# Fade into darkness towards the edge of the ground disc.
r = np.hypot(G[:, 0], G[:, 1])[:, None]
ground *= np.clip((EXT - r) / 4.0, 0, 1)
gimg = np.clip(ground.reshape(n, n, 3), 0, 255).astype(np.uint8)
cv2.imwrite(OUT + '/ground.png', cv2.cvtColor(gimg, cv2.COLOR_RGB2BGR))

# ---- obstacle meshes
V, VT, F, groups = [], [], [], []
vbase = 0
for c in crops:
    name, cam = c['name'], c['cam']
    P = c['C'] + c['rays'] * np.minimum(c['t'], FAR)[..., None]
    t = c['t']
    N = t.shape[0]
    ii = np.arange(0, N, STEP); jj = np.arange(0, N, STEP)
    Pg = P[np.ix_(ii, jj)]; tg = t[np.ix_(ii, jj)]
    val = c['valid'][np.ix_(ii, jj)] & in_sector(cam, Pg)
    # Within one camera, each direction belongs to the crop that looks most straight at it.
    rg = c['rays'][np.ix_(ii, jj)]
    az = np.degrees(np.arctan2(rg[..., 0], rg[..., 1]))
    mine = (az - c['center_az'] + 180) % 360 - 180
    for oc in c['sibling_az']:
        other = (az - oc + 180) % 360 - 180
        val &= np.abs(mine) <= np.abs(other) + 1e-6
    # Ground is drawn by the ground texture; keep things that stand up, and the far backdrop.
    # "Ground" = depth agrees with where the ray meets the floor; anything clearly nearer stands up.
    tfloor = c['tg'][np.ix_(ii, jj)]
    on_floor = np.isfinite(tfloor) & (tg > tfloor * 0.85)
    keep = val & ((~on_floor & (Pg[..., 2] > 0.06)) | (tg >= FAR * 0.9))
    gi, gj = np.meshgrid(ii, jj, indexing='ij')
    uvs = np.stack([(gj + 0.5) / N, 1 - (gi + 0.5) / N], -1)
    h, w_ = keep.shape
    idx = -np.ones((h, w_), int)
    idx[keep] = np.arange(keep.sum())
    tt = np.minimum(tg, FAR)
    faces = []
    for (a0, a1, b0, b1, c0, c1) in [(0, 0, 1, 0, 0, 1), (1, 0, 1, 1, 0, 1)]:
        A = idx[a0:h - 1 + a0, a1:w_ - 1 + a1]; B = idx[b0:h - 1 + b0, b1:w_ - 1 + b1]; Cc = idx[c0:h - 1 + c0, c1:w_ - 1 + c1]
        ta = tt[a0:h - 1 + a0, a1:w_ - 1 + a1]; tb = tt[b0:h - 1 + b0, b1:w_ - 1 + b1]; tc = tt[c0:h - 1 + c0, c1:w_ - 1 + c1]
        mx = np.maximum(np.maximum(ta, tb), tc); mn = np.minimum(np.minimum(ta, tb), tc)
        # Cut where the depth jumps: that's an object's edge, not a surface.
        tol = np.where(mn > FAR * 0.85, 1.6, 1.0 + 0.05 + 0.012 * mn)
        good = (A >= 0) & (B >= 0) & (Cc >= 0) & (mx / np.maximum(mn, 1e-3) < tol)
        fa = np.stack([A[good], B[good], Cc[good]], 1)
        faces.append(fa)
    faces = np.concatenate(faces)
    # Drop sheets seen almost edge-on: those are stretched across a depth edge, not real surfaces.
    if len(faces):
        Pk = Pg[keep]
        n_ = np.cross(Pk[faces[:, 1]] - Pk[faces[:, 0]], Pk[faces[:, 2]] - Pk[faces[:, 0]])
        n_ /= np.linalg.norm(n_, axis=1, keepdims=True) + 1e-9
        ctr = Pk[faces].mean(1)
        view = ctr - c['C']; view /= np.linalg.norm(view, axis=1, keepdims=True)
        far = np.linalg.norm(ctr - c['C'], axis=1) > FAR * 0.85
        faces = faces[(np.abs((n_ * view).sum(1)) > 0.3) | far]
        # Nothing inside our own car, and no little floating scraps.
        ctr = Pk[faces].mean(1)
        inside = (np.abs(ctr[:, 0]) < W / 2 + 0.1) & (np.abs(ctr[:, 1]) < L / 2 + 0.1) & (ctr[:, 2] < 1.7)
        faces = faces[~inside]
    if len(faces):
        from scipy.sparse import coo_matrix
        from scipy.sparse.csgraph import connected_components
        nv_ = keep.sum()
        e = np.concatenate([faces[:, [0, 1]], faces[:, [1, 2]]])
        g = coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(nv_, nv_))
        _, comp = connected_components(g, directed=False)
        fc = np.bincount(comp[faces[:, 0]], minlength=comp.max() + 1)
        faces = faces[fc[comp[faces[:, 0]]] >= 150]
    if len(faces) == 0: continue
    img = c['img'].astype(np.float32) * gain[cam]
    cv2.imwrite(f'{OUT}/{name}.jpg', cv2.cvtColor(np.clip(img, 0, 255).astype(np.uint8), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 92])
    V.append(Pg[keep]); VT.append(uvs[keep]); F.append(faces + vbase); groups.append((name, len(faces)))
    vbase += keep.sum()
    print('mesh', name, 'verts', keep.sum(), 'faces', len(faces), flush=True)

V = np.concatenate(V); VT = np.concatenate(VT)
with open(OUT + '/scene.mtl', 'w') as m:
    for name, _ in groups:
        m.write(f'newmtl {name}\nmap_Kd {name}.jpg\n\n')
    m.write('newmtl ground\nmap_Kd ground.png\n')
with open(OUT + '/scene.obj', 'w') as o:
    o.write('mtllib scene.mtl\n')
    # OBJ is Y-up by convention for importers: write (x, z, -y) so Blender's importer lands it Z-up.
    o.write(''.join(f'v {x:.4f} {z_:.4f} {-y:.4f}\n' for x, y, z_ in V))
    o.write(''.join(f'vt {u:.5f} {v:.5f}\n' for u, v in VT))
    k = 0
    for (name, cnt), faces in zip(groups, F):
        o.write(f'o {name}\nusemtl {name}\n')
        o.write(''.join(f'f {a+1}/{a+1} {b+1}/{b+1} {c_+1}/{c_+1}\n' for a, b, c_ in faces))
    # Ground quad
    nv = len(V)
    o.write(f'o ground\nusemtl ground\n')
    for x, y in [(-EXT, -EXT), (EXT, -EXT), (EXT, EXT), (-EXT, EXT)]:
        o.write(f'v {x} 0.0 {-y}\n')
    nt = len(VT)
    for u, v in [(0, 0), (1, 0), (1, 1), (0, 1)]:
        o.write(f'vt {u} {v}\n')
    o.write(f'f {nv+1}/{nt+1} {nv+2}/{nt+2} {nv+3}/{nt+3} {nv+4}/{nt+4}\n')
print('wrote', OUT + '/scene.obj', len(V), 'verts')
