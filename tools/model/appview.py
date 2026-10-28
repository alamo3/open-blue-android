"""Render the car as the app frames it: appview.py car.glb studio.hdr out.png az el cable(0/1) [parts]"""
import bpy, sys, os, math, mathutils
sys.path.insert(0, os.path.dirname(__file__))
from common import reset
import appcam
GLB, HDR, OUT, AZ, EL, CABLE, PARTS, W, H = sys.argv[-9:]
AZ, EL = float(AZ), float(EL)
W, H = int(W), int(H)
reset()
bpy.ops.import_scene.gltf(filepath=GLB)
O = bpy.data.objects
for o in O: o.rotation_mode = 'XYZ'
if CABLE == "0": O["charge_cable"].hide_render = True
ang = {"door_fl": ('z', -45), "door_rl": ('z', -45), "door_fr": ('z', 45), "door_rr": ('z', 45), "hood": ('x', -38), "tailgate": ('x', 60)}
for n in (PARTS.split(",") if PARTS != "none" else []):
    ax, a = ang[n]
    setattr(O[n].rotation_euler, ax, getattr(O[n].rotation_euler, ax) + math.radians(a))
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.device = 'CPU'; sc.cycles.samples = 24
sc.render.resolution_x, sc.render.resolution_y = W, H
sc.render.film_transparent = True
world = bpy.data.worlds.new("W"); sc.world = world; world.use_nodes = True
env = world.node_tree.nodes.new("ShaderNodeTexEnvironment"); env.image = bpy.data.images.load(HDR)
world.node_tree.links.new(env.outputs["Color"], world.node_tree.nodes["Background"].inputs["Color"])
sun = bpy.data.lights.new("sun", 'SUN'); sun.energy = 2.5; sun.angle = math.radians(6)
so = bpy.data.objects.new("sun", sun); sc.collection.objects.link(so)
# app: direction (0.45,-1,-0.55) in glTF -> Blender (0.45, 0.55, -1)
so.rotation_euler = mathutils.Vector((0.45, 0.55, -1)).to_track_quat('-Z', 'Y').to_euler()
d = appcam.fit_distance(W / H)
ex, ey, ez = appcam.eye(AZ, EL, d)
tx, ty, tz = appcam.TARGET
cam = bpy.data.cameras.new("cam"); cam.sensor_fit = 'VERTICAL'; cam.angle_y = math.radians(appcam.FOV_Y)
co = bpy.data.objects.new("cam", cam); sc.collection.objects.link(co)
co.location = (ex, -ez, ey)
co.rotation_euler = (mathutils.Vector((tx, -tz, ty)) - co.location).to_track_quat('-Z', 'Y').to_euler()
sc.camera = co
print("DIST", d)
sc.render.filepath = OUT
bpy.ops.render.render(write_still=True)
