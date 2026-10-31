"""Rig the "Hyundai Ioniq 6" model (Mona x Supercars, CC BY 4.0) for the app: rig6.py stage1.blend car_ioniq6.glb

Input: stage1.blend from prep6.py (metres, one object per source material).
Blender axes: +X = car's left (driver side), -Y = front, +Z = up. s below = -y (forward of the middle).

- Kinds: wheels (every piece inside a wheel's space), interior, glass, everything else stays as it is.
- The body colour is the material "paint" (the app recolours it), Nocturne Grey here; the black roof
  panel is painted to match.
- Moving parts are the mesh islands inside each part's region (as in rig.py): door_fl/fr/rl/rr
  (with their glass, handles, trim, door cards and the door-mounted mirrors), hood, tailgate (the
  trunk lid with its wing, emblem and stop lamp). Origins on the hinges.
- Hidden or busy detail (wheels, interior, trim) decimated for phones; paint panels left alone.
- Ground shadow, charging cable on the right rear port, and an anchors file in generic.py's format
  (ui/CarBody.kt reads it with CarShape.parse).
"""
import bpy, bmesh, sys, os, math, json, struct, mathutils
import numpy as np
V = mathutils.Vector
BLEND, OUT = sys.argv[-2], sys.argv[-1]
bpy.ops.wm.open_mainfile(filepath=BLEND)
O = bpy.data.objects
coll = bpy.context.scene.collection
P = "_2023_Hyundai_Ioniq_6_"

L, W, H = 4.855, 1.88, 1.495
HALF = L / 2
AXLES = (-(HALF - 0.85), -(HALF - 0.85) + 2.95)   # front and rear axle, as y
PAINT = (0.048, 0.053, 0.060)                      # Nocturne Grey, matched by eye (linear RGB)

def mesh_objects(): return [o for o in O if o.type == 'MESH']

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

# ---------------------------------------------------------------- materials
def bsdf(m): return next((n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None) if m.node_tree else None
def look(m, color=None, metal=None, rough=None, emit=None, alpha=None):
    b = bsdf(m)
    if color is not None: b.inputs["Base Color"].default_value = (*color, 1)
    if metal is not None: b.inputs["Metallic"].default_value = metal
    if rough is not None: b.inputs["Roughness"].default_value = rough
    if emit is not None:
        b.inputs["Emission Color"].default_value = (*emit, 1); b.inputs["Emission Strength"].default_value = 2.0
    if alpha is not None:
        b.inputs["Alpha"].default_value = alpha
        if hasattr(m, "surface_render_method"): m.surface_render_method = 'BLENDED'
        else: m.blend_method = 'BLEND'
    m.use_backface_culling = False

for m in bpy.data.materials:
    if not bsdf(m): continue
    n = m.name.replace(P, "")
    base = tuple(bsdf(m).inputs["Base Color"].default_value[:3])
    if n == "Material__99":
        m.name = "paint"; look(m, PAINT, 0.35, 0.28)
    elif n.startswith("High_Glossy"): look(m, (0.01, 0.01, 0.011), 0.0, 0.12)
    elif n == "wind_glass": m.name = "glass"; look(m, (0.02, 0.025, 0.03), 0.0, 0.04, alpha=0.62)
    elif n == "light_GLASS": m.name = "light_glass"; look(m, (0.9, 0.92, 0.95), 0.0, 0.03, alpha=0.18)
    elif n in ("led_light", "led"): look(m, (0.92, 0.94, 0.97), 0.0, 0.2, emit=(0.85, 0.9, 1.0))
    elif n in ("brake_light", "brake_light_02", "wind_spoiler_light"): look(m, (0.55, 0.02, 0.02), 0.0, 0.2, emit=(0.9, 0.03, 0.02))
    elif n in ("aluminum", "matal") or "RimFace" in n: look(m, base, 0.9, 0.3)
    elif n == "Tires": look(m, (0.02, 0.02, 0.022), 0.0, 0.85)
    elif "13_-_Default" in n: look(m, base, 0.0, 0.3, emit=(0.9, 0.45, 0.0))   # mirror indicators
    elif n in ("material_2", "material_3") or n.endswith(("lambert5", "lambert2", "lambert6")):
        # a black interior (the model's is pale grey), as most of these cars have and as the
        # climate screen's cabin view expects
        look(m, (0.05, 0.051, 0.055) if n == "material_3" else (0.032, 0.033, 0.036), 0.0, 0.7)
    else: look(m, base, 0.0, 0.5)

paint = bpy.data.materials["paint"]

# The pale grey "blinn1" trim is also used inside (steering wheel, seat backs): darken it there.
cabin_mat = bpy.data.materials["material_2"]
for o in mesh_objects():
    if not o.name.endswith("exterior04blinn1"): continue
    if cabin_mat.name not in [ms.material.name for ms in o.material_slots if ms.material]: o.data.materials.append(cabin_mat)
    ci = [ms.material.name for ms in o.material_slots].index(cabin_mat.name)
    for p in o.data.polygons:
        c = p.center
        if abs(c.x) < 0.72 and 0.55 < c.z < 1.12 and -1.15 < c.y < 1.25: p.material_index = ci

# Alberta cars carry no front plate: drop the model's front one (the rear "IONIQ 6" plate stays).
for o in mesh_objects():
    if o.name in ("Color_M00", "Color_M09"):
        bm = bmesh.new(); bm.from_mesh(o.data)
        bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.calc_center_median().y < -2.0], context='FACES')
        bm.to_mesh(o.data); bm.free()

