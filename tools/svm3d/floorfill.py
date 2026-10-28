"""LaMa on the top-down floor: the floor is a plane, so its holes are a 2D inpainting problem."""
import sys, numpy as np, cv2, onnxruntime as ort
# usage: floorfill.py DIR OUT  (DIR/models/lama.onnx; OUT/ground.jpg + ground_seen.png from the app pipeline)
D, K = sys.argv[1], sys.argv[2]
lama = ort.InferenceSession(D + '/models/lama.onnx', providers=['CPUExecutionProvider'])
g = cv2.cvtColor(cv2.imread(K + '/ground.jpg'), cv2.COLOR_BGR2RGB)
seen = cv2.imread(K + '/ground_seen.png', 0) > 127
n = g.shape[0]; ext = 12.0; res = 2 * ext / n
yy, xx = np.mgrid[0:n, 0:n]
X = -ext + (xx + .5) * res; Y = ext - (yy + .5) * res
inside = np.hypot(X, Y) < 11.5
seen = cv2.erode(seen.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0  # drop the smeared rims
known = seen & inside
img = np.where(known[..., None], g, 0).astype(np.uint8)
def lama_run(img, hole):
    x = cv2.resize(img, (512, 512), interpolation=cv2.INTER_AREA).astype(np.float32).transpose(2, 0, 1)[None] / 255
    m = (cv2.resize(hole.astype(np.uint8) * 255, (512, 512), interpolation=cv2.INTER_NEAREST) > 0).astype(np.float32)[None, None]
    o = lama.run(None, {'image': x, 'mask': m})[0][0].transpose(1, 2, 0)
    if o.max() <= 1.5: o = o * 255
    return cv2.resize(np.clip(o, 0, 255).astype(np.uint8), (img.shape[1], img.shape[0]), interpolation=cv2.INTER_CUBIC)
# Grow outwards in rings: LaMa is good at holes it can see around, poor at painting 70% of a picture.
cur = np.where(known[..., None], g, 0).astype(np.uint8); have = known.copy()
step = int(2.0 / res)  # ~2 m per ring
for it in range(12):
    if (~have & inside).sum() == 0: break
    dist = cv2.distanceTransform((~have).astype(np.uint8), cv2.DIST_L2, 5)
    ring = ~have & (dist <= step) & inside
    mean = cur[have].mean(0)
    src = np.where(have[..., None], cur, mean).astype(np.uint8)
    out_ = lama_run(src, ~have)
    # Let each ring overlap what the last one painted by ~0.3 m so the joins blend.
    soft = cv2.GaussianBlur(ring.astype(np.float32), (0, 0), 0.15 / res)
    soft = np.where(known, 0, np.maximum(soft, ring))[..., None]
    cur = (cur * (1 - soft) + out_ * soft).astype(np.uint8); have |= ring
fill = cur
# Feather the seam over ~6 px so the joins don't show.
a = cv2.GaussianBlur(known.astype(np.float32), (0, 0), 3)
a = np.where(known, np.maximum(a, 0.5) * 2 - 1, 0)[..., None].clip(0, 1)
out = (g * a + fill * (1 - a)).astype(np.uint8)
alpha = np.clip((11.5 - np.hypot(X, Y)) / 1.0, 0, 1)  # fade out at the edge of the world
cv2.imwrite(K + '/ground_filled.jpg', cv2.cvtColor(out, cv2.COLOR_RGB2BGR))
cv2.imwrite(K + '/ground_filled_alpha.png', (alpha * 255).astype(np.uint8))
cv2.imwrite(K + '/floorfill_cmp.jpg', cv2.resize(cv2.hconcat([cv2.cvtColor(img, cv2.COLOR_RGB2BGR), cv2.cvtColor(out, cv2.COLOR_RGB2BGR)]), (1024, 512)))
