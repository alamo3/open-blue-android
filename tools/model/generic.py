"""Generic stand-in cars for models the app has no 3D model of: generic.py <sedan|crossover|suv> out.glb

Built from scratch (no third-party meshes): the body is lofted from a side profile, a plan-view
outline and cross-sections, then split into the same named, hinged parts as the IONIQ 5 rig
(door_fl/fr/rl/rr, hood, tailgate, charge_cable, ground_shadow), with the paint in a material
called "paint" so the app can recolour it. Writes <out>-anchors.json with the points the app's
effects need (charge port, cable path, hood profile, airflow paths, size).

Blender axes: +X = car's left (driver side), -Y = front, +Z = up. "s" below is the distance
forward of the middle of the car (s = -y).
"""
import bpy, bmesh, sys, os, math, json, struct
import numpy as np
import mathutils
V = mathutils.Vector

KIND, OUT = sys.argv[-2], sys.argv[-1]
bpy.ops.wm.read_factory_settings(use_empty=True)

# ------------------------------------------------------------------ the three body types
# Lengths in metres. top: the silhouette's upper edge as (s, z) from nose to tail; belt: the
# window line; cowl/ws_top: where the windshield starts and ends; rear_top/rear_bot: the rear
# glass; hatch: the rear is a lift-up tailgate (else a trunk lid).
TYPES = {
    "sedan": dict(
        L=4.78, W=1.84, H=1.45, WB=2.86, R=0.335, front_oh=0.96, clear=0.14, cladding=False, hatch=False,
        top=[(2.39, 0.60), (2.33, 0.74), (2.20, 0.80), (1.45, 0.90), (0.95, 0.95), (0.05, 1.40), (-0.35, 1.45),
             (-0.95, 1.40), (-1.70, 1.05), (-2.25, 1.00), (-2.36, 0.90), (-2.39, 0.62)],
        belt=(0.95, 1.00), cowl=0.95, ws_top=0.05, rear_top=-0.95, rear_bot=-1.70,
    ),
    "crossover": dict(
        L=4.42, W=1.84, H=1.62, WB=2.70, R=0.355, front_oh=0.88, clear=0.18, cladding=True, hatch=True,
        top=[(2.21, 0.66), (2.15, 0.84), (2.02, 0.90), (1.40, 1.00), (0.95, 1.06), (0.08, 1.57), (-0.25, 1.62),
             (-1.40, 1.60), (-1.90, 1.52), (-2.12, 1.10), (-2.19, 0.98), (-2.21, 0.62)],
        belt=(1.03, 1.10), cowl=0.95, ws_top=0.08, rear_top=-1.90, rear_bot=-2.12,
    ),
    "suv": dict(
        L=4.86, W=1.95, H=1.76, WB=2.86, R=0.375, front_oh=0.95, clear=0.20, cladding=True, hatch=True,
        top=[(2.43, 0.70), (2.37, 0.94), (2.22, 1.02), (1.55, 1.10), (1.05, 1.15), (0.20, 1.71), (-0.10, 1.76),
             (-1.95, 1.75), (-2.30, 1.70), (-2.40, 1.12), (-2.42, 1.00), (-2.43, 0.66)],
        belt=(1.10, 1.16), cowl=1.05, ws_top=0.20, rear_top=-2.30, rear_bot=-2.40,
    ),
}
P = TYPES[KIND]
L, W, H, WB, R = P["L"], P["W"], P["H"], P["WB"], P["R"]
HALF = L / 2
S_F = HALF - P["front_oh"]            # front axle
S_R = S_F - WB                        # rear axle
RA = R + 0.05                         # wheel arch radius
TRACK = W / 2 - 0.13                  # wheel centre, from the middle

