"""Split the Ioniq 5 lowpoly body into articulated parts with hinge pivots, then export GLB.

Blender axes after import: +X = car's left (driver side), -Y = front, +Z = up.
"""
import bpy, bmesh, sys, os, math, mathutils
sys.path.insert(0, os.path.dirname(__file__))
from common import *

S = sys.argv[-2]
OUT = sys.argv[-1]
reset()
bpy.ops.import_scene.gltf(filepath=S + "/model/gltf/scene.gltf")

# Apply transforms and join the four body meshes (paint, textured parts, black trim, lights).
body_parts = [bpy.data.objects[n] for n in ("Object_4", "Object_5", "Object_6", "Object_7")]
for o in bpy.data.objects:
    o.select_set(False)
for o in body_parts:
    o.select_set(True)
bpy.context.view_layer.objects.active = body_parts[0]
bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
bpy.ops.object.join()
body = bpy.context.view_layer.objects.active
body.name = "body"

SEAM_FRONT, SEAM_MID, SEAM_REAR = -1.185, -0.005, 1.2
REAR_WHEEL = mathutils.Vector((0, 1.31, 0.38))
HOOD_REAR = -1.245

def bisect(obj, co, no, select_fn):
    bm = bmesh.new(); bm.from_mesh(obj.data)
    geom = [f for f in bm.faces if select_fn(f)]
    verts = list({v for f in geom for v in f.verts}); edges = list({e for f in geom for e in f.edges})
    bmesh.ops.bisect_plane(bm, geom=verts + edges + geom, plane_co=co, plane_no=no)
    bm.to_mesh(obj.data); bm.free()

def side_region(f):
    c = f.calc_center_median()
    return abs(c.x) > 0.5 and 0.3 < c.z < 1.52

for y in (SEAM_FRONT, SEAM_MID, SEAM_REAR):
    bisect(body, (0, y, 0), (0, 1, 0), side_region)

def separate(name, pred):
    bpy.ops.object.select_all(action='DESELECT')
    body.select_set(True); bpy.context.view_layer.objects.active = body
    bm = bmesh.new(); bm.from_mesh(body.data)
    n = 0
    for f in bm.faces:
        f.select = pred(f)
        n += f.select
    bm.to_mesh(body.data); bm.free()
    if n == 0:
        print("!! no faces for", name); return None
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_mode(type='FACE')
    bpy.ops.object.mode_set(mode='OBJECT')
    # sync face selection flags into edit mode selection
    for p in body.data.polygons:
        pass
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.separate(type='SELECTED')
    bpy.ops.object.mode_set(mode='OBJECT')
    part = [o for o in bpy.context.selected_objects if o != body][0]
    part.name = name
    print(name, "faces:", n)
    return part

def door_pred(left, y0, y1):
    def p(f):
        c = f.calc_center_median()
        side = c.x > 0.55 if left else c.x < -0.55
        if not (side and y0 < c.y < y1 and 0.36 < c.z < 1.47):
            return False
        # Keep the rear wheel-arch cladding on the body.
        if (mathutils.Vector((0, c.y, c.z)) - REAR_WHEEL).length < 0.56:
            return False
        if c.z > 1.06:
            # Above the beltline only the side window and its frame belong to the door: faces
            # that point sideways. The windshield and A-pillar face forward and stay put.
            # The mirror is door-mounted on this car, so it comes along.
            mirror = abs(c.x) > 0.93 and -1.02 < c.y < -0.72 and c.z < 1.26
            return mirror or (abs(f.normal.x) > 0.6 and c.y > -1.0)
        return True
    return p

