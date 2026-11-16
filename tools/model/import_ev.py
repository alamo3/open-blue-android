"""Prepare an EV GLB for Open Blue.

Usage: blender -b --python tools/model/import_ev.py -- KIND SOURCE.glb OUT.glb
The importer bakes the source's hierarchy into metre-sized, Z-up meshes, combines
parts around their hinges and adds the cable, contact shadow and effect anchors.
The original GLB is never modified.
"""
import bpy, bmesh, json, math, os, struct, sys
import numpy as np
from mathutils import Matrix, Vector

KIND, SOURCE, OUT = sys.argv[sys.argv.index('--') + 1:]
CONFIG = {
    'ioniq9': dict(
        matrix=Matrix(((0, -1, 0, 0), (1, 0, 0, 0), (0, 0, 1, .01), (0, 0, 0, 1))),
        doors={'Door_Dummy_Front_RIGHT':'door_fl', 'Door_Dummy_Front_LEFT':'door_fr',
               'Door_Dummy_Rear_RIGHT':'door_rl', 'Door_Dummy_Rear_LEFT':'door_rr'},
        paint='clearcoat_sunset_brown', length=5.06, width=1.98, height=1.77,
        hood_hinge=(0, -1.30, 1.19), tail_hinge=(0, 2.08, 1.64),
        port=(-.965, 1.93, 1.08), port_normal=(-1, 0, 0), cowl=(1.30, 1.19), windshield_top=(.54, 1.70),
    ),
    'kona_electric': dict(
        matrix=Matrix(((1, 0, 0, .082), (0, 1, 0, .42), (0, 0, 1, .02), (0, 0, 0, 1))),
        doors={'Door_ElectricPreferred_Front_Right_Pivot':'door_fl',
               'Door_ElectricPreferred_Front_Left_Pivot':'door_fr',
               'Door_ElectricPreferred_Rear_Right_Pivot':'door_rl',
               'Door_ElectricPreferred_Front_Left_Pivot (1)':'door_rr'},
        paint='material-SLw1D', length=4.36, width=1.82, height=1.62,
        hood_hinge=(0, -.96, 1.13), tail_hinge=(0, 1.44, 1.54),
        port=(.38, -2.12, .85), port_normal=(0, -1, 0), cowl=(.96, 1.13), windshield_top=(.25, 1.59),
    ),
    'ioniq6': dict(
        matrix=Matrix.Identity(4),
        doors={'Door_Front_Right_DUMMY003':'door_fl','Door_Front_Left_DUMMY003':'door_fr',
               'Door_Rear_Right_DUMMY003':'door_rl','Door_Rear_Left_DUMMY003':'door_rr'},
        paint='material-n8ned', length=4.85, width=1.88, height=1.47,
        hood_hinge=(0,-1.11,1.00), tail_hinge=(0,1.52,1.13),
        port=(-.90,1.67,.89), port_normal=(-1,0,0), cowl=(1.11,1.00), windshield_top=(.355,1.37),
        simplify=True,
    ),
    'ioniq5': dict(
        matrix=Matrix(((0,-1,0,0),(1,0,0,-1.36),(0,0,1,.27),(0,0,0,1))),
        doors={'Door_Front_RIGHT_Dummy':'door_fl','Door_Front_LEFT_Dummy':'door_fr',
               'Door_Rear_RIGHT_Dummy':'door_rl','Door_Rear_LEFT_Dummy':'door_rr'},
        paint='material-Ioniq5N-AtlasWhite', length=4.66, width=1.89, height=1.64,
        hood_hinge=(0,-1.36,1.07), tail_hinge=(0,1.30,1.56),
        port=(-.93,1.66,.96), port_normal=(-1,0,0), cowl=(1.36,1.07), windshield_top=(.35,1.52),
        simplify=True,
    ),
    'elantra': dict(
        matrix=Matrix.Translation((0,.019,-.019)),
        doors={'EssentialDoor_Right_Front_DUMMY':'door_fl','EssentialDoor_Left_Front_DUMMY':'door_fr',
               'EssentialDoor_Right_Rear_DUMMY':'door_rl','EssentialDoor_Left_Rear_DUMMY':'door_rr'},
        paint='material-N-krf', length=4.73, width=1.80, height=1.42,
        hood_hinge=(0,-1.09,.96), tail_hinge=(0,1.78,1.09),
        port=(-.85,1.65,.80), port_normal=(-1,0,0), cowl=(1.09,.96), windshield_top=(.35,1.40), simplify=True,
    ),
    'elantra_n': dict(
        matrix=Matrix.Identity(4),
        doors={'Door_Right_Front_DUMMY':'door_fl','Door_Left_Front_DUMMY':'door_fr',
               'Door_Right_Rear_DUMMY':'door_rl','Door_Left_Rear_DUMMY':'door_rr'},
        paint='material-YlSpF', length=4.71, width=1.80, height=1.44,
        hood_hinge=(0,-1.10,.97), tail_hinge=(0,1.765,1.11),
        port=(-.85,1.65,.80), port_normal=(-1,0,0), cowl=(1.10,.97), windshield_top=(.35,1.40), simplify=True,
    ),
    'kona': dict(
        matrix=Matrix(((1,0,0,.082),(0,1,0,.42),(0,0,1,-.013),(0,0,0,1))),
        doors={'Door_Preferred_Front_Right_Pivot':'door_fl','Door_Preferredl_Front_Left_Pivot':'door_fr',
               'Door_Preferred_Rear_Right_Pivot':'door_rl','Door_Preferred_Front_Left_Pivot':'door_rr'},
        paint='material-SLw1D', length=4.36, width=1.82, height=1.60,
        hood_hinge=(0,-.96,1.10), tail_hinge=(0,1.44,1.52),
        port=(.38,-2.12,.85), port_normal=(0,-1,0), cowl=(.96,1.10), windshield_top=(.25,1.57), simplify=True,
    ),
    'palisade': dict(
        matrix=Matrix(((-1,0,0,0),(0,-1,0,.1185),(0,0,1,.001),(0,0,0,1))),
        doors={'Door_PreferredTrend_FL_DUMMY001':'door_fl','Door_PreferredTrend_FR_DUMMY001':'door_fr',
               'Door_PreferredTrend_RL_DUMMY001':'door_rl','Door_PreferredTrend_RR_DUMMY001':'door_rr'},
        paint='material-N-krf', length=5.08, width=1.98, height=1.82,
        hood_hinge=(0,-1.11,1.25), tail_hinge=(0,1.90,1.72),
        port=(-.95,1.9,1.0), port_normal=(-1,0,0), cowl=(1.11,1.25), windshield_top=(.35,1.75), simplify=True,
    ),
    'santa_fe': dict(
        matrix=Matrix(((1,0,0,0),(0,1,0,-.1475),(0,0,1,.01),(0,0,0,1))),
        doors={'Door_Front_Right_DUMMY':'door_fl','Door_Front_Left_DUMMY':'door_fr',
               'Door_Rear_Right_DUMMY':'door_rl','Door_Rear_Left_DUMMY':'door_rr'},
        paint='material-ArF-S', length=5.15, width=2.04, height=1.78,
        hood_hinge=(0,-1.27,1.20), tail_hinge=(0,1.83,1.71),
        port=(-.98,1.8,1.0), port_normal=(-1,0,0), cowl=(1.27,1.20), windshield_top=(.35,1.73), simplify=True,
    ),
    'sonata': dict(
        matrix=Matrix.Translation((.265,-.019,-.183)),
        doors={'Door_Front_Right_DUMMYPreferred':'door_fl','Door_Front_Left_DummyPreferred':'door_fr',
               'Door_Rear_Right_DUMMYPreferred':'door_rl','Door_Rear_Left_DummyPreferred':'door_rr'},
        paint='material-N-krf', length=5.28, width=1.88, height=1.53,
        hood_hinge=(0,-.95,1.07), tail_hinge=(0,1.70,1.15),
        port=(-.90,1.85,.85), port_normal=(-1,0,0), cowl=(.95,1.07), windshield_top=(.25,1.47), simplify=True,
    ),
    'tucson': dict(
        matrix=Matrix.Identity(4),
        doors={'Door_Front_Right_Dummy004':'door_fl','Door_Front_Left_Dummy004':'door_fr',
               'Door_Rear_Right_DUMMY004':'door_rl','Door_Rear_Left_Dummy004':'door_rr'},
        paint='material-jD0Vw', length=4.64, width=1.86, height=1.70,
        hood_hinge=(0,-1.05,1.16), tail_hinge=(0,1.63,1.61),
        port=(-.90,1.7,.95), port_normal=(-1,0,0), cowl=(1.05,1.16), windshield_top=(.25,1.64), simplify=True,
    ),
    'venue': dict(
        matrix=Matrix.Identity(4),
        doors={'Door_Front_Right_Essential_DUMMY':'door_fl','Door_Front_Left_Essential_DUMMY':'door_fr',
               'Door_Rear_Right_Essential_DUMMY':'door_rl','Door_Rear_Left_Essential_DUMMY':'door_rr'},
        paint='material-N-krf', length=4.04, width=1.74, height=1.69,
        hood_hinge=(0,-.93,1.09), tail_hinge=(0,1.39,1.54),
        port=(-.85,1.45,.95), port_normal=(-1,0,0), cowl=(.93,1.09), windshield_top=(.20,1.58), simplify=True,
    ),
}
P=CONFIG[KIND]; TRANSFORM=P['matrix']
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=SOURCE)
bpy.context.view_layer.update()
O=bpy.data.objects

