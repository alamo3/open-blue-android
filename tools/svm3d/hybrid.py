"""Hybrid scene: use the depth reconstruction only to decide WHERE things are, then describe them
cleanly: nearby cars as car models placed and turned to match, everything else that stands up as
extruded walls, and the photographed floor underneath.

Reads crops/*.npz and scene/ground.png (from depth.py / build.py). Writes hybrid/layout.json,
hybrid/walls.obj and hybrid/ground.png, plus a plan-view debug image.
"""
import sys, os, glob, json
import numpy as np, cv2, onnxruntime as ort

D = sys.argv[1]
OUT = D + '/hybrid'
os.makedirs(OUT, exist_ok=True)
L, W = 4.655, 1.89
SECT = {'front': 0, 'right': 90, 'rear': 180, 'left': -90}
VEHICLES = {2: 'car', 3: 'motorcycle', 5: 'bus', 7: 'truck'}

crops = []
for f in sorted(glob.glob(D + '/crops/*.npz')):
    z = dict(np.load(f)); z['name'] = os.path.basename(f)[:-4]; z['cam'] = z['name'].split('_')[0]
    crops.append(z)

def in_sector(cam, P, margin=8):
    az = np.degrees(np.arctan2(P[..., 0], P[..., 1]))
    return np.abs((az - SECT[cam] + 180) % 360 - 180) < 45 + margin

# ---------------------------------------------------------------- vehicle detection
det = ort.InferenceSession(D + '/models/yolo11n.onnx', providers=['CPUExecutionProvider'])

def detect(img):
    x = cv2.resize(img, (640, 640)).astype(np.float32).transpose(2, 0, 1)[None] / 255.0
    out = det.run(None, {'images': x})[0][0]          # 84 x 8400
    boxes, scores = out[:4].T, out[4:].T
    cls = scores.argmax(1); conf = scores.max(1)
    keep = (conf > 0.25) & np.isin(cls, list(VEHICLES))
    b = boxes[keep]; c = conf[keep]; k = cls[keep]
    xyxy = np.stack([b[:, 0] - b[:, 2] / 2, b[:, 1] - b[:, 3] / 2, b[:, 0] + b[:, 2] / 2, b[:, 1] + b[:, 3] / 2], 1)
    xyxy *= img.shape[0] / 640.0
    idx = cv2.dnn.NMSBoxes([list(map(float, [r[0], r[1], r[2] - r[0], r[3] - r[1]])) for r in xyxy], c.tolist(), 0.25, 0.5)
    idx = np.array(idx).ravel() if len(idx) else []
    return [(xyxy[i], float(c[i]), VEHICLES[int(k[i])]) for i in idx]

cars = []          # each: dict(points=Nx3, conf)
car_pixels = {}    # crop name -> bool mask of pixels belonging to detected vehicles
for c in crops:
    N = c['t'].shape[0]
    P = c['C'] + c['rays'] * c['t'][..., None]
    tfloor = c['tg']
    standing = c['valid'] & ~(np.isfinite(tfloor) & (c['t'] > tfloor * 0.85)) & (P[..., 2] > 0.08) & (c['t'] < 18)
    mask_all = np.zeros((N, N), bool)
    for (x0, y0, x1, y1), conf, kind in detect(c['img']):
        x0, y0 = max(0, int(x0)), max(0, int(y0)); x1, y1 = min(N, int(x1)), min(N, int(y1))
        if (x1 - x0) * (y1 - y0) < 400: continue
        box = np.zeros((N, N), bool); box[y0:y1, x0:x1] = True
        m = box & standing
        if m.sum() < 150: continue
        # Keep the main depth cluster in the box: the vehicle usually fills most of its box, so
        # this skips both a post in front of it and the wall behind it.
        t = c['t'][m]
        lt = np.log(t)
        hist, edges = np.histogram(lt, bins=40)
        k = int(np.argmax(np.convolve(hist, [1, 2, 1], 'same')))
        centre = np.exp((edges[k] + edges[k + 1]) / 2)
        m &= (c['t'] > centre / 1.45) & (c['t'] < centre * 1.45)
        if m.sum() < 150 or centre > 10: continue
        # A car's box is about as tall as a car would look at that distance. If the depth we
        # found is far nearer than that, it's something in front of the car (a pillar): skip.
        fp = (N / 2) / np.tan(np.radians(50))
        implied = fp * 1.6 / max(1.0, (y1 - y0))
        if not (0.45 < centre / implied < 2.2):
            print(f"{c['name']}: skipped a {kind} at {implied:.1f} m behind something at {centre:.1f} m", flush=True)
            continue
        pts = P[m]
        pts = pts[in_sector(c['cam'], pts, margin=25)]
        if len(pts) < 150: continue
        mask_all |= m
        cars.append({'points': pts, 'conf': conf, 'kind': kind, 'cam': c['cam'], 'C': c['C'],
                     'aspect': (x1 - x0) / max(1, (y1 - y0))})
        print(f"{c['name']}: {kind} {conf:.2f}, {len(pts)} points, median distance {np.median(t):.1f} m", flush=True)
    car_pixels[c['name']] = mask_all

