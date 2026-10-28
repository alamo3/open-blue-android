"""Warp each fisheye onto the ground with the calibration and compare with the car's top view."""
import json, sys
import numpy as np, cv2
sys.path.insert(0, sys.argv[1])
from calib_model import project, load

D = sys.argv[1]
cal = load(D)
top = cv2.imread(D + '/in/top.jpg')
H, W = top.shape[:2]
uu, vv = np.meshgrid(np.arange(W, dtype=np.float32), np.arange(H, dtype=np.float32))
X = (uu - cal['CU']) / cal['S']; Y = -(vv - cal['CV']) / cal['S']
P = np.stack([X.ravel(), Y.ravel(), np.zeros(X.size)], 1)
tiles = []
for name in ['front', 'rear', 'left', 'right']:
    fish = cv2.imread(D + '/in/' + name + '.jpg')
    u, v, ok = project(P, cal['cams'][name]['params'])
    u = np.where(ok, u, -1).reshape(H, W).astype(np.float32)
    v = np.where(ok, v, -1).reshape(H, W).astype(np.float32)
    warped = cv2.remap(fish, u, v, cv2.INTER_LINEAR, borderValue=(0, 0, 0))
    # Checkerboard blend so misalignment shows as broken edges.
    chk = ((uu // 40 + vv // 40) % 2 == 0)[..., None]
    mix = np.where(chk & (warped.sum(2, keepdims=True) > 30), warped, top)
    cv2.putText(mix, name, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 255), 2)
    tiles.append(mix)
cv2.imwrite(D + '/check.jpg', np.hstack(tiles))
print('ok')