def parent_names(o):
    names=[]
    while o.parent:
        o=o.parent; names.append(o.name)
    return names

pivots={part: TRANSFORM @ O[name].matrix_world.translation for name,part in P['doors'].items()}
pivots.update(hood=Vector(P['hood_hinge']), tailgate=Vector(P['tail_hinge']))
groups={n:[] for n in ('body','door_fl','door_fr','door_rl','door_rr','hood','tailgate')}

def part_for(o):
    parents=parent_names(o)
    for name,part in P['doors'].items():
        if name in parents: return part
    name=o.name
    if KIND=='ioniq9':
        # The bonnet and hatch are already separate pieces but lack hinges.
        if name == 'BodyPaint_Essential (7)' or name == 'Car_Ext_Details_008': return 'hood'
        if name.startswith('Back_Details') and not name.startswith(('Back_Details003','Back_Details031')):
            # Tailgate pieces, excluding low bumper and side lights.
            points=[o.matrix_world @ Vector(v) for v in o.bound_box]
            if min(p.x for p in points)>2.10 and min(p.z for p in points)>.65: return 'tailgate'
        if name in ('BodyPaint_Essential (6)','BodyPaint_Essential (8)'): return 'tailgate'
    elif KIND=='kona_electric':
        if name == 'Kona_ElectricPreferredBodyPaint_1 (1)' or name == 'silver_logo_f_LRexterior': return 'hood'
        if name in ('Kona_ElectricPreferredBodyPaint_1 (4)','Kona_ElectricPreferredBodyPaint_1 (5)',
                    'glassDarkB_rearWindow_LRexterior','plasticGlossy_tailgate_LRexterior',
                    'plasticD_rearWindowFrame_LRexterior','plasticBlack_wiper_r_LRexterior',
                    'reflect_brakeLight_LRexterior','glassRedMatt_brakeLight_LRexterior',
                    'plasticD_brakelight_LRexterior','plasticInt_tailgate_LRinterior002',
                    'silver_Kona_LRexterior','silver_logo_r_LRexterior','plastic_cam_r_LRexterior'):
            return 'tailgate'
    elif KIND=='ioniq6':
        if name == 'Ioniq6_BodyPaint_2 (2)': return 'hood'
        if 'tailgate01' in parents or name in (
            'Ioniq6_BodyPaint_2 (8)', 'Object030', 'Object031', 'RearBrakeLightGlass001',
            'TailLightMatteBlackBacking001', 'TailLightGlass003', 'TailLightMatteBacking001',
            'TailgateCamera001', 'TailgateCameraButtons001', 'TailgateCameraLens001',
            'SpoilerReflector003', 'Spoiler001', 'HyundaiBumperLogo001',
            'Ioniq6RearBadge001', 'IONIQbadgeBacking001',
        ): return 'tailgate'
    elif KIND=='ioniq5':
        # The bonnet is a single clamshell panel reaching the headlights.
        if name in ('Body_', 'BodyPaint_LongRange (1)'): return 'hood'
        if name in ('BodyPaint_LongRange (4)','BodyPaint_LongRange (5)','BodyPaint_LongRange (6)',
                    'Box134','Object162','Object2588',
                    'Object113','Object115','Object117','Object119','Object121','Object123',
                    'Object125','Object127','Object133','Object135','Object137','Object140',
                    'Object142','Object144','Object146','Object156','Object161','Object309',
                    'Object674','Interior_001','Interior_002','Interior_003','Interior_009',
                    'Interior_010','Interior_011','Interior_017','Interior_018','Interior_019',
                    'Interior_020','Interior_021'):
            return 'tailgate'
    elif KIND in ('elantra','elantra_n'):
        hood='Elantra_EssentialBodyPaint_1 (8)' if KIND=='elantra' else 'Elantra_NBodyPaint_1'
        tail='Elantra_EssentialBodyPaint_1 (4)' if KIND=='elantra' else 'Elantra_NBodyPaint_1 (8)'
        if name==hood: return 'hood'
        if name==tail or name.startswith(('TrunkTrim','TrunkLight','TrunkSkirt','TrunkDoorSensor','logo_trunk_0')) or name in (
                'RearElantraText','BadgeColour2','BrakeLightReflector','BrakeLightSurround',
                'BrakeLightGlass','light_glass','SmallTailightReflector','wing_003','wing_002','Object101'):
            return 'tailgate'
    elif KIND=='kona':
        if name in ('Kona_PreferredBodyPaint_1 (9)','silver_logo_f_LRexterior'): return 'hood'
        if name in ('Kona_PreferredBodyPaint_1 (4)','Kona_PreferredBodyPaint_1 (5)',
                    'glassDarkB_rearWindow_LRexterior','plasticGlossy_tailgate_LRexterior',
                    'plasticD_rearWindowFrame_LRexterior','plasticBlack_wiper_r_LRexterior',
                    'reflect_brakeLight_LRexterior','glassRedMatt_brakeLight_LRexterior',
                    'plasticD_brakelight_LRexterior','plasticInt_tailgate_LRinterior',
                    'silver_Kona_LRexterior','silver_logo_r_LRexterior','plastic_cam_r_LRexterior'):
            return 'tailgate'
    elif KIND=='palisade':
        if name=='BodyPaint_PreferredTrend (11)': return 'hood'
        if name.startswith('trunkDoor_') or name in ('BodyPaint_PreferredTrend (5)', 'BodyPaint_PreferredTrend (7)',
                'BodyPaint_PreferredTrend (6)','BodyPaint_PreferredTrend (10)',
                'BodyPaint_PreferredTrend (12)', 'bumper_r_09_msx2_LRE', 'BlackPlastic (2)'):
            return 'tailgate'
    elif KIND=='santa_fe':
        if name in ('BodyPaintSantaFePreferred_1 (3).001','HyundaiHoodLogo'): return 'hood'
        if name.lower().startswith(('trunk_', 'trunkpart', 'trunk_part', 'trunkplastic')) or name in (
                'BodyPaintSantaFePreferred_1 (1).001','RearGlass','TaillightsRedReflector',
                'TaillightsGlass','Object045','Object049'):
            return 'tailgate'
    elif KIND=='sonata':
        if name in ('BodyPaint_SonataPreferred_1 (15)','HyundaiBadge002'): return 'hood'
        if name in ('BodyPaint_SonataPreferred_1 (5)','BodyPaint_SonataPreferred_1 (13)',
                    'Spoiler','RearGlass001','Group_140','Group_141','Group_142','asdads001',
                    'Object642','Object644','Object682','Object683','Object684','Object685',
                    'Object691','HyundaiBadge003','Plane.113','Box085','Text007'):
            return 'tailgate'
    elif KIND=='tucson':
        if name=='TucsonBodyPaintPreferred_1 (4).001': return 'hood'
        if name.startswith(('BootWindow','door_trunk_part','PlasticBootLightCasing','BootLightGlass','BootReflector')) or name in (
                'body_part3','part_r001','TucsonBadge','logo_f_part_001','RedTailLightReflector3','TailLightGlass3',
                'InnerLightCasing','RearPlasticBodywork','RedRearStopLight','RearWiper','TailLightCasing5','TailLightCasing4',
                'TucsonBodyPaintPreferred_1 (6).001','TucsonBodyPaintPreferred_1 (7).001',
                'TucsonBodyPaintPreferred_1 (1).002','TucsonBodyPaintPreferred_1 (3).002',
                'TucsonBodyPaintPreferred_1 (4).002'):
            return 'tailgate'
    elif KIND=='venue':
        if name=='Venue_EssentialBodyPaint_1 (4)': return 'hood'
        if name.lower().startswith(('tailgate','trunklight','trunksensor')) or name in (
                'Venue_EssentialBodyPaint_1 (7)','Venue_EssentialRoofPaint_1','RearWindowGlass001','Object156'):
            return 'tailgate'
    return 'body'