def smooth_profile(points, sigma=0.06, step=0.005):
    s = np.array([p[0] for p in points][::-1]); z = np.array([p[1] for p in points][::-1])
    grid = np.arange(-HALF, HALF + 1e-9, step)
    lin = np.interp(grid, s, z)
    r = int(3 * sigma / step); k = np.exp(-0.5 * (np.arange(-r, r + 1) * step / sigma) ** 2); k /= k.sum()
    pad = np.pad(lin, r, mode='edge')
    return grid, np.convolve(pad, k, mode='valid')

GRID, ZTOP = smooth_profile(P["top"])
def z_top(s): return float(np.interp(s, GRID, ZTOP))
def z_belt(s):
    b0, b1 = P["belt"]
    return b0 + (b1 - b0) * (P["cowl"] - s) / (P["cowl"] - P["rear_bot"])
def z_bot(s):
    z = P["clear"]
    # bumpers tuck up a little at the ends
    end = max(0.0, abs(s) - (HALF - 0.35)) / 0.35
    z += 0.16 * end ** 2
    for a in (S_F, S_R):
        d = abs(s - a)
        if d < RA: z = max(z, R + math.sqrt(RA * RA - d * d) * 0.98)
    return z
def half_w(s):
    # Rounder at the nose than at the tail (plan view).
    u = min(1.0, abs(s) / HALF); e = 4.0 if s > 0 else 6.0
    return W / 2 * (1 - u ** e) ** (1 / e)
def cabin(s):  # how much greenhouse there is here, 0..1
    return max(0.0, min(1.0, (z_top(s) - z_belt(s) - 0.05) / 0.12))

# ------------------------------------------------------------------ loft the body
# Rings across the car, each running up the left side, over the top and down the right side.
# Rows are laid out in three bands (sill to window line, window line to roof edge, roof edge to
# top) so the window outlines fall on mesh edges instead of stair-stepping across faces; ring
# positions include every pillar and door cut for the same reason.
N1, N2, N3 = 14, 10, 6
NZ = N1 + N2 + N3
SPLIT = S_F - RA - 1.02     # B-pillar
DOOR_F0 = S_F - RA - 0.04   # front door leading edge
DOOR_R1 = S_R + RA * 0.55   # rear door trailing edge
cowl, wst, rtop, rbot = P["cowl"], P["ws_top"], P["rear_top"], P["rear_bot"]
A_GAP = 0.16                # A-pillar width along the car at the window line
cuts = [SPLIT - 0.05, SPLIT + 0.05, DOOR_F0, DOOR_R1, cowl, wst, rtop, rbot, rtop + (0.18 if P["hatch"] else 0.12),
        HALF - 0.14, HALF - 0.16, -HALF + 0.12, -HALF + 0.14, cowl - A_GAP]
s_vals = sorted(set([round(x, 4) for x in np.arange(-HALF + 0.002, HALF - 0.002, 0.022)] + [round(c, 4) for c in cuts]), reverse=True)
s_vals = [x for x in s_vals if -HALF < x < HALF]

def row_heights(s):
    zb, zt, zl = z_bot(s), z_top(s), z_belt(s)
    zt = max(zt, zb + 0.08)
    g = cabin(s)
    if g > 0:
        zw = max(zl + 0.02, zt - 0.075)
        zl2 = min(zl, zt - 0.1)
        z1 = [zb + (zl2 - zb) * k / N1 for k in range(N1)]
        z2 = [zl2 + (zw - zl2) * k / N2 for k in range(N2)]
        z3 = [zw + (zt - zw) * k / N3 for k in range(N3 + 1)]
        return z1 + z2 + z3, zl2, zt, g
    return [zb + (zt - zb) * k / NZ for k in range(NZ + 1)], zt, zt, 0.0