def islands_in_box(lo, hi, min_faces=6):
    """Faces of whole connected pieces whose bounds lie inside the box (keeps panels intact)."""
    bm = bmesh.new(); bm.from_mesh(body.data)
    bm.faces.ensure_lookup_table()
    seen, keep = set(), set()
    for f in bm.faces:
        if f.index in seen: continue
        comp, stack = [], [f]; seen.add(f.index)
        while stack:
            g = stack.pop(); comp.append(g.index)
            for e in g.edges:
                for h in e.link_faces:
                    if h.index not in seen: seen.add(h.index); stack.append(h)
        vs = [v.co for i in comp for v in bm.faces[i].verts]
        inside = all(lo[k] <= min(v[k] for v in vs) and max(v[k] for v in vs) <= hi[k] for k in range(3))
        if inside and len(comp) >= min_faces: keep.update(comp)
    bm.free()
    return lambda f: f.index in keep

parts = {}
parts["door_fl"] = separate("door_fl", door_pred(True, SEAM_FRONT, SEAM_MID))
parts["door_rl"] = separate("door_rl", door_pred(True, SEAM_MID, SEAM_REAR))
parts["door_fr"] = separate("door_fr", door_pred(False, SEAM_FRONT, SEAM_MID))
parts["door_rr"] = separate("door_rr", door_pred(False, SEAM_MID, SEAM_REAR))

parts["hood"] = separate("hood", islands_in_box((-0.86, -2.5, 0.84), (0.86, -1.2, 1.25)))
parts["tailgate"] = separate("tailgate", islands_in_box((-0.84, 1.25, 0.6), (0.84, 2.1, 1.52)))

def set_origin(obj, point):
    m = obj.data
    off = mathutils.Vector(point)
    for v in m.vertices:
        v.co -= off
    obj.location = off

def bounds(obj):
    vs = [obj.matrix_world @ v.co for v in obj.data.vertices]
    return [min(v[i] for v in vs) for i in range(3)], [max(v[i] for v in vs) for i in range(3)]

for k, o in parts.items():
    if o is None: continue
    mn, mx = bounds(o)
    if k.startswith("door"):
        left = k.endswith("l")
        # Hinge on the outer skin at the leading edge (not the mirror tip, which sticks out further).
        edge = [o.matrix_world @ v.co for v in o.data.vertices if (o.matrix_world @ v.co).y < mn[1] + 0.06]
        hx = max(v.x for v in edge) if left else min(v.x for v in edge)
        hinge = (hx, mn[1], (mn[2] + mx[2]) / 2)
    elif k == "hood":
        hinge = (0, mx[1], mx[2])                                           # rear edge, near windshield
    else:  # tailgate: top edge at the roof
        top = max(o.data.vertices, key=lambda v: v.co.z).co
        hinge = (0, mn[1], mx[2])
    set_origin(o, hinge)
    print(k, "bounds", [round(x, 2) for x in mn], [round(x, 2) for x in mx], "hinge", [round(x, 3) for x in hinge])

# Dark cabin so an open door doesn't reveal an empty shell: a lower block filling the door
# openings and a narrower upper block inside the greenhouse (kept inside the glass and roof).
mat = bpy.data.materials.new("M_Cabin")
mat.use_nodes = True
bsdf = mat.node_tree.nodes["Principled BSDF"]
bsdf.inputs["Base Color"].default_value = (0.03, 0.03, 0.035, 1)
bsdf.inputs["Roughness"].default_value = 0.9
def box(name, x, y0, y1, z0, z1):
    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, (y0 + y1) / 2, (z0 + z1) / 2))
    b = bpy.context.active_object
    b.name = name
    b.scale = (2 * x, y1 - y0, z1 - z0)
    bpy.ops.object.transform_apply(scale=True)
    b.data.materials.append(mat)
    return b
lower = box("cabin", 0.82, -0.98, 1.17, 0.34, 1.02)
upper = box("cabin_upper", 0.58, -0.5, 1.02, 1.0, 1.38)
bpy.ops.object.select_all(action='DESELECT')
lower.select_set(True); upper.select_set(True)
bpy.context.view_layer.objects.active = lower
bpy.ops.object.join()

# Double-sided so the inside of an open door isn't see-through.
for m in bpy.data.materials:
    m.use_backface_culling = False

bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_yup=True, export_apply=True)
print("exported", OUT, os.path.getsize(OUT))
