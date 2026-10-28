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

# ---------------------------------------------------------------- anchors for the app's effects
import json
import numpy as np
V = mathutils.Vector

def cast(origin, direction, skip=("interior", "engine", "wheels")):
    """Nearest hit on the car's outer skin: (point, normal) in world space, or None."""
    origin, direction = V(origin), V(direction).normalized()
    best = None
    for o in O:
        if o.type != 'MESH' or o.name in skip: continue
        inv = o.matrix_world.inverted()
        ok, loc, nrm, _ = o.ray_cast(inv @ origin, (inv.to_3x3() @ direction).normalized())
        if not ok: continue
        w = o.matrix_world @ loc
        n = (o.matrix_world.to_3x3().inverted().transposed() @ nrm).normalized()
        if best is None or (w - origin).length < (best[0] - origin).length: best = (w, n)
    return best

def gl(v):  # Blender -> glTF axes
    return [round(v[0], 3), round(v[2], 3), round(-v[1], 3)]

anchors = {}
# Charge port: rear right quarter panel, just behind and above the rear wheel.
port, port_n = cast((-3, 1.67, 0.96), (1, 0, 0))
port_n = V((port_n.x, port_n.y, 0)).normalized()  # keep the plug level
anchors["chargePort"] = gl(port); anchors["chargePortNormal"] = gl(port_n)
# Top surface profiles (hood, windshield, roof) along the car at a few lateral offsets.
for x in (0.0, 0.35, 0.6):
    prof = []
    for k in range(0, 56):
        y = -2.45 + k * 0.05
        h = cast((x, y, 3), (0, 0, -1), skip=("interior", "engine", "wheels", "door_fl", "door_fr", "door_rl", "door_rr"))
        prof.append(round(h[0].z, 3) if h else None)
    anchors[f"profile_x{x}"] = prof
print("ANCHORS", json.dumps(anchors))
json.dump(anchors, open(os.path.splitext(OUT)[0] + "-anchors.json", "w"), indent=1)

# ---------------------------------------------------------------- baked ground contact shadow
HALF = (1.45, 2.9)        # shadow plane half-size in metres (x, y); the render below covers exactly this
RES = (256, 512)

def silhouette(objs):
    """Top-down orthographic coverage (alpha, rows bottom-up like Blender pixels) of the given objects."""
    sc = bpy.context.scene
    hidden = [o for o in O if o.type == 'MESH' and o not in objs and not o.hide_render]
    for o in hidden: o.hide_render = True
    sc.render.engine = 'CYCLES'; sc.cycles.samples = 8; sc.cycles.device = 'CPU'
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = RES; sc.render.resolution_percentage = 100
    cam = bpy.data.cameras.new("top"); cam.type = 'ORTHO'; cam.ortho_scale = 2 * HALF[1]
    co = bpy.data.objects.new("top", cam); sc.collection.objects.link(co)
    co.location = (0, 0, 10)  # looking down -Z: image right = +X, image up = +Y
    sc.camera = co
    path = "/tmp/_sil.png"; sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    img = bpy.data.images.load(path)
    w, h = img.size
    a = np.array(img.pixels[:]).reshape(h, w, 4)[:, :, 3].copy()
    bpy.data.objects.remove(co); bpy.data.images.remove(img)
    for o in hidden: o.hide_render = False
    return a

def blur(a, sigma_m):
    r = sigma_m / (2 * HALF[1] / RES[1])  # metres -> pixels
    xs = np.arange(-int(3 * r) - 1, int(3 * r) + 2)
    k = np.exp(-0.5 * (xs / r) ** 2); k /= k.sum()
    a = np.apply_along_axis(lambda m: np.convolve(m, k, mode='same'), 0, a)
    return np.apply_along_axis(lambda m: np.convolve(m, k, mode='same'), 1, a)

meshes = [o for o in O if o.type == 'MESH']
body_a = silhouette(meshes)
wheel_a = silhouette([O["wheels"]])
# Broad soft shade under the whole car, darker core under the floor, tight contact under the tyres.
shadow = 0.42 * blur(body_a, 0.16) + 0.33 * blur(body_a, 0.05) + 0.5 * blur(wheel_a, 0.025)
shadow = np.clip(shadow, 0, 0.88)
h, w = shadow.shape
rgba = np.zeros((h, w, 4), dtype=np.float32); rgba[:, :, 3] = shadow
img = bpy.data.images.new("ground_shadow", w, h, alpha=True)
img.pixels = rgba.ravel().tolist()
img.filepath_raw = "/tmp/ground_shadow.png"; img.file_format = 'PNG'; img.save()
img.pack()

