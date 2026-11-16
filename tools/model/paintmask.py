"""Render the paint-only mask aligned with appview.py's default widget still.

Usage: CAR_ANCHORS=... blender -b --python tools/model/paintmask.py -- car.glb out.png
"""
import math
import os
import sys
import bpy
import mathutils

sys.path.insert(0, os.path.dirname(__file__))
from common import reset
import appcam

glb, out = sys.argv[sys.argv.index('--') + 1:]
reset()
bpy.ops.import_scene.gltf(filepath=glb)

def material(name, value):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    nodes.clear()
    emission = nodes.new('ShaderNodeEmission')
    emission.inputs['Color'].default_value = (value, value, value, 1)
    output = nodes.new('ShaderNodeOutputMaterial')
    mat.node_tree.links.new(emission.outputs[0], output.inputs['Surface'])
    return mat

white, black = material('mask_white', 1), material('mask_black', 0)
slots = []
for obj in bpy.data.objects:
    if obj.name in ('charge_cable', 'ground_shadow'):
        obj.hide_render = True
    if obj.type == 'MESH':
        for slot in obj.material_slots:
            # glTF import can create Blender variants named paint.001, paint.002, etc.
            name = slot.material.name if slot.material else ''
            slots.append((slot, name == 'paint' or name.startswith('paint.')))
        if not obj.material_slots:
            obj.data.materials.append(black)
for slot, is_paint in slots:
    slot.material = white if is_paint else black

scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 8
scene.render.resolution_x, scene.render.resolution_y = 1100, 748
scene.render.film_transparent = False
world = bpy.data.worlds.new('black')
world.use_nodes = True
world.node_tree.nodes['Background'].inputs[0].default_value = (0, 0, 0, 1)
scene.world = world
scene.view_settings.view_transform = 'Standard'
d = appcam.fit_distance(1100 / 748)
ex, ey, ez = appcam.eye(40, 12, d)
tx, ty, tz = appcam.TARGET
cam = bpy.data.cameras.new('cam')
cam.sensor_fit = 'VERTICAL'
cam.angle_y = math.radians(appcam.FOV_Y)
co = bpy.data.objects.new('cam', cam)
scene.collection.objects.link(co)
co.location = (ex, -ez, ey)
co.rotation_euler = (mathutils.Vector((tx, -tz, ty)) - co.location).to_track_quat('-Z', 'Y').to_euler()
scene.camera = co
scene.render.filepath = out
bpy.ops.render.render(write_still=True)
sys.stdout.flush()
os._exit(0)