# The roof panel is black gloss on this model; on a Nocturne Grey car it's body colour.
for o in mesh_objects():
    if not any(ms.material and "High_Glossy" in ms.material.name for ms in o.material_slots): continue
    if "paint" not in [ms.material.name for ms in o.material_slots if ms.material]: o.data.materials.append(paint)
    pi = [ms.material.name for ms in o.material_slots].index("paint")
    n_roof = 0
    for p in o.data.polygons:
        c = p.center
        if c.z > 1.36 and abs(c.x) < 0.62 and p.normal.z > 0.5 and -0.75 < c.y < 0.9:
            p.material_index = pi; n_roof += 1
    print("roof faces painted in", o.name, n_roof)

# ---------------------------------------------------------------- sort islands into parts and kinds
def in_wheel(x0, x1, y0, y1, z0, z1):
    if z1 > 0.76 or min(abs(x0), abs(x1)) < 0.45 or (x0 < 0 < x1): return False
    return any(abs(y0 - a) <= 0.42 and abs(y1 - a) <= 0.42 for a in AXLES)

INTERIOR = ("material_2", "material_3", "lambert5", "lambert2", "lambert6", "I_xogICo001")
def kind_of(name):
    n = name.replace(P, "")
    if any(k in n for k in INTERIOR): return "interior"
    if n == "wind_glass": return "glass"
    return "body"

def part_of(kind, x0, x1, y0, y1, z0, z1, cy):
    side = 'l' if x0 > 0.5 else ('r' if x1 < -0.5 else None)
    if side and z0 >= 0.25 and z1 <= 1.47:
        front = y0 >= -1.15 and y1 <= 0.25
        rear = y0 >= 0.02 and y1 <= 1.26
        if front and rear: return ("door_f" if cy < 0.135 else "door_r") + side
        if front: return "door_f" + side
        if rear: return "door_r" + side
    if kind != "interior" and y1 <= -1.10 and y0 <= -1.5 and z0 >= 0.70 and z1 >= 0.85 and max(abs(x0), abs(x1)) <= 0.86:
        return "hood"
    if kind != "interior" and y0 >= 1.40 and z0 >= 0.80 and max(abs(x0), abs(x1)) <= 0.92:
        return "tailgate"
    return None