rings, ring_s = [], []
for s in s_vals:
    zs, zl, zt, g = row_heights(s)
    zb = zs[0]; w = half_w(s)
    top_w = w * (1 - 0.22 * g)
    pts = []
    for z in zs:
        hw = w if (z <= zl or g == 0) else w + (top_w - w) * (z - zl) / max(zt - zl, 1e-3)
        rb, rt = 0.10, (0.16 if g > 0.5 else 0.10)
        if z < zb + rb: hw *= math.sqrt(max(0.0, 1 - ((zb + rb - z) / rb) ** 2)) * 0.25 + 0.75
        if z > zt - rt: hw *= math.sqrt(max(0.0, 1 - ((z - (zt - rt)) / rt) ** 2)) * 0.55 + 0.45
        pts.append((hw, z))
    rings.append([(hw, -s, z) for hw, z in pts] + [(-hw, -s, z) for hw, z in reversed(pts)])
    ring_s.append(s)

bm = bmesh.new()
vs = [[bm.verts.new(p) for p in ring] for ring in rings]
n = len(rings[0])                     # 2 * (NZ + 1)
meta = {}                             # face -> (s, kind, row): kind 'L', 'R', 'top' or 'bottom'
for ri, (a, b) in enumerate(zip(vs, vs[1:])):
    sm = (ring_s[ri] + ring_s[ri + 1]) / 2
    for k in range(n):
        j = (k + 1) % n
        f = bm.faces.new((a[k], a[j], b[j], b[k]))
        if k < NZ: meta[f] = (sm, 'L', k)
        elif k == NZ: meta[f] = (sm, 'top', NZ)
        elif k < 2 * NZ + 1: meta[f] = (sm, 'R', 2 * NZ - k)
        else: meta[f] = (sm, 'bottom', -1)
# Close the nose and tail with strips across, one per row (not one big polygon), so lights,
# the tailgate and the bumper can each be their own rows.
for ring, kind in ((vs[0], 'nose'), (vs[-1], 'tail')):
    for k in range(NZ):
        f = bm.faces.new((ring[k], ring[k + 1], ring[n - 2 - k], ring[n - 1 - k]))
        meta[f] = (ring_s[0] if kind == 'nose' else ring_s[-1], kind, k)
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)

# ------------------------------------------------------------------ materials by region
MATS = {}
def mat(name, color, metallic=0.0, rough=0.5, emit=None, alpha=1.0):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = next(x for x in m.node_tree.nodes if x.type == 'BSDF_PRINCIPLED')
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Metallic"].default_value = metallic; b.inputs["Roughness"].default_value = rough
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1); b.inputs["Emission Strength"].default_value = 3.0
    if alpha < 1:
        b.inputs["Alpha"].default_value = alpha
        if hasattr(m, "surface_render_method"): m.surface_render_method = 'BLENDED'
        else: m.blend_method = 'BLEND'
    MATS[name] = len(MATS)
    return m
mats = [
    mat("paint", (0.78, 0.78, 0.75), 0.0, 0.32),
    mat("glass", (0.02, 0.025, 0.03), 0.0, 0.04, alpha=0.62),
    mat("trim", (0.025, 0.026, 0.028), 0.0, 0.6),
    mat("light_front", (0.9, 0.92, 0.95), 0.0, 0.2, emit=(0.85, 0.9, 1.0)),
    mat("light_rear", (0.6, 0.02, 0.02), 0.0, 0.2, emit=(0.9, 0.03, 0.02)),
]
BAND2 = range(N1, N1 + N2)            # window rows
LIGHT_F = (NZ - 6, NZ - 5)                   # rows (outside the cabin, rows split the nose evenly)
LIGHT_R = (N1 - 3,) if P["hatch"] else (NZ - 6,)
BAND3 = range(N1 + N2, NZ)            # roof edge rows

def arch_trim(s, z):
    for a in (S_F, S_R):
        if math.hypot(s - a, z - R) < RA + 0.07: return True
    return False

