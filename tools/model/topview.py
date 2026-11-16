"""Render a transparent top-down marker for an imported EV.

Run with Blender: blender -b --python tools/model/topview.py -- car.glb studio.hdr anchors.json out.png
"""
import bpy, json, math, sys

GLB, HDR, ANCHORS, OUT = sys.argv[sys.argv.index('--') + 1:]
length = json.load(open(ANCHORS))['length']
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=GLB)
for name in ('charge_cable', 'ground_shadow'):
    obj = bpy.data.objects.get(name)
    if obj: obj.hide_render = True
scene = bpy.context.scene
scene.render.engine = 'CYCLES'; scene.cycles.samples = 16
scene.render.resolution_x = 320; scene.render.resolution_y = 640
scene.render.resolution_percentage = 100; scene.render.film_transparent = True
world = bpy.data.worlds.new('world'); scene.world = world; world.use_nodes = True
env = world.node_tree.nodes.new('ShaderNodeTexEnvironment'); env.image = bpy.data.images.load(HDR)
world.node_tree.links.new(env.outputs['Color'], world.node_tree.nodes['Background'].inputs['Color'])
light = bpy.data.lights.new('sun', 'SUN'); light.energy = 2.5
obj = bpy.data.objects.new('sun', light); scene.collection.objects.link(obj); obj.rotation_euler = (.4, -.5, -.4)
cam = bpy.data.cameras.new('cam'); cam.type = 'ORTHO'; cam.ortho_scale = length * 1.01
obj = bpy.data.objects.new('cam', cam); scene.collection.objects.link(obj)
obj.location = (0, 0, 10); obj.rotation_euler = (0, 0, math.pi); scene.camera = obj
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