buckets = {}            # label -> list of objects (copies holding only that label's faces)
for obj in list(mesh_objects()):
    kind = kind_of(obj.name)
    bm = bmesh.new(); bm.from_mesh(obj.data); bm.faces.ensure_lookup_table()
    labels = {}
    for comp in islands(bm):
        vs = np.array([v.co[:] for i in comp for v in bm.faces[i].verts])
        (x0, y0, z0), (x1, y1, z1) = vs.min(0), vs.max(0)
        if in_wheel(x0, x1, y0, y1, z0, z1): lab = "wheels"
        else: lab = part_of(kind, x0, x1, y0, y1, z0, z1, vs[:, 1].mean()) or (kind if kind != "body" else None)
        if lab:
            for i in comp: labels[i] = lab
    bm.free()
    for lab in sorted(set(labels.values())):
        new = obj.copy(); new.data = obj.data.copy(); coll.objects.link(new)
        bm = bmesh.new(); bm.from_mesh(new.data); bm.faces.ensure_lookup_table()
        bmesh.ops.delete(bm, geom=[f for f in bm.faces if labels.get(f.index) != lab], context='FACES')
        bm.to_mesh(new.data); bm.free()
        buckets.setdefault(lab, []).append(new)
    bm = bmesh.new(); bm.from_mesh(obj.data); bm.faces.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.index in labels], context='FACES')
    bm.to_mesh(obj.data); bm.free()
    buckets.setdefault("body", []).append(obj)

def join(objs, name):
    objs = [o for o in objs if len(o.data.polygons)]
    for o in [o for o in O if o.type == 'MESH' and not len(o.data.polygons)]: bpy.data.objects.remove(o)
    if not objs: return None
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    j = bpy.context.view_layer.objects.active; j.name = name; j.data.name = name
    return j

parts = {lab: join(objs, lab) for lab, objs in buckets.items()}
parts = {k: v for k, v in parts.items() if v}
for o in [o for o in O if o.type == 'MESH' and not len(o.data.polygons)]: bpy.data.objects.remove(o)

# ---------------------------------------------------------------- simplify for phones
RATIO = {"wheels": 0.22, "interior": 0.3, "body": 0.6, "glass": 1.0}
for lab, o in parts.items():
    r = RATIO.get(lab, 0.8 if lab.startswith("door") or lab in ("hood", "tailgate") else 1.0)
    before = len(o.data.polygons)
    if r < 1.0:
        # keep the paint at full detail: decimate a copy without it, then put it back
        bm = bmesh.new(); bm.from_mesh(o.data)
        pslot = [ms.material.name for ms in o.material_slots].index("paint") if "paint" in [ms.material.name for ms in o.material_slots if ms.material] else -1
        if pslot >= 0:
            keep = o.copy(); keep.data = o.data.copy(); coll.objects.link(keep)
            bk = bmesh.new(); bk.from_mesh(keep.data)
            bmesh.ops.delete(bk, geom=[f for f in bk.faces if f.material_index != pslot], context='FACES'); bk.to_mesh(keep.data); bk.free()
            bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.material_index == pslot], context='FACES')
        bm.to_mesh(o.data); bm.free()
        m = o.modifiers.new("dec", 'DECIMATE'); m.ratio = r
        bpy.context.view_layer.objects.active = o; bpy.ops.object.modifier_apply(modifier="dec")
        if pslot >= 0 and len(keep.data.polygons):
            bpy.ops.object.select_all(action='DESELECT'); keep.select_set(True); o.select_set(True)
            bpy.context.view_layer.objects.active = o; bpy.ops.object.join()
        elif pslot >= 0: bpy.data.objects.remove(keep)
    print(f"{lab:10} faces {before:>7} -> {len(o.data.polygons):>7}")

# ---------------------------------------------------------------- hinges
def verts(o): return [o.matrix_world @ v.co for v in o.data.vertices]
def set_origin(o, p):
    off = V(p)
    for v in o.data.vertices: v.co -= off
    o.location = off