# Detach the geometry from all animated source transforms. The app controls one
# rigid node per panel and independently animates each of the four doors.
for o in list(O):
    if o.type!='MESH': continue
    part=part_for(o)
    mat=TRANSFORM @ o.matrix_world
    o.data=o.data.copy()
    # The source contains very dense stitching, buttons and interior details.
    # Keep the visible painted surfaces intact and reduce only other heavy meshes.
    if P.get('simplify') and len(o.data.polygons)>4000 and not any(
        m and m.name.startswith(P['paint']) for m in o.data.materials
    ):
        bpy.ops.object.select_all(action='DESELECT'); o.select_set(True); bpy.context.view_layer.objects.active=o
        modifier=o.modifiers.new('mobile_detail','DECIMATE')
        modifier.ratio=.30 if any(k in o.name.lower() for k in ('stitch','button','seatbackinsert','tailgate_light_edge')) else .55
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    o.data.transform(mat)
    if mat.determinant()<0: o.data.flip_normals()
    o.parent=None; o.matrix_world=Matrix.Identity(4)
    if KIND in ('kona','kona_electric') and o.name in (
        'glassClear_taillightLED_LRexterior','plasticD_taillightLED_LRexterior','ledBumpBR_taillightLED_LRexterior',
    ):
        # The source combines fixed corner lights and the light across the hatch.
        bm=bmesh.new(); bm.from_mesh(o.data)
        for x in (-.65,.65):
            bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                                   dist=.00001,plane_co=(x,0,0),plane_no=(1,0,0))
        bm.faces.ensure_lookup_table(); bm.faces.index_update()
        selected={f.index for f in bm.faces if abs(f.calc_center_median().x)<.6501}
        for name,inside in (('body',False),('tailgate',True)):
            piece=bm.copy(); piece.faces.ensure_lookup_table()
            bmesh.ops.delete(piece,geom=[f for f in piece.faces if (f.index in selected)!=inside],context='FACES')
            loose=[v for v in piece.verts if not v.link_faces]
            if loose: bmesh.ops.delete(piece,geom=loose,context='VERTS')
            mesh=o.data.copy(); piece.to_mesh(mesh); piece.free()
            obj=O.new(o.name+'_'+name,mesh); bpy.context.scene.collection.objects.link(obj); groups[name].append(obj)
        bm.free(); O.remove(o,do_unlink=True)
    else:
        groups[part].append(o)

