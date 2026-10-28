"""Render the reconstructed surroundings with the IONIQ 5 model in the middle.
usage: blender-python render.py SCENE_DIR CAR_GLB OUT_PREFIX
"""
import bpy, sys, math, mathutils, os
SCENE, GLB, OUT = sys.argv[-3:]

bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.device = 'CPU'; sc.cycles.samples = int(os.environ.get('SAMPLES', '16'))
sc.render.resolution_x, sc.render.resolution_y = 1280, 800
sc.view_settings.view_transform = 'Standard'

bpy.ops.wm.obj_import(filepath=os.path.join(SCENE, 'scene.obj'))
# Photo surfaces are shown as they are: emission only, no lighting on top.
for mat in bpy.data.materials:
    nt = mat.node_tree
    if nt is None: continue
    img = next((n.image for n in nt.nodes if n.type == 'TEX_IMAGE' and getattr(n, 'image', None)), None)
    if img is None: continue
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    tex = nt.nodes.new('ShaderNodeTexImage'); tex.image = img
    tex.extension = 'EXTEND'
    em = nt.nodes.new('ShaderNodeEmission'); out = nt.nodes.new('ShaderNodeOutputMaterial')
    nt.links.new(tex.outputs['Color'], em.inputs['Color']); nt.links.new(em.outputs['Emission'], out.inputs['Surface'])
    tex.interpolation = 'Linear'
    mat.use_backface_culling = False

# The car: glTF is Y-up with the nose at +Z; the importer turns that into Blender's Z-up with the
# nose at -Y. Our scene has the nose at +Y, so turn the car round.
before = set(bpy.data.objects)
bpy.ops.import_scene.gltf(filepath=GLB)
new = [o for o in bpy.data.objects if o not in before]
for o in new:
    if o.name in ('charge_cable', 'ground_shadow'):
        o.hide_render = True
pivot = bpy.data.objects.new('car_pivot', None); sc.collection.objects.link(pivot)
for o in new:
    if o.parent is None:
        o.parent = pivot
pivot.rotation_euler.z = math.pi
# Light for the car only (photo surfaces are emissive and ignore it).
world = bpy.data.worlds.new('W'); sc.world = world; world.use_nodes = True
world.node_tree.nodes['Background'].inputs['Color'].default_value = (0.02, 0.02, 0.025, 1)
world.node_tree.nodes['Background'].inputs['Strength'].default_value = 1.0
for loc, e in [((4, 6, 8), 600), ((-6, -4, 6), 400), ((0, 0, 9), 500)]:
    l = bpy.data.lights.new('l', 'AREA'); l.energy = e; l.size = 6
    lo = bpy.data.objects.new('l', l); lo.location = loc
    lo.rotation_euler = (mathutils.Vector((0, 0, 0)) - mathutils.Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    sc.collection.objects.link(lo)

ONLY = os.environ.get('ONLY')
if ONLY:
    for o in bpy.data.objects:
        if o.type == 'MESH' and '_' in o.name and o.name.split('_')[0] in ('front', 'rear', 'left', 'right') and not o.name.startswith(ONLY):
            o.hide_render = True
VIEWS_ONLY = os.environ.get('VIEWS')
cam = bpy.data.cameras.new('cam'); cam.lens = 24
co = bpy.data.objects.new('cam', cam); sc.collection.objects.link(co); sc.camera = co
VIEWS = {
    'a_front_left': (-35, 28, 9.0),
    'b_rear_right': (150, 22, 9.0),
    'c_high': (-10, 62, 12.0),
    'd_side_left': (-95, 15, 7.5),
}
ORBIT = int(os.environ.get('ORBIT', '0'))
if ORBIT:
    sc.render.resolution_x, sc.render.resolution_y = 960, 600
    VIEWS = {f'orbit_{k:03d}': (-180 + 360 * k / ORBIT, 30 + 8 * math.sin(2 * math.pi * k / ORBIT), 9.5) for k in range(ORBIT)}
for name, (az, el, dist) in VIEWS.items():
    if VIEWS_ONLY and name not in VIEWS_ONLY.split(','): continue
    a, e = math.radians(az), math.radians(el)
    # azimuth 0 = in front of the car looking back at it
    co.location = (dist * math.cos(e) * math.sin(a), dist * math.cos(e) * math.cos(a), dist * math.sin(e) + 0.6)
    co.rotation_euler = (mathutils.Vector((0, 0, 0.7)) - co.location).to_track_quat('-Z', 'Y').to_euler()
    sc.render.filepath = f'{OUT}_{name}.png'
    bpy.ops.render.render(write_still=True)
print('done')