for lab, o in parts.items():
    if not (lab.startswith("door") or lab in ("hood", "tailgate")): continue
    vs = verts(o)
    mn = [min(v[k] for v in vs) for k in range(3)]; mx = [max(v[k] for v in vs) for k in range(3)]
    if lab.startswith("door"):
        left = lab.endswith("l")
        edge = [v for v in vs if v.y < mn[1] + 0.06 and v.z < 0.98]
        hx = max(v.x for v in edge) if left else min(v.x for v in edge)
        hinge = (hx, mn[1], 0.8)
    elif lab == "hood":
        hinge = (0, mx[1], mx[2])
    else:
        hinge = (0, mn[1], mx[2])
    set_origin(o, hinge)
    print(lab, "bounds", [round(x, 2) for x in mn], [round(x, 2) for x in mx], "hinge", [round(x, 3) for x in hinge])

# ---------------------------------------------------------------- anchors (generic.py's format)
def cast(origin, direction, skip=("interior", "wheels")):
    origin, direction = V(origin), V(direction).normalized()
    best = None
    for o in mesh_objects():
        if o.name in skip or o.name in ("ground_shadow", "charge_cable"): continue
        inv = o.matrix_world.inverted()
        ok, loc, nrm, idx = o.ray_cast(inv @ origin, (inv.to_3x3() @ direction).normalized())
        if not ok: continue
        w = o.matrix_world @ loc
        nw = (o.matrix_world.to_3x3().inverted().transposed() @ nrm).normalized()
        if best is None or (w - origin).length < (best[0] - origin).length: best = (w, nw, o, idx)
    return best
def gl(v): return [round(v[0], 3), round(v[2], 3), round(-v[1], 3)]

anchors = {"kind": "ioniq6", "length": L, "width": W, "height": H, "nose": round(HALF, 3)}
# charge port: the round flap on the right rear quarter panel (y 1.62..1.82, z 0.73..0.91)
port, port_n, _, _ = cast((-3, 1.72, 0.82), (1, 0, 0))
port_n = V((port_n.x, port_n.y, 0)).normalized()
anchors["chargePort"] = gl(port); anchors["chargePortNormal"] = gl(port_n)
# centre-line profile: hood top from the nose back to the windshield, and where the glass starts and ends
prof = []
for k in range(0, 400):
    s = HALF - 0.005 - k * 0.012
    h = cast((0.0, -s, 3), (0, 0, -1), skip=("interior", "wheels", "door_fl", "door_fr", "door_rl", "door_rr"))
    if h: prof.append((s, h[0].z, h[2].active_material.name if h[2].active_material else "", h[2].material_slots[h[2].data.polygons[h[3]].material_index].material.name))
glass = [p for p in prof if p[3] == "glass" and p[0] > 0.0]   # the windshield (the rear glass is behind the roof)
cowl_s = max(p[0] for p in glass); ws_s = min(p[0] for p in glass)
cz = [p[1] for p in prof if abs(p[0] - cowl_s) < 0.007][0]; wz = [p[1] for p in prof if abs(p[0] - ws_s) < 0.007][0]
anchors["hood"] = [[round(p[0], 3), round(p[1], 3)] for p in prof if p[0] > cowl_s + 0.08 and p[0] <= HALF - 0.12][::8]
anchors["cowl"] = [round(cowl_s, 3), round(cz, 3)]
anchors["windshieldTop"] = [round(ws_s, 3), round(wz, 3)]
anchors["roof"] = round(max(p[1] for p in prof), 3)
print("anchors", {k: anchors[k] for k in ("chargePort", "cowl", "windshieldTop", "roof")}, "hood points", len(anchors["hood"]))