def place(car):
    """Turn a vehicle's visible surface points into a footprint: centre, heading, size."""
    pts = car['points']
    body = pts[(pts[:, 2] > 0.3) & (pts[:, 2] < 1.6)]
    if len(body) > 200: pts = body
    xy = pts[:, :2]
    ctr = np.median(xy, 0)
    # Depth errors spread points along the viewing rays, so a plain principal axis can point the
    # wrong way. Instead find the straightest line through the points (a car's flat side).
    rng = np.random.default_rng(1)
    best = (0, None)
    sub = xy[rng.choice(len(xy), min(len(xy), 4000), replace=False)]
    for _ in range(300):
        a, b = sub[rng.integers(0, len(sub), 2)]
        d = b - a; nd = np.linalg.norm(d)
        if nd < 0.4: continue
        d /= nd; nrm = np.array([-d[1], d[0]])
        inl = np.abs((sub - a) @ nrm) < 0.12
        if inl.sum() > best[0]: best = (inl.sum(), d)
    major = best[1] if best[1] is not None else np.array([0.0, 1.0])
    line_pts = xy[np.abs((xy - ctr) @ np.array([-major[1], major[0]])) < 0.3]
    if len(line_pts) > 50: ctr = np.median(line_pts, 0)
    on_line = np.abs((xy - ctr) @ np.array([-major[1], major[0]])) < 0.2
    proj = (xy[on_line] if on_line.sum() > 50 else xy) - ctr
    proj = proj @ major
    lo, hi = np.percentile(proj, [0.5, 99.5])
    extent = hi - lo
    cam_xy = car['C'][:2]
    away = ctr - cam_xy; away /= np.linalg.norm(away)
    # A side view is long and low in the picture; a front or back is nearly square.
    # Right beside us and square-on, it's the car next door's flank (each crop only sees a bit).
    close_side = np.linalg.norm(ctr - cam_xy) < 3.0 and abs(major @ away) < 0.4
    if extent > 2.1 or car['aspect'] > 1.5 or close_side:
        # We see its side: it lies along the major axis, its body on the far side from us.
        axis = major
        normal = np.array([-axis[1], axis[0]])
        if normal @ away < 0: normal = -normal
        length = float(np.clip(extent, 4.2, 5.4))
        mid = ctr + major * (lo + hi) / 2
        centre = mid + normal * 0.92
    else:
        # We see its front or back: it points away from us.
        axis = away
        length = 4.6
        centre = ctr + away * length / 2
    height = float(np.clip(np.percentile(pts[:, 2], 97), 1.3, 2.1))
    heading = float(np.degrees(np.arctan2(axis[0], axis[1])))
    return {'x': float(centre[0]), 'y': float(centre[1]), 'heading': heading, 'length': length,
            'height': height, 'kind': car['kind'], 'conf': car['conf'], 'n': len(pts)}

# Pool detections of the same vehicle (seen in neighbouring crops or cameras) before placing it,
# so its whole visible side is used.
pools = []
for c in sorted(cars, key=lambda c: -len(c['points'])):
    cm = np.median(c['points'][:, :2], 0)
    for p_ in pools:
        if np.hypot(*(np.median(p_['points'][:, :2], 0) - cm)) < 2.6:
            p_['points'] = np.concatenate([p_['points'], c['points']])
            p_['conf'] = max(p_['conf'], c['conf'])
            p_['aspect'] = max(p_['aspect'], c['aspect'])
            break
    else:
        pools.append(dict(c))
