"""Top-down cutaway of the cabin: cabinview.py car.glb studio.hdr out.png cut_z w h y0 y1"""
import bpy, sys, os, math, mathutils
sys.path.insert(0, os.path.dirname(__file__))
from common import reset
GLB, HDR, OUT, CUT, W, H, Y0, Y1 = sys.argv[-8:]
CUT, W, H, Y0, Y1 = float(CUT), int(W), int(H), float(Y0), float(Y1)
reset()
bpy.ops.import_scene.gltf(filepath=GLB)
O = bpy.data.objects
for n in ("charge_cable", "ground_shadow"): O[n].hide_render = True
# Cut the roof off for real (camera clipping alone would still shade the cabin with it).
import bmesh
for o in list(O):
    if o.type != 'MESH' or o.hide_render: continue
    me = o.data.copy(); o.data = me
    bm = bmesh.new(); bm.from_mesh(me)
    bm.transform(o.matrix_world)
    geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
    bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, CUT), plane_no=(0, 0, 1), clear_outer=True)
    bm.transform(o.matrix_world.inverted())
    bm.to_mesh(me); bm.free()
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.device = 'CPU'; sc.cycles.samples = 48
sc.render.resolution_x, sc.render.resolution_y = W, H
sc.render.film_transparent = True
world = bpy.data.worlds.new("W"); sc.world = world; world.use_nodes = True
env = world.node_tree.nodes.new("ShaderNodeTexEnvironment"); env.image = bpy.data.images.load(HDR)
world.node_tree.links.new(env.outputs["Color"], world.node_tree.nodes["Background"].inputs["Color"])
sun = bpy.data.lights.new("sun", 'SUN'); sun.energy = 2.0; sun.angle = math.radians(20)
so = bpy.data.objects.new("sun", sun); sc.collection.objects.link(so)
so.rotation_euler = (math.radians(20), math.radians(-12), 0)
cam = bpy.data.cameras.new("cam"); cam.type = 'ORTHO'
cam.ortho_scale = max((Y1 - Y0), (Y1 - Y0) * W / H)
co = bpy.data.objects.new("cam", cam); sc.collection.objects.link(co)
co.location = (0, (Y0 + Y1) / 2, 10)
co.rotation_euler = (0, 0, math.radians(180))  # nose (−Y) at the top of the image, driver (+X) on the left
cam.clip_start = 10 - CUT; cam.clip_end = 20
sc.camera = co
sc.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print("SCALE m/px", cam.ortho_scale / max(W, H))