me = bpy.data.meshes.new("ground_shadow")
hx, hy, gz = HALF[0], HALF[1], 0.003
me.from_pydata([(-hx, -hy, gz), (hx, -hy, gz), (hx, hy, gz), (-hx, hy, gz)], [], [(0, 1, 2, 3)])
uv = me.uv_layers.new(name="UVMap")
for li, (u, v) in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]): uv.data[li].uv = (u, v)
plane = bpy.data.objects.new("ground_shadow", me); coll.objects.link(plane)
mat = bpy.data.materials.new("ground_shadow"); mat.use_nodes = True
nt = mat.node_tree; bsdf = next(n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED')
tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = img
nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
if hasattr(mat, "surface_render_method"): mat.surface_render_method = 'BLENDED'
else: mat.blend_method = 'BLEND'
me.materials.append(mat)

# ---------------------------------------------------------------- charging cable (hidden unless charging)
def catmull(points, steps=10):
    pts = [points[0]] + points + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for s in range(steps):
            t = s / steps
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    out.append(points[-1])
    return out

n = V(port_n); up = V((0, 0, 1)); back = V((0, 1, 0))
PLUG = 0.15
ctrl = [port + n * (PLUG + 0.04), port + n * (PLUG + 0.16) - up * 0.06, port + n * 0.42 - up * 0.5 + back * 0.08,
        port + n * 0.62 - up * 0.92 + back * 0.25, V((port.x + n.x * 1.05, port.y + 0.75, 0.02)),
        V((port.x + n.x * 1.9, port.y + 1.5, 0.02))]
for p in ctrl[-2:]: p.z = 0.016
path = catmull(ctrl, 12)
cu = bpy.data.curves.new("cable", 'CURVE'); cu.dimensions = '3D'
cu.bevel_depth = 0.013; cu.bevel_resolution = 3; cu.use_fill_caps = True
sp = cu.splines.new('POLY'); sp.points.add(len(path) - 1)
for i, p in enumerate(path): sp.points[i].co = (p.x, p.y, p.z, 1)
cobj = bpy.data.objects.new("cable_tmp", cu); coll.objects.link(cobj)
dg = bpy.context.evaluated_depsgraph_get()
cable_me = bpy.data.meshes.new_from_object(cobj.evaluated_get(dg))
bpy.data.objects.remove(cobj)

bm = bmesh.new(); bm.from_mesh(cable_me)
def add_cyl(r1, r2, length, start):
    """Cylinder/cone along the port normal, from start outward."""
    rot = n.to_track_quat('Z', 'Y').to_matrix().to_4x4()
    m = mathutils.Matrix.Translation(start + n * (length / 2)) @ rot
    bmesh.ops.create_cone(bm, cap_ends=True, segments=24, radius1=r1, radius2=r2, depth=length, matrix=m)
add_cyl(0.034, 0.036, 0.11, port)                     # connector body, seated in the port
add_cyl(0.036, 0.03, 0.04, port + n * 0.11)           # grip
add_cyl(0.022, 0.015, 0.05, port + n * (PLUG - 0.002))  # strain relief where the cable leaves
bm.to_mesh(cable_me); bm.free()
cable = bpy.data.objects.new("charge_cable", cable_me); coll.objects.link(cable)
rub = bpy.data.materials.new("charge_cable"); rub.use_nodes = True
rb = next(x for x in rub.node_tree.nodes if x.type == 'BSDF_PRINCIPLED')
rb.inputs["Base Color"].default_value = (0.012, 0.013, 0.014, 1); rb.inputs["Roughness"].default_value = 0.55
cable_me.materials.append(rub)
for poly in cable_me.polygons: poly.use_smooth = True
set_origin(cable, port)   # the app slides it along the port normal to plug in / out
anchors["cablePath"] = [gl(p - port) for p in path[::3]]  # relative to the port, for the energy pulses
json.dump(anchors, open(os.path.splitext(OUT)[0] + "-anchors.json", "w"), indent=1)

total = sum(len(p.vertices) - 2 for o in O if o.type == 'MESH' for p in o.data.polygons)
print("triangles after reduction:", total)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_yup=True, export_apply=True)
print("exported", OUT, os.path.getsize(OUT))

# Final touches in the GLB's JSON chunk:
# - the shadow should not be lit: mark its material unlit (KHR_materials_unlit);
# - glass uses KHR_materials_transmission, which Filament renders with screen-space refraction
#   that flickers over a transparent background (the windshield came and went as the view moved).
#   Make it plain tinted, alpha-blended glass instead.
import struct
data = open(OUT, "rb").read()
jlen = struct.unpack_from("<I", data, 12)[0]
doc = json.loads(data[20:20 + jlen])
for m in doc["materials"]:
    if m.get("name") == "ground_shadow":
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}
        m["alphaMode"] = "BLEND"
    ext = m.get("extensions", {})
    if "KHR_materials_transmission" in ext:
        del ext["KHR_materials_transmission"]
        if not ext: m.pop("extensions")
        red = m.get("name") == "red_glass"
        m["pbrMetallicRoughness"] = {"baseColorFactor": [0.55, 0.02, 0.02, 0.7] if red else [0.02, 0.025, 0.03, 0.62],
                                     "metallicFactor": 0, "roughnessFactor": 0.04}
doc["extensionsUsed"] = [e for e in doc.get("extensionsUsed", []) if e != "KHR_materials_transmission"]
doc.setdefault("extensionsUsed", [])
if "KHR_materials_unlit" not in doc["extensionsUsed"]: doc["extensionsUsed"].append("KHR_materials_unlit")
js = json.dumps(doc, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
rest = data[20 + jlen:]
out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + len(rest)) + struct.pack("<II", len(js), 0x4E4F534A) + js + rest
open(OUT, "wb").write(out)
print("patched", OUT, len(out))