placed = [place(c) for c in pools]
# Merge the same vehicle seen in several crops or cameras.
placed.sort(key=lambda p: -p['n'])
merged = []
for p in placed:
    if np.hypot(p['x'], p['y']) > 10: continue
    def same(q):
        a = np.radians(q['heading']); ax = np.array([np.sin(a), np.cos(a)])
        d = np.array([p['x'] - q['x'], p['y'] - q['y']])
        return abs(d @ ax) < q['length'] / 2 + 0.8 and abs(d @ np.array([ax[1], -ax[0]])) < 1.6
    if any(same(q) for q in merged): continue
    # Not on top of our own car.
    if abs(p['x']) < W / 2 + 0.6 and abs(p['y']) < L / 2 + 0.6: continue
    merged.append(p)
print('vehicles:', [(round(m['x'], 1), round(m['y'], 1), round(m['heading']), m['kind']) for m in merged])

def in_any_car(P):
    inside = np.zeros(len(P), bool)
    for m in merged:
        a = np.radians(m['heading'])
        ax = np.array([np.sin(a), np.cos(a)]); nx = np.array([ax[1], -ax[0]])
        d = P[:, :2] - [m['x'], m['y']]
        inside |= (np.abs(d @ ax) < m['length'] / 2 + 0.4) & (np.abs(d @ nx) < 1.25)
    return inside

