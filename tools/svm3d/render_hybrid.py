"""Render the hybrid scene: photographed floor, clean extruded walls, neighbouring cars as clay
models, and the IONIQ 5 in the middle.
usage: blender-python render_hybrid.py SVM_DIR CAR_GLB OUT_PREFIX
"""
import bpy, sys, math, mathutils, os, json
D, GLB, OUT = sys.argv[-3:]
H = os.path.join(D, 'hybrid')
lay = json.load(open(os.path.join(H, 'layout.json')))

bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.device = 'CPU'; sc.cycles.samples = int(os.environ.get('SAMPLES', '24'))
sc.render.resolution_x, sc.render.resolution_y = 1280, 800
sc.view_settings.view_transform = 'AgX'

def emission_image_material(name, path):
    m = bpy.data.materials.new(name); m.use_nodes = True; nt = m.node_tree
    for n in list(nt.nodes): nt.nodes.remove(n)
    tex = nt.nodes.new('ShaderNodeTexImage'); tex.image = bpy.data.images.load(path); tex.extension = 'EXTEND'
    # Lit like everything else, but mostly its own photo brightness.
    bsdf = nt.nodes.new('ShaderNodeBsdfDiffuse'); em = nt.nodes.new('ShaderNodeEmission')
    mix = nt.nodes.new('ShaderNodeAddShader'); out = nt.nodes.new('ShaderNodeOutputMaterial')
    em.inputs['Strength'].default_value = 0.8
    nt.links.new(tex.outputs['Color'], bsdf.inputs['Color']); nt.links.new(tex.outputs['Color'], em.inputs['Color'])
    nt.links.new(bsdf.outputs['BSDF'], mix.inputs[0]); nt.links.new(em.outputs['Emission'], mix.inputs[1])
    nt.links.new(mix.outputs['Shader'], out.inputs['Surface'])
    return m

def clay(name, rgb, rough=0.75):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (*rgb, 1); b.inputs['Roughness'].default_value = rough
    return m

# Floor: the photo ground, 24 m square.
bpy.ops.mesh.primitive_plane_add(size=24.0, location=(0, 0, 0))
floor = bpy.context.active_object
floor.data.materials.append(emission_image_material('floor', os.path.join(H, 'ground.png')))

# Walls.
before = set(bpy.data.objects)
bpy.ops.wm.obj_import(filepath=os.path.join(H, 'walls.obj'))
walls = [o for o in bpy.data.objects if o not in before]
for o, w in zip(sorted(walls, key=lambda o: int(o.name.split('_')[1].split('.')[0])), lay['walls']):
    c = w['color']
    tint = [0.78 * 0.65 + min(1.0, x * 1.4) * 0.35 for x in c]
    o.data.materials.clear(); o.data.materials.append(clay('wall', tint, 0.85))
    sol = o.modifiers.new('solid', 'SOLIDIFY'); sol.thickness = 0.12; sol.offset = 0
    bev = o.modifiers.new('bevel', 'BEVEL'); bev.width = 0.02; bev.segments = 2

def import_car(x, y, heading, ghost):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=GLB)
    new = [o for o in bpy.data.objects if o not in before]
    pivot = bpy.data.objects.new('pivot', None); sc.collection.objects.link(pivot)
    for o in new:
        if o.name.startswith('charge_cable'): o.hide_render = True
        if o.parent is None: o.parent = pivot
        if ghost and o.type == 'MESH' and not o.name.startswith('ground_shadow'):
            mat = clay('ghost', (0.42, 0.44, 0.48), 0.5)
            for i in range(len(o.material_slots)): o.material_slots[i].material = mat
            if not o.material_slots: o.data.materials.append(mat)
    # glTF nose is +Z -> Blender -Y; turn so the nose points along the heading (0 = +Y, our front).
    pivot.rotation_euler.z = math.pi - math.radians(heading)
    pivot.location = (x, y, 0)

import_car(0, 0, 0, ghost=False)
for v in lay['vehicles']:
    import_car(v['x'], v['y'], v['heading'], ghost=True)

world = bpy.data.worlds.new('W'); sc.world = world; world.use_nodes = True
bg = world.node_tree.nodes['Background']
bg.inputs['Color'].default_value = (0.035, 0.04, 0.05, 1); bg.inputs['Strength'].default_value = 1.0
for loc, e, size in [((5, 7, 9), 900, 8), ((-7, -5, 7), 500, 8), ((0, 0, 10), 700, 10)]:
    l = bpy.data.lights.new('l', 'AREA'); l.energy = e; l.size = size
    lo = bpy.data.objects.new('l', l); lo.location = loc
    lo.rotation_euler = (mathutils.Vector((0, 0, 0)) - mathutils.Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    sc.collection.objects.link(lo)

cam = bpy.data.cameras.new('cam'); cam.lens = 24
co = bpy.data.objects.new('cam', cam); sc.collection.objects.link(co); sc.camera = co
VIEWS = {'a_front_left': (-35, 28, 9.0), 'b_rear_right': (150, 22, 9.0), 'c_high': (-10, 62, 12.0), 'd_side_left': (-95, 15, 7.5)}
ORBIT = int(os.environ.get('ORBIT', '0'))
if ORBIT:
    sc.render.resolution_x, sc.render.resolution_y = 960, 600
    VIEWS = {f'orbit_{k:03d}': (-180 + 360 * k / ORBIT, 30 + 8 * math.sin(2 * math.pi * k / ORBIT), 9.5) for k in range(ORBIT)}
for name, (az, el, dist) in VIEWS.items():
    a, e = math.radians(az), math.radians(el)
    co.location = (dist * math.cos(e) * math.sin(a), dist * math.cos(e) * math.cos(a), dist * math.sin(e) + 0.6)
    co.rotation_euler = (mathutils.Vector((0, 0, 0.7)) - co.location).to_track_quat('-Z', 'Y').to_euler()
    sc.render.filepath = f'{OUT}_{name}.png'
    bpy.ops.render.render(write_still=True)
print('done')