def classify(f):
    s, kind, row = meta[f]
    c = f.calc_center_median(); z = c.z; nrm = f.normal
    zt, w = z_top(s), half_w(s)
    g = cabin(s)
    # Light bars: one row across the nose and one across the tail, wrapping a little round the corners.
    if (kind == 'nose' or (kind in ('L', 'R') and s > HALF - 0.12)) and row in LIGHT_F: return "light_front"
    if (kind == 'tail' or (kind in ('L', 'R') and s < -HALF + 0.10)) and row in LIGHT_R: return "light_rear"
    # Black sill and arch edges (the lowest rows follow the wheel arches), more on crossovers and SUVs.
    if kind == 'bottom' or (kind in ('L', 'R', 'nose', 'tail') and 0 <= row < (3 if P["cladding"] else 1)): return "trim"
    if g > 0.5 and kind in ('top', 'L', 'R') and (row in BAND2 or row in BAND3 or kind == 'top'):
        front = wst < s < cowl
        rear = rbot < s < rtop
        if front and (kind == 'top' or row in BAND3 or nrm.y < -0.35): return "glass"
        if rear and (kind == 'top' or row in BAND3 or nrm.y > 0.35): return "glass"
        if kind in ('L', 'R') and row in BAND2:
            a_pillar = s > cowl - A_GAP
            b_pillar = abs(s - SPLIT) < 0.05
            c_pillar = s < rtop + (0.18 if P["hatch"] else 0.12)
            if not (a_pillar or b_pillar or c_pillar): return "glass"
    return "paint"

for f in bm.faces:
    f.material_index = MATS[classify(f)]
    f.smooth = True

# ------------------------------------------------------------------ split off the moving parts
def part_of(f):
    s, kind, row = meta[f]
    nrm = f.normal; z = f.calc_center_median().z
    if kind in ('L', 'R') and row >= 2 and row < N1 + N2 and cabin(s) > 0.5:
        side = "l" if kind == 'L' else "r"
        if SPLIT < s < DOOR_F0: return "door_f" + side
        if DOOR_R1 < s < SPLIT: return "door_r" + side
    if cowl < s < HALF - 0.14 and (kind == 'top' or (kind in ('L', 'R') and row >= NZ - 6)):
        return "hood"
    if P["hatch"]:
        # The lift-up tailgate: the tail from just under the lights up, and the roof's rear edge.
        if kind == 'tail' and row >= N1 - 5: return "tailgate"
        if kind == 'top' and s < rtop: return "tailgate"
    else:
        # Trunk lid: the deck behind the rear glass and the top of the tail.
        if -HALF + 0.002 < s < rbot and (kind == 'top' or (kind in ('L', 'R') and row >= NZ - 6)):
            return "tailgate"
        if kind == 'tail' and row >= NZ - 7: return "tailgate"
    return None

labels = {f: part_of(f) for f in bm.faces}
me = bpy.data.meshes.new("body")
coll = bpy.context.scene.collection
parts = {}
for lab in sorted({l for l in labels.values() if l}):
    b2 = bm.copy()
    keep = {fi for fi, f in enumerate(bm.faces) if labels[f] == lab}
    b2.faces.ensure_lookup_table()
    bmesh.ops.delete(b2, geom=[f for fi, f in enumerate(b2.faces) if fi not in keep], context='FACES')
    m2 = bpy.data.meshes.new(lab); b2.to_mesh(m2); b2.free()
    for m in mats: m2.materials.append(m)
    o = bpy.data.objects.new(lab, m2); coll.objects.link(o); parts[lab] = o
bm.faces.ensure_lookup_table()
bmesh.ops.delete(bm, geom=[f for f in bm.faces if labels[f]], context='FACES')
bm.to_mesh(me); bm.free()
for m in mats: me.materials.append(m)
body = bpy.data.objects.new("body", me)
coll.objects.link(body)

def set_origin(o, p):
    off = V(p)
    for v in o.data.vertices: v.co -= off
    o.location = off