# ---------------------------------------------------------------- walls from a "scan"
# Like a parking sensor sweeping round: for each direction from each camera, the nearest thing
# that stands up. Joining neighbouring directions gives thin walls that follow real surfaces,
# instead of blobs of scattered depth points.
walls = []
for cam in SECT:
    pts_all, rgb_all = [], []
    for c in [c for c in crops if c['cam'] == cam]:
        P = c['C'] + c['rays'] * c['t'][..., None]
        tfloor = c['tg']
        st = c['valid'] & ~(np.isfinite(tfloor) & (c['t'] > tfloor * 0.85)) & (P[..., 2] > 0.12) & (P[..., 2] < 2.3) & (c['t'] < 10)
        st &= ~car_pixels[c['name']] & in_sector(cam, P, margin=12)
        pts_all.append(P[st]); rgb_all.append(c['img'][st])
        Cpos = c['C']
    pts = np.concatenate(pts_all); rgb = np.concatenate(rgb_all).astype(np.float32)
    keep = ~in_any_car(pts) & ~((np.abs(pts[:, 0]) < W / 2 + 0.45) & (np.abs(pts[:, 1]) < L / 2 + 0.35))
    pts, rgb = pts[keep], rgb[keep]
    d = pts[:, :2] - Cpos[:2]
    az = np.degrees(np.arctan2(d[:, 0], d[:, 1])); r = np.hypot(d[:, 0], d[:, 1])
    bins = np.arange(-180, 181, 1.5)
    idx = np.digitize(az, bins)
    layers = [[], []]
    for b in range(1, len(bins)):
        m0 = idx == b
        beyond = 0.0
        for layer in range(2):
            m = m0 & (r > beyond)
            if m.sum() < 25:
                layers[layer].append(None); continue
            rb = r[m]
            near = np.percentile(rb, 8)
            close = m.copy(); close[m] = rb < near + 0.35
            if close.sum() < 15:
                layers[layer].append(None); continue
            h = float(np.clip(np.percentile(pts[close, 2], 92), 0.15, 2.2))
            a = np.radians((bins[b - 1] + bins[b]) / 2)
            xy = Cpos[:2] + near * np.array([np.sin(a), np.cos(a)])
            layers[layer].append((xy, h, near, rgb[close].mean(0) / 255, a))
            beyond = near + 0.9
    # Join neighbours at a similar distance into wall runs.
    run = []
    def flush():
        if len(run) < 3: return
        # Smooth the distances along the run (depth noise makes walls wobble), then rebuild it.
        rs = np.array([p_[2] for p_ in run]); ang = np.array([p_[4] for p_ in run])
        k = min(7, len(rs) // 2 * 2 + 1)
        rs = np.array([np.median(rs[max(0, i - k // 2): i + k // 2 + 1]) for i in range(len(rs))])
        rs = np.convolve(np.pad(rs, 2, mode='edge'), np.ones(5) / 5, 'valid')
        line = Cpos[:2] + rs[:, None] * np.stack([np.sin(ang), np.cos(ang)], 1)
        hs = np.array([p_[1] for p_ in run])
        hs = np.array([np.median(hs[max(0, i - 4): i + 5]) for i in range(len(hs))])
        # Low things are kerbs: give them one tidy height.
        hs = np.where(hs < 0.5, 0.22, hs)
        walls.append({'line': line.tolist(), 'heights': hs.tolist(),
                      'color': np.mean([p_[3] for p_ in run], 0).tolist()})
    for scan in layers:
        run = []
        for item in scan:
            if item is None:
                flush(); run = []; continue
            if run and abs(item[2] - run[-1][2]) > 0.3 + 0.1 * item[2]:
                flush(); run = []
            run.append(item)
        flush()
print('walls:', len(walls), 'runs, segments', sum(len(w['line']) - 1 for w in walls))

with open(OUT + '/walls.obj', 'w') as o:
    vi = 1
    for i, w in enumerate(walls):
        line = np.array(w['line']); hs = np.array(w['heights'])
        # Smooth the heights a little so the top edge isn't jagged.
        hs = np.convolve(np.pad(hs, 2, mode='edge'), np.ones(5) / 5, 'valid')
        o.write(f'o wall_{i}\n')
        for (x, y), h in zip(line, hs): o.write(f'v {x:.3f} 0.0 {-y:.3f}\nv {x:.3f} {h:.3f} {-y:.3f}\n')
        for j in range(len(line) - 1):
            a = vi + 2 * j
            o.write(f'f {a} {a + 2} {a + 3} {a + 1}\n')
        vi += 2 * len(line)

# Ground: the photographed floor, with the blind spots filled in from around them.
g = cv2.imread(D + '/scene/ground.png')
gs = cv2.resize(g, None, fx=0.5, fy=0.5)
hole = (cv2.cvtColor(gs, cv2.COLOR_BGR2GRAY) < 1).astype(np.uint8)
base = np.median(gs[hole == 0].reshape(-1, 3), 0)
# build.py painted unseen ground a flat dark tone: find it and inpaint it instead.
flat = (np.abs(gs.astype(int) - gs.astype(int).mean(2, keepdims=True)).sum(2) < 4) & (cv2.Laplacian(cv2.cvtColor(gs, cv2.COLOR_BGR2GRAY), cv2.CV_32F) == 0)
mask = cv2.morphologyEx(flat.astype(np.uint8), cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
filled = cv2.inpaint(gs, mask, 9, cv2.INPAINT_TELEA)
cv2.imwrite(OUT + '/ground.png', cv2.resize(filled, (g.shape[1], g.shape[0])))

json.dump({'vehicles': merged, 'walls': walls}, open(OUT + '/layout.json', 'w'), indent=1)

# Plan view for checking.
plan = cv2.resize(cv2.imread(OUT + '/ground.png'), (800, 800))
S = 800 / 24.0
def px(x, y): return int(400 + x * S), int(400 - y * S)
for w in walls:
    pts = np.array([px(x, y) for x, y in w['line']], np.int32)
    cv2.polylines(plan, [pts], False, (0, 200, 255), 2)
for m in merged:
    a = np.radians(m['heading']); ax = np.array([np.sin(a), np.cos(a)]); nx = np.array([ax[1], -ax[0]])
    corners = [np.array([m['x'], m['y']]) + ax * sx * m['length'] / 2 + nx * sy * 0.93 for sx, sy in [(1, 1), (1, -1), (-1, -1), (-1, 1)]]
    cv2.polylines(plan, [np.array([px(*c) for c in corners], np.int32)], True, (0, 255, 0), 2)
cv2.rectangle(plan, px(-W / 2, L / 2), px(W / 2, -L / 2), (255, 255, 255), 2)
cv2.imwrite(OUT + '/plan.jpg', plan)
