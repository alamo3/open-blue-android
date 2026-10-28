import bpy, sys, os, math
sys.path.insert(0, os.path.dirname(__file__))
from common import *
GLB, OUTDIR, MODE = sys.argv[-3], sys.argv[-2], sys.argv[-1]
reset()
bpy.ops.import_scene.gltf(filepath=GLB)
O = bpy.data.objects
for o in O:
    o.rotation_mode = 'XYZ'
if MODE == "open":
    # Blender axes: +X left side, -Y front, +Z up. Angles mirror what the app applies.
    O["door_fl"].rotation_euler.z += math.radians(-45)
    O["door_rl"].rotation_euler.z += math.radians(-45)
    O["door_fr"].rotation_euler.z += math.radians(45)
    O["door_rr"].rotation_euler.z += math.radians(45)
    O["hood"].rotation_euler.x += math.radians(-38)
    O["tailgate"].rotation_euler.x += math.radians(60)
setup_render(900, 560, 20)
camera_at((4.4, -4.8, 2.6), (0, -0.2, 0.7), 38); render(f"{OUTDIR}/{MODE}_front.png")
for o in list(O):
    if o.type == 'CAMERA': O.remove(o)
camera_at((-4.6, 4.9, 2.8), (0, 0.3, 0.8), 38); render(f"{OUTDIR}/{MODE}_rear.png")