for lab, o in parts.items():
    vsw = [v.co for v in o.data.vertices]
    mn = [min(v[k] for v in vsw) for k in range(3)]; mx = [max(v[k] for v in vsw) for k in range(3)]
    # A little thickness, so an open panel isn't a paper-thin sheet.
    sol = o.modifiers.new("t", 'SOLIDIFY'); sol.thickness = 0.025; sol.offset = -1.0
    bpy.context.view_layer.objects.active = o; bpy.ops.object.modifier_apply(modifier="t")
    if lab.startswith("door"):
        left = lab.endswith("l")
        hinge = ((mx[0] if left else mn[0]) - (0.03 if left else -0.03), mn[1], 0.8)
    elif lab == "hood":
        hinge = (0, mx[1], mx[2])
    else:
        hinge = (0, mn[1], mx[2])
    set_origin(o, hinge)
    print(lab, len(o.data.polygons), "faces, hinge", [round(x, 3) for x in hinge])

# ------------------------------------------------------------------ cabin, wheels, mirrors
def box(name, x0, x1, y0, y1, z0, z1, m, bevel=0.0):
    bmx = bmesh.new()
    bmesh.ops.create_cube(bmx, size=1.0)
    for v in bmx.verts:
        v.co = V(((x0 + x1) / 2 + v.co.x * (x1 - x0), (y0 + y1) / 2 + v.co.y * (y1 - y0), (z0 + z1) / 2 + v.co.z * (z1 - z0)))
    if bevel: bmesh.ops.bevel(bmx, geom=list(bmx.edges), offset=bevel, segments=3, affect='EDGES')
    mm = bpy.data.meshes.new(name); bmx.to_mesh(mm); bmx.free()
    mm.materials.append(m)
    for p in mm.polygons: p.use_smooth = True
    o = bpy.data.objects.new(name, mm); coll.objects.link(o); return o

dark = mat("interior", (0.035, 0.036, 0.04), 0.0, 0.8)
seat = mat("seat", (0.06, 0.062, 0.068), 0.0, 0.7)
iw = half_w(0) - 0.08
cab = [box("interior", -iw, iw, -(cowl - 0.05), -(rbot + 0.25), z_bot(0) + 0.05, z_belt(0) - 0.02, dark)]
# dash and seats, so the cabin isn't empty through the glass
cab.append(box("dash", -iw, iw, -(cowl - 0.05), -(cowl - 0.45), z_belt(0) - 0.15, z_belt(0) + 0.05, dark, 0.03))
for sx in (-0.38, 0.38):
    for row, s0 in ((0, cowl - 0.95), (1, cowl - 1.85)):
        if s0 - 0.5 < rbot + 0.2 and not P["hatch"] and row == 1: pass
        cab.append(box("seat", sx - 0.24, sx + 0.24, -(s0 + 0.05), -(s0 - 0.45), z_belt(0) - 0.45, z_belt(0) - 0.25, seat, 0.04))
        cab.append(box("seat", sx - 0.23, sx + 0.23, -(s0 - 0.38), -(s0 - 0.5), z_belt(0) - 0.3, z_belt(0) + 0.25, seat, 0.04))
bpy.ops.object.select_all(action='DESELECT')
for o in cab: o.select_set(True)
bpy.context.view_layer.objects.active = cab[0]; bpy.ops.object.join(); bpy.context.view_layer.objects.active.name = "interior"