for o in list(O):
    if o.type!='MESH': O.remove(o,do_unlink=True)

paint=bpy.data.materials.get(P['paint'])
if paint is None: raise ValueError('Missing body paint material: '+P['paint'])
for o in O:
    if o.type != 'MESH': continue
    for i,m in enumerate(o.data.materials):
        if m and (m.name == P['paint'] or m.name.startswith(P['paint']+'.')):
            o.data.materials[i]=paint
paint.name='paint'
if paint.use_nodes:
    shader=next((n for n in paint.node_tree.nodes if n.type=='BSDF_PRINCIPLED'),None)
    if shader:
        if KIND=='santa_fe':
            for link in list(shader.inputs['Base Color'].links): paint.node_tree.links.remove(link)
        shader.inputs['Base Color'].default_value=(.78,.78,.75,1)

for name,objects in groups.items():
    if not objects: raise ValueError('No geometry assigned to '+name)
    bpy.ops.object.select_all(action='DESELECT')
    for o in objects: o.select_set(True)
    bpy.context.view_layer.objects.active=objects[0]; bpy.ops.object.join()
    obj=bpy.context.view_layer.objects.active; obj.name=name
    if name in pivots:
        origin=pivots[name]
        for v in obj.data.vertices: v.co-=origin
        obj.location=origin
    print('PART',name,'polygons',len(obj.data.polygons),'pivot',tuple(round(v,3) for v in obj.location),flush=True)

