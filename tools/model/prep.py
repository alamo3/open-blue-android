"""Stage 1 for the Ioniq 5 N model: import, scale to metres, merge the 1,620 pieces by kind, save .blend."""
import bpy, sys, os, mathutils, re
sys.path.insert(0, os.path.dirname(__file__))
from common import *
SRC, OUT = sys.argv[-2], sys.argv[-1]
reset()
bpy.ops.import_scene.gltf(filepath=SRC)
meshes = [o for o in bpy.data.objects if o.type == 'MESH']
bpy.ops.object.select_all(action='DESELECT')
for o in meshes: o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
for o in [o for o in bpy.data.objects if o.type != 'MESH']:
    bpy.data.objects.remove(o)
# scale to metres around the origin, then apply
for o in meshes:
    o.matrix_world = mathutils.Matrix.Scale(100, 4) @ o.matrix_world
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

def kind(name):
    n = name
    if "Wheel" in n or "Calliper" in n: return "wheels"
    if n.startswith("h:Interior") or "SeatBelt" in n: return "interior"
    if "Engine_Geo" in n: return "engine"
    if "Window" in n: return "glass"
    return "body"
groups = {}
for o in meshes:
    groups.setdefault(kind(o.name), []).append(o)
for k, objs in groups.items():
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    j = bpy.context.view_layer.objects.active
    j.name = k
    bb = [j.matrix_world @ mathutils.Vector(c) for c in j.bound_box]
    print(k, len(objs), "tris", sum(len(p.vertices) - 2 for p in j.data.polygons),
          [round(min(p[i] for p in bb), 2) for i in range(3)], [round(max(p[i] for p in bb), 2) for i in range(3)])
bpy.ops.wm.save_as_mainfile(filepath=OUT)
