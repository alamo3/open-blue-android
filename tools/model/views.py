import bpy, sys, os, math
sys.path.insert(0, os.path.dirname(__file__))
from common import *
GLB, OUTDIR, TAG = sys.argv[-4], sys.argv[-3], sys.argv[-2]
OPEN = sys.argv[-1].split(",") if sys.argv[-1] != "none" else []
reset()
bpy.ops.import_scene.gltf(filepath=GLB)
O = bpy.data.objects
for o in O: o.rotation_mode = 'XYZ'
ang = {"door_fl": ('z', -45), "door_rl": ('z', -45), "door_fr": ('z', 45), "door_rr": ('z', 45), "hood": ('x', -38), "tailgate": ('x', 60)}
for n in OPEN:
    ax, a = ang[n]
    setattr(O[n].rotation_euler, ax, getattr(O[n].rotation_euler, ax) + math.radians(a))
setup_render(700, 460, 16)
views = {"fl": ((4.0, -4.4, 2.6), (0, -0.6, 0.8)), "top": ((0.01, -1.4, 6.0), (0, -1.4, 0.6)), "fr": ((-4.0, -4.4, 2.6), (0, -0.6, 0.8)), "side": ((5.5, -0.4, 1.4), (0, -0.4, 0.9))}
for k, (loc, tgt) in views.items():
    for o in list(O):
        if o.type == 'CAMERA': O.remove(o)
    camera_at(loc, tgt, 38); render(f"{OUTDIR}/{TAG}_{k}.png")