# Soft contact shadow; transparent and unlit, just like the existing car assets.
L,W,H=P['length'],P['width'],P['height']; hx=W/2+.45; hy=L/2+.45
nx,ny=192,384
xx,yy=np.meshgrid(np.linspace(-hx,hx,nx),np.linspace(-hy,hy,ny))
r=np.maximum(np.abs(xx)/(W*.48),np.abs(yy)/(L*.48))
a=np.clip(.42/(1+np.exp(np.clip((r-.88)*13,-40,40))),0,.42)
rgba=np.zeros((ny,nx,4),dtype=np.float32); rgba[:,:,3]=a
img=bpy.data.images.new('ground_shadow',width=nx,height=ny,alpha=True); img.pixels=rgba.ravel().tolist(); img.filepath_raw=f'/tmp/openblue_ev_shadow_{KIND}.png'; img.file_format='PNG'; img.save(); img.pack()
mesh=bpy.data.meshes.new('ground_shadow'); mesh.from_pydata([(-hx,-hy,.003),(hx,-hy,.003),(hx,hy,.003),(-hx,hy,.003)],[],[(0,1,2,3)])
uv=mesh.uv_layers.new(name='UVMap')
for i,p in enumerate(((0,0),(1,0),(1,1),(0,1))): uv.data[i].uv=p
obj=bpy.data.objects.new('ground_shadow',mesh); bpy.context.scene.collection.objects.link(obj)
mat=bpy.data.materials.new('ground_shadow'); mat.use_nodes=True; tree=mat.node_tree
bsdf=next(n for n in tree.nodes if n.type=='BSDF_PRINCIPLED'); tex=tree.nodes.new('ShaderNodeTexImage'); tex.image=img
tree.links.new(tex.outputs['Color'],bsdf.inputs['Base Color']); tree.links.new(tex.outputs['Alpha'],bsdf.inputs['Alpha'])
if hasattr(mat,'surface_render_method'): mat.surface_render_method='BLENDED'
else: mat.blend_method='BLEND'
mesh.materials.append(mat)