tyre = mat("tyre", (0.02, 0.02, 0.022), 0.0, 0.85)
rim = mat("rim", (0.55, 0.56, 0.58), 1.0, 0.28)
wheel_objs = []
for sx in (TRACK, -TRACK):
    for sa in (S_F, S_R):
        bmw = bmesh.new()
        tw = 0.24
        m = mathutils.Matrix.Translation(V((sx, -sa, R))) @ mathutils.Matrix.Rotation(math.pi / 2, 4, 'Y')
        bmesh.ops.create_cone(bmw, cap_ends=True, segments=40, radius1=R, radius2=R, depth=tw, matrix=m)
        bmesh.ops.bevel(bmw, geom=[e for e in bmw.edges if len(e.link_faces) == 2 and abs(e.calc_face_angle(0)) > 1.0],
                        offset=0.05, segments=4, affect='EDGES')
        for f in bmw.faces: f.material_index = 0; f.smooth = True
        out = 1 if sx > 0 else -1
        # rim: a disc inset in the outer face, with five spokes
        mr = mathutils.Matrix.Translation(V((sx + out * (tw / 2 - 0.005), -sa, R))) @ mathutils.Matrix.Rotation(math.pi / 2, 4, 'Y')
        r1 = bmesh.ops.create_cone(bmw, cap_ends=True, segments=40, radius1=R * 0.66, radius2=R * 0.66, depth=0.02, matrix=mr)
        for v in r1["verts"]:
            for f in v.link_faces: f.material_index = 1
        for k in range(5):
            ang = 2 * math.pi * k / 5
            ms = (mathutils.Matrix.Translation(V((sx + out * (tw / 2 + 0.008), -sa, R))) @
                  mathutils.Matrix.Rotation(ang, 4, 'X') @ mathutils.Matrix.Translation(V((0, 0, R * 0.33))) @
                  mathutils.Matrix.Diagonal(V((0.025, 0.07, R * 0.6, 1))))
            sp = bmesh.ops.create_cube(bmw, size=1.0, matrix=ms)
            for v in sp["verts"]:
                for f in v.link_faces: f.material_index = 1
        mw = bpy.data.meshes.new("wheel"); bmw.to_mesh(mw); bmw.free()
        mw.materials.append(tyre); mw.materials.append(rim)
        wo = bpy.data.objects.new("wheels", mw); coll.objects.link(wo); wheel_objs.append(wo)
bpy.ops.object.select_all(action='DESELECT')
for o in wheel_objs: o.select_set(True)
bpy.context.view_layer.objects.active = wheel_objs[0]; bpy.ops.object.join(); bpy.context.view_layer.objects.active.name = "wheels"

# mirrors at the base of the A-pillars (on the body, like the IONIQ's)
pm = bpy.data.materials["paint"]
for side in (1, -1):
    s0 = cowl - 0.22; zl = z_belt(s0)
    x0 = side * (half_w(s0) - 0.02)
    o = box("mirror", min(x0, x0 + side * 0.2), max(x0, x0 + side * 0.2), -(s0 + 0.04), -(s0 - 0.1), zl + 0.02, zl + 0.15, pm, 0.035)
    o.parent = None

# ------------------------------------------------------------------ anchors for the app's effects
def cast(origin, direction, skip=("interior", "wheels")):
    origin, direction = V(origin), V(direction).normalized()
    best = None
    dg = bpy.context.evaluated_depsgraph_get()
    for o in bpy.data.objects:
        if o.type != 'MESH' or any(o.name.startswith(k) for k in skip): continue
        inv = o.matrix_world.inverted()
        ok, loc, nrm, _ = o.ray_cast(inv @ origin, (inv.to_3x3() @ direction).normalized())
        if not ok: continue
        w = o.matrix_world @ loc
        nw = (o.matrix_world.to_3x3().inverted().transposed() @ nrm).normalized()
        if best is None or (w - origin).length < (best[0] - origin).length: best = (w, nw)
    return best

def gl(v): return [round(v[0], 3), round(v[2], 3), round(-v[1], 3)]

anchors = {"kind": KIND, "length": L, "width": W, "height": H}
port_s = S_R - 0.30
port, port_n = cast((-3, -port_s, z_belt(port_s) - 0.08), (1, 0, 0))
port_n = V((port_n.x, port_n.y, 0)).normalized()
anchors["chargePort"] = gl(port); anchors["chargePortNormal"] = gl(port_n)
# hood height along the centre line, nose to cowl (glTF: s -> y)
anchors["hood"] = [[round(s, 3), round(z_top(s), 3)] for s in np.arange(HALF - 0.14, cowl - 0.1, -0.1)]
anchors["cowl"] = [round(cowl, 3), round(z_top(cowl), 3)]
anchors["windshieldTop"] = [round(wst, 3), round(z_top(wst), 3)]
anchors["belt"] = round(z_belt(0), 3)
anchors["roof"] = round(z_top((wst + rtop) / 2), 3)
anchors["nose"] = round(HALF, 3)

