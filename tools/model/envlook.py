import bpy, sys, os, math
sys.path.insert(0, os.path.dirname(__file__))
from common import *
GLB, HDR, OUTP = sys.argv[-3], sys.argv[-2], sys.argv[-1]
reset()
bpy.ops.import_scene.gltf(filepath=GLB)
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.samples = 24
sc.render.resolution_x, sc.render.resolution_y = 800, 480
w = bpy.data.worlds.new("W"); sc.world = w; w.use_nodes = True
nt = w.node_tree; env = nt.nodes.new("ShaderNodeTexEnvironment"); env.image = bpy.data.images.load(HDR)
nt.links.new(env.outputs["Color"], nt.nodes["Background"].inputs["Color"])
print("hdr size", env.image.size[:])
camera_at((4.0, -4.6, 2.2), (0, -0.2, 0.7), 38); render(OUTP)
