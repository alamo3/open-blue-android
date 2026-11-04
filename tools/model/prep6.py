"""Stage 1 for the IONIQ 6 model: prep6.py model.dae stage1.blend

Source: "Hyundai Ioniq 6" by Mona x Supercars (Sketchfab, CC BY 4.0), a SketchUp COLLADA export.
Blender 5 has no COLLADA importer, so this reads it with pycollada (pip install pycollada) and
builds one object per material, in metres (scaled to the real 4,855 mm length).
Front -Y, +Z up, +X the car's left (as tools/model/generic.py). Duplicate (two-sided) and degenerate
triangles dropped, vertices welded, normals made consistent, smoothed by angle."""
import bpy, bmesh, sys, math, time, collections
import numpy as np, collada
DAE, OUT = sys.argv[-2], sys.argv[-1]
LENGTH = 4.855
t0 = time.time()
d = collada.Collada(DAE, ignore=[collada.common.DaeUnsupportedError, collada.common.DaeBrokenRefError])
names = {m.id: m.name for m in d.materials}
cols = {}
for m in d.materials:
    dif = m.effect.diffuse
    cols[m.id] = tuple(dif[:3]) if isinstance(dif, tuple) else (0.5, 0.5, 0.5)
by = collections.defaultdict(list)
kinds = collections.Counter()
for bg in d.scene.objects('geometry'):
    for prim in bg.primitives():
        kinds[type(prim).__name__] += 1
        if isinstance(prim, (collada.polylist.BoundPolylist, collada.polygons.BoundPolygons)): prim = prim.triangleset()
        if not isinstance(prim, collada.triangleset.BoundTriangleSet) or prim.vertex_index is None: continue  # SketchUp edges are lines
        by[prim.material.id if prim.material is not None else "none"].append(prim.vertex[prim.vertex_index])
print("primitive kinds", dict(kinds))
tri = {k: np.concatenate(v).astype(np.float64) for k, v in by.items()}
allv = np.concatenate([v.reshape(-1, 3) for v in tri.values()])
lo, hi = allv.min(0), allv.max(0)
s = LENGTH / (hi[1] - lo[1])
off = np.array([(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, lo[2]])
print("scale m/unit", round(s, 6), "size m", ((hi - lo) * s).round(3), "load s", round(time.time() - t0, 1))
bpy.ops.wm.read_factory_settings(use_empty=True)
coll = bpy.context.scene.collection
report = []
for k, t in sorted(tri.items(), key=lambda kv: -len(kv[1])):
    p = (t.reshape(-1, 3) - off) * s
    q = np.round(p / 1e-4).astype(np.int64)
    uq, inv = np.unique(q, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    verts = uq * 1e-4
    f = inv.reshape(-1, 3)
    ok = (f[:, 0] != f[:, 1]) & (f[:, 1] != f[:, 2]) & (f[:, 0] != f[:, 2])
    f = f[ok]
    srt = np.sort(f, axis=1)
    _, first = np.unique(srt, axis=0, return_index=True)
    f = f[np.sort(first)]
    used = np.unique(f); remap = -np.ones(len(verts), np.int64); remap[used] = np.arange(len(used))
    verts = verts[used]; f = remap[f]
    me = bpy.data.meshes.new(names.get(k, k))
    me.vertices.add(len(verts)); me.vertices.foreach_set("co", verts.astype(np.float32).ravel())
    me.loops.add(f.size); me.loops.foreach_set("vertex_index", f.astype(np.int32).ravel())
    me.polygons.add(len(f)); me.polygons.foreach_set("loop_start", (np.arange(len(f)) * 3).astype(np.int32)); me.polygons.foreach_set("loop_total", np.full(len(f), 3, np.int32))
    me.update(calc_edges=True); me.validate(clean_customdata=False)
    bm = bmesh.new(); bm.from_mesh(me); bmesh.ops.recalc_face_normals(bm, faces=bm.faces); bm.to_mesh(me); bm.free()
    mat = bpy.data.materials.new(names.get(k, k)); mat.use_nodes = True
    b = next(n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'); b.inputs["Base Color"].default_value = (*cols.get(k, (0.5,) * 3), 1)
    me.materials.append(mat)
    me.shade_smooth()
    if hasattr(me, "set_sharp_from_angle"): me.set_sharp_from_angle(angle=math.radians(35))
    o = bpy.data.objects.new(names.get(k, k), me); coll.objects.link(o)
    report.append((names.get(k, k), len(t), len(f)))
for r in report: print(f"{r[0][:48]:48} tris {r[1]:>8} -> {r[2]:>8}")
print("total tris", sum(r[2] for r in report), "time s", round(time.time() - t0, 1))
bpy.ops.wm.save_as_mainfile(filepath=OUT)
