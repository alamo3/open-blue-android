"""Rig the "2024 Hyundai Ioniq 5 N" model (Ddiaz Design, CC BY-NC 4.0) for the app.

Input: stage1.blend from prepN.py (metres, parts merged by kind: body, glass, interior, engine, wheels).
Blender axes: +X = car's left (driver side), -Y = front, +Z = up.

The model's panels are separate mesh islands, so each moving part is simply the set of islands
that lie inside that part's region; anything spanning regions (B-pillar, body side) stays put.
"""
import bpy, bmesh, sys, os, math, mathutils
BLEND, OUT = sys.argv[-2], sys.argv[-1]
bpy.ops.wm.open_mainfile(filepath=BLEND)
O = bpy.data.objects

def islands(bm):
    seen, out = set(), []
    for f in bm.faces:
        if f.index in seen: continue
        comp, st = [], [f]; seen.add(f.index)
        while st:
            g = st.pop(); comp.append(g.index)
            for e in g.edges:
                for h in e.link_faces:
                    if h.index not in seen: seen.add(h.index); st.append(h)
        out.append(comp)
    return out

def classify(name, x0, x1, y0, y1, z0, z1, cy):
    """Label for one island from its bounds and centre y, or None to keep it on the body."""
    side = 'l' if x0 > 0.55 else ('r' if x1 < -0.55 else None)
    if side and z0 >= 0.2 and z1 <= 1.46 and name in ("body", "glass", "interior"):
        # A piece must fit inside one door's span; longer pieces (sills, body side) stay put.
        front = y0 >= -1.0 and y1 <= 0.36
        rear = y0 >= 0.1 and y1 <= 1.47
        if front and rear:
            return ("door_f" if cy < 0.22 else "door_r") + side
        if front: return "door_f" + side
        if rear: return "door_r" + side
    if name in ("body", "glass") and y1 <= -1.0 and y0 < -1.3 and z0 >= 0.6 and z1 > 0.85 and max(abs(x0), abs(x1)) <= 0.96:
        return "hood"
    if name in ("body", "glass") and y0 >= 1.25 and z0 >= 0.6 and max(abs(x0), abs(x1)) <= 0.8:
        return "tailgate"
    return None

pieces = {}
coll = bpy.context.scene.collection
for name in ("body", "glass", "interior"):
    obj = O[name]
    bm = bmesh.new(); bm.from_mesh(obj.data); bm.faces.ensure_lookup_table()
    labels = {}
    for comp in islands(bm):
        vs = [v.co for i in comp for v in bm.faces[i].verts]
        b = [f(v[k] for v in vs) for k in range(3) for f in (min, max)]
        cy = sum(v.y for v in vs) / len(vs)
        lab = classify(name, *b, cy)
        if lab:
            for i in comp: labels[i] = lab
    bm.free()
    # One copy per label keeping only that label's faces (face indices match across copies),
    # then remove all labelled faces from the original.
    for lab in sorted(set(labels.values())):
        new = obj.copy(); new.data = obj.data.copy(); coll.objects.link(new)
        bm = bmesh.new(); bm.from_mesh(new.data); bm.faces.ensure_lookup_table()
        bmesh.ops.delete(bm, geom=[f for f in bm.faces if labels.get(f.index) != lab], context='FACES')
        bm.to_mesh(new.data); bm.free()
        pieces.setdefault(lab, []).append(new)
    bm = bmesh.new(); bm.from_mesh(obj.data); bm.faces.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.index in labels], context='FACES')
    bm.to_mesh(obj.data); bm.free()

parts = {}
for lab, objs in pieces.items():
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    j = bpy.context.view_layer.objects.active; j.name = lab; parts[lab] = j

def verts(o): return [o.matrix_world @ v.co for v in o.data.vertices]

def set_origin(o, p):
    off = mathutils.Vector(p)
    for v in o.data.vertices: v.co -= off
    o.location = off

for lab, o in parts.items():
    vs = verts(o)
    mn = [min(v[k] for v in vs) for k in range(3)]; mx = [max(v[k] for v in vs) for k in range(3)]
    if lab.startswith("door"):
        left = lab.endswith("l")
        # Hinge on the outer skin at the leading edge, below the mirror.
        edge = [v for v in vs if v.y < mn[1] + 0.06 and v.z < 0.98]
        hx = max(v.x for v in edge) if left else min(v.x for v in edge)
        hinge = (hx, mn[1], 0.8)
    elif lab == "hood":
        hinge = (0, mx[1], mx[2])
    else:
        hinge = (0, mn[1], mx[2])
    set_origin(o, hinge)
    print(lab, "faces", len(o.data.polygons), "bounds", [round(x, 2) for x in mn], [round(x, 2) for x in mx], "hinge", [round(x, 3) for x in hinge])

# Reduce triangle count for phones: heavy on hidden-ish detail, light on visible panels.
ratios = {"interior": 0.3, "wheels": 0.3, "engine": 0.25}  # paint panels untouched: reducing them breaks the smooth shading
for o in O:
    if o.type != 'MESH': continue
    r = ratios.get(o.name)
    if r is None or len(o.data.polygons) < 400: continue
    m = o.modifiers.new("dec", 'DECIMATE'); m.ratio = r
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.modifier_apply(modifier="dec")

# Paint to match the car (Atlas White) and make the N's red accents a neutral dark grey.
for m in bpy.data.materials:
    if not m.node_tree: continue
    bsdf = next((n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if bsdf is None: continue
    if "Paint_Material" in m.name:
        m.name = "paint"
        bsdf.inputs["Base Color"].default_value = (0.78, 0.78, 0.75, 1)
    elif m.name == "red_paint":
        bsdf.inputs["Base Color"].default_value = (0.05, 0.05, 0.055, 1)
    m.use_backface_culling = False

total = sum(len(p.vertices) - 2 for o in O if o.type == 'MESH' for p in o.data.polygons)
print("triangles after reduction:", total)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_yup=True, export_apply=True)
print("exported", OUT, os.path.getsize(OUT))
