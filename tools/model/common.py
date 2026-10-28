import bpy, math, mathutils

def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)

def setup_render(w=900, h=560, samples=24):
    sc = bpy.context.scene
    sc.render.engine = 'CYCLES'
    sc.cycles.device = 'CPU'
    sc.cycles.samples = samples
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.film_transparent = False
    world = bpy.data.worlds.new("W"); sc.world = world
    world.use_nodes = True
    world.node_tree.nodes['Background'].inputs[0].default_value = (0.85, 0.87, 0.9, 1)
    world.node_tree.nodes['Background'].inputs[1].default_value = 1.0
    sun = bpy.data.lights.new("sun", 'SUN'); sun.energy = 3
    o = bpy.data.objects.new("sun", sun); sc.collection.objects.link(o)
    o.rotation_euler = (math.radians(50), 0, math.radians(30))

def camera_at(loc, target=(0, 0, 0.6), lens=40):
    sc = bpy.context.scene
    cam = bpy.data.cameras.new("cam"); cam.lens = lens
    o = bpy.data.objects.new("cam", cam); sc.collection.objects.link(o)
    o.location = loc
    d = mathutils.Vector(target) - mathutils.Vector(loc)
    o.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    sc.camera = o
    return o

def render(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