# ---------------------------------------------------------------- ground shadow (as in rig.py)
SH = (W / 2 + 0.55, HALF + 0.55)
RES = (256, int(256 * SH[1] / SH[0]))
def silhouette(objs):
    sc = bpy.context.scene
    hidden = [o for o in mesh_objects() if o not in objs and not o.hide_render]
    for o in hidden: o.hide_render = True
    sc.render.engine = 'CYCLES'; sc.cycles.samples = 8; sc.cycles.device = 'CPU'
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = RES; sc.render.resolution_percentage = 100
    cam = bpy.data.cameras.new("top"); cam.type = 'ORTHO'; cam.ortho_scale = 2 * SH[1]
    co = bpy.data.objects.new("top", cam); sc.collection.objects.link(co)
    co.location = (0, 0, 10); sc.camera = co
    path = "/tmp/_sil_ioniq6.png"; sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    img = bpy.data.images.load(path); w, h = img.size
    a = np.array(img.pixels[:]).reshape(h, w, 4)[:, :, 3].copy()
    bpy.data.objects.remove(co); bpy.data.images.remove(img)
    for o in hidden: o.hide_render = False
    return a
def blur(a, sigma_m):
    r = sigma_m / (2 * SH[1] / RES[1])
    xs = np.arange(-int(3 * r) - 1, int(3 * r) + 2); k = np.exp(-0.5 * (xs / r) ** 2); k /= k.sum()
    a = np.apply_along_axis(lambda m: np.convolve(m, k, mode='same'), 0, a)
    return np.apply_along_axis(lambda m: np.convolve(m, k, mode='same'), 1, a)
body_a = silhouette(mesh_objects()); wheel_a = silhouette([parts["wheels"]])
shadow = np.clip(0.42 * blur(body_a, 0.16) + 0.33 * blur(body_a, 0.05) + 0.5 * blur(wheel_a, 0.025), 0, 0.88)
h, w = shadow.shape
rgba = np.zeros((h, w, 4), dtype=np.float32); rgba[:, :, 3] = shadow
img = bpy.data.images.new("ground_shadow", w, h, alpha=True); img.pixels = rgba.ravel().tolist()
img.filepath_raw = "/tmp/ground_shadow_ioniq6.png"; img.file_format = 'PNG'; img.save(); img.pack()
me = bpy.data.meshes.new("ground_shadow")
me.from_pydata([(-SH[0], -SH[1], 0.003), (SH[0], -SH[1], 0.003), (SH[0], SH[1], 0.003), (-SH[0], SH[1], 0.003)], [], [(0, 1, 2, 3)])
uv = me.uv_layers.new(name="UVMap")
for li, (u, v) in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]): uv.data[li].uv = (u, v)
plane = bpy.data.objects.new("ground_shadow", me); coll.objects.link(plane)
sm = bpy.data.materials.new("ground_shadow"); sm.use_nodes = True
nt = sm.node_tree; sb = next(x for x in nt.nodes if x.type == 'BSDF_PRINCIPLED')
tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = img
nt.links.new(tex.outputs["Color"], sb.inputs["Base Color"]); nt.links.new(tex.outputs["Alpha"], sb.inputs["Alpha"])
if hasattr(sm, "surface_render_method"): sm.surface_render_method = 'BLENDED'
else: sm.blend_method = 'BLEND'
me.materials.append(sm)

# ---------------------------------------------------------------- charging cable (as in rig.py)
def catmull(points, steps=10):
    pts = [points[0]] + points + [points[-1]]; out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for st in range(steps):
            t = st / steps
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    out.append(points[-1]); return out
nn = V(port_n); up = V((0, 0, 1)); back = V((0, 1, 0)); PLUG = 0.15
ctrl = [port + nn * (PLUG + 0.04), port + nn * (PLUG + 0.16) - up * 0.06, port + nn * 0.42 - up * 0.5 + back * 0.08,
        port + nn * 0.62 - up * min(0.92, port.z - 0.06) + back * 0.25, V((port.x + nn.x * 1.05, port.y + 0.75, 0.016)),
        V((port.x + nn.x * 1.9, port.y + 1.5, 0.016))]