# Connector points outward from the charge port, then the cable falls to the ground.
port=Vector(P['port']); normal=Vector(P['port_normal']).normalized(); up=Vector((0,0,1))
end=port+normal*1.9; end.z=.018
ctrl=[port+normal*.19,port+normal*.31-up*.06,port+normal*.42-up*.5,
      port+normal*.62-up*min(.92,port.z-.06),port+normal*1.05-up*(port.z-.018),end]
def catmull(points,steps=12):
    pts=[points[0]]+points+[points[-1]]; out=[]
    for i in range(1,len(pts)-2):
        a,b,c,d=pts[i-1:i+3]
        for j in range(steps):
            t=j/steps; out.append(.5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t))
    return out+[points[-1]]
path=catmull(ctrl)
curve=bpy.data.curves.new('cable','CURVE'); curve.dimensions='3D'; curve.bevel_depth=.013; curve.bevel_resolution=3; curve.use_fill_caps=True
spline=curve.splines.new('POLY'); spline.points.add(len(path)-1)
for i,p in enumerate(path): spline.points[i].co=(*p,1)
obj=bpy.data.objects.new('cable_tmp',curve); bpy.context.scene.collection.objects.link(obj)
mesh=bpy.data.meshes.new_from_object(obj.evaluated_get(bpy.context.evaluated_depsgraph_get())); O.remove(obj,do_unlink=True)
bm=bmesh.new(); bm.from_mesh(mesh)
def cylinder(r1,r2,length,start):
    rotation=normal.to_track_quat('Z','Y').to_matrix().to_4x4()
    bmesh.ops.create_cone(bm,cap_ends=True,segments=24,radius1=r1,radius2=r2,depth=length,matrix=Matrix.Translation(start+normal*(length/2)) @ rotation)