# ------------------------------------------------------------------ ground shadow (as in rig.py)
SH = (W / 2 + 0.55, HALF + 0.55)
RES = (256, int(256 * SH[1] / SH[0]))
def silhouette(objs):
    sc = bpy.context.scene
    hidden = [o for o in bpy.data.objects if o.type == 'MESH' and o not in objs and not o.hide_render]
    for o in hidden: o.hide_render = True
    sc.render.engine = 'CYCLES'; sc.cycles.samples = 8; sc.cycles.device = 'CPU'
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = RES; sc.render.resolution_percentage = 100
    cam = bpy.data.cameras.new("top"); cam.type = 'ORTHO'; cam.ortho_scale = 2 * SH[1]
    co = bpy.data.objects.new("top", cam); sc.collection.objects.link(co)
    co.location = (0, 0, 10); sc.camera = co
    path = "/tmp/_sil_%s.png" % KIND; sc.render.filepath = path
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
meshes = [o for o in bpy.data.objects if o.type == 'MESH']
body_a = silhouette(meshes); wheel_a = silhouette([bpy.data.objects["wheels"]])
shadow = np.clip(0.42 * blur(body_a, 0.16) + 0.33 * blur(body_a, 0.05) + 0.5 * blur(wheel_a, 0.025), 0, 0.88)
h, w = shadow.shape
rgba = np.zeros((h, w, 4), dtype=np.float32); rgba[:, :, 3] = shadow
img = bpy.data.images.new("ground_shadow", w, h, alpha=True); img.pixels = rgba.ravel().tolist()
img.filepath_raw = "/tmp/ground_shadow_%s.png" % KIND; img.file_format = 'PNG'; img.save(); img.pack()
mesh = bpy.data.meshes.new("ground_shadow")
mesh.from_pydata([(-SH[0], -SH[1], 0.003), (SH[0], -SH[1], 0.003), (SH[0], SH[1], 0.003), (-SH[0], SH[1], 0.003)], [], [(0, 1, 2, 3)])
uv = mesh.uv_layers.new(name="UVMap")
for li, (u, v) in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]): uv.data[li].uv = (u, v)
plane = bpy.data.objects.new("ground_shadow", mesh); coll.objects.link(plane)
sm = bpy.data.materials.new("ground_shadow"); sm.use_nodes = True
nt = sm.node_tree; bsdf = next(x for x in nt.nodes if x.type == 'BSDF_PRINCIPLED')
tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = img
nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"]); nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
if hasattr(sm, "surface_render_method"): sm.surface_render_method = 'BLENDED'
else: sm.blend_method = 'BLEND'
mesh.materials.append(sm)

# ------------------------------------------------------------------ charging cable (as in rig.py)
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

total = sum(len(p.vertices) - 2 for o in bpy.data.objects if o.type == 'MESH' for p in o.data.polygons)
print("triangles:", total)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_yup=True, export_apply=True)

# Shadow unlit; glass plain alpha-blended (as rig.py does).
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
    m["doubleSided"] = True
doc.setdefault("extensionsUsed", [])
doc["extensionsUsed"] = [e for e in doc["extensionsUsed"] if e != "KHR_materials_transmission"]
if "KHR_materials_unlit" not in doc["extensionsUsed"]: doc["extensionsUsed"].append("KHR_materials_unlit")
js = json.dumps(doc, separators=(",", ":")).encode(); js += b" " * (-len(js) % 4)
rest = data[20 + jlen:]
open(OUT, "wb").write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + len(rest)) + struct.pack("<II", len(js), 0x4E4F534A) + js + rest)
print("exported", OUT, os.path.getsize(OUT))