path = catmull(ctrl, 12)
cu = bpy.data.curves.new("cable", 'CURVE'); cu.dimensions = '3D'; cu.bevel_depth = 0.013; cu.bevel_resolution = 3; cu.use_fill_caps = True
sp = cu.splines.new('POLY'); sp.points.add(len(path) - 1)
for i, p in enumerate(path): sp.points[i].co = (p.x, p.y, p.z, 1)
cobj = bpy.data.objects.new("cable_tmp", cu); coll.objects.link(cobj)
cable_me = bpy.data.meshes.new_from_object(cobj.evaluated_get(bpy.context.evaluated_depsgraph_get()))
bpy.data.objects.remove(cobj)
bmc = bmesh.new(); bmc.from_mesh(cable_me)
def add_cyl(r1, r2, length, start):
    rot = nn.to_track_quat('Z', 'Y').to_matrix().to_4x4()
    bmesh.ops.create_cone(bmc, cap_ends=True, segments=24, radius1=r1, radius2=r2, depth=length,
                          matrix=mathutils.Matrix.Translation(start + nn * (length / 2)) @ rot)
add_cyl(0.034, 0.036, 0.11, port); add_cyl(0.036, 0.03, 0.04, port + nn * 0.11); add_cyl(0.022, 0.015, 0.05, port + nn * (PLUG - 0.002))
bmc.to_mesh(cable_me); bmc.free()
cable = bpy.data.objects.new("charge_cable", cable_me); coll.objects.link(cable)
cm = bpy.data.materials.new("charge_cable"); cm.use_nodes = True
cb = next(x for x in cm.node_tree.nodes if x.type == 'BSDF_PRINCIPLED')
cb.inputs["Base Color"].default_value = (0.012, 0.013, 0.014, 1); cb.inputs["Roughness"].default_value = 0.55
cable_me.materials.append(cm)
for poly in cable_me.polygons: poly.use_smooth = True
set_origin(cable, port)
anchors["cablePath"] = [gl(p - port) for p in path[::3]]
json.dump(anchors, open(os.path.splitext(OUT)[0] + "-anchors.json", "w"), indent=1)

total = sum(len(p.vertices) - 2 for o in mesh_objects() for p in o.data.polygons)
print("triangles:", total)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_yup=True, export_apply=True)

# Shadow unlit; glass plain alpha-blended; everything double-sided (as generic.py does).
data = open(OUT, "rb").read()
jlen = struct.unpack_from("<I", data, 12)[0]
doc = json.loads(data[20:20 + jlen])
for m in doc["materials"]:
    if m.get("name") == "ground_shadow":
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}; m["alphaMode"] = "BLEND"
    if m.get("name") == "glass":
        m["alphaMode"] = "BLEND"
        m["pbrMetallicRoughness"] = {"baseColorFactor": [0.02, 0.025, 0.03, 0.62], "metallicFactor": 0, "roughnessFactor": 0.04}
        m.pop("extensions", None)
    if m.get("name") == "light_glass":
        m["alphaMode"] = "BLEND"
        m["pbrMetallicRoughness"] = {"baseColorFactor": [0.9, 0.92, 0.95, 0.18], "metallicFactor": 0, "roughnessFactor": 0.03}
        m.pop("extensions", None)
    m["doubleSided"] = True
doc.setdefault("extensionsUsed", [])
doc["extensionsUsed"] = [e for e in doc["extensionsUsed"] if e != "KHR_materials_transmission"]
if "KHR_materials_unlit" not in doc["extensionsUsed"]: doc["extensionsUsed"].append("KHR_materials_unlit")
js = json.dumps(doc, separators=(",", ":")).encode(); js += b" " * (-len(js) % 4)
rest = data[20 + jlen:]
open(OUT, "wb").write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + len(rest)) + struct.pack("<II", len(js), 0x4E4F534A) + js + rest)
print("exported", OUT, os.path.getsize(OUT))