cylinder(.034,.036,.11,port); cylinder(.036,.03,.04,port+normal*.11); cylinder(.022,.015,.05,port+normal*.148)
bm.to_mesh(mesh); bm.free(); obj=O.new('charge_cable',mesh); bpy.context.scene.collection.objects.link(obj)
mat=bpy.data.materials.new('charge_cable'); mat.use_nodes=True; shader=next(n for n in mat.node_tree.nodes if n.type=='BSDF_PRINCIPLED'); shader.inputs['Base Color'].default_value=(.012,.013,.014,1); shader.inputs['Roughness'].default_value=.55
mesh.materials.append(mat)
for face in mesh.polygons: face.use_smooth=True
for v in mesh.vertices: v.co-=port
obj.location=port

def gl(v): return [round(v[0],3),round(v[2],3),round(-v[1],3)]
# Ray-cast the hood so the existing climate effect follows this model's surface.
bpy.context.view_layer.update()
hood=O['hood']; inverse=hood.matrix_world.inverted()
def height(y):
    ok,loc,_,_=hood.ray_cast(inverse @ Vector((0,y,4)),(0,0,-1))
    return (hood.matrix_world @ loc).z if ok else None
hood_profile=[]
for y in np.arange(-L/2+.15,-P['cowl'][0]+.001,.1):
    z=height(y)
    if z is not None: hood_profile.append([round(-y,3),round(z,3)])
if len(hood_profile)<2: raise ValueError('Cannot sample hood surface')
anchors=dict(kind=KIND,length=L,width=W,height=H,nose=L/2,chargePort=gl(port),chargePortNormal=gl(normal),
             cablePath=[gl(p-port) for p in path[::3]],hood=hood_profile,cowl=P['cowl'],windshieldTop=P['windshield_top'])
with open(os.path.splitext(OUT)[0]+'-anchors.json','w') as f: json.dump(anchors,f,indent=1)

bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=OUT,export_format='GLB',export_yup=True,export_apply=True,export_animations=False)
# The shadow must be unlit even under the app's studio lighting.
data=open(OUT,'rb').read(); jlen=struct.unpack_from('<I',data,12)[0]; doc=json.loads(data[20:20+jlen])
for m in doc['materials']:
    if m.get('name')=='ground_shadow': m.setdefault('extensions',{})['KHR_materials_unlit']={}; m['alphaMode']='BLEND'
    m['doubleSided']=True
doc.setdefault('extensionsUsed',[])
if 'KHR_materials_unlit' not in doc['extensionsUsed']: doc['extensionsUsed'].append('KHR_materials_unlit')
js=json.dumps(doc,separators=(',',':')).encode(); js+=b' '*(-len(js)%4); rest=data[20+jlen:]
with open(OUT,'wb') as f: f.write(struct.pack('<III',0x46546c67,2,20+len(js)+len(rest))+struct.pack('<II',len(js),0x4e4f534a)+js+rest)
print('EXPORTED',OUT,os.path.getsize(OUT),flush=True)
sys.stdout.flush(); os._exit(0)
