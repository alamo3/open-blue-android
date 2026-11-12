# Car model pipeline

Source: "2024 Hyundai Ioniq 5 N" by Ddiaz Design (Sketchfab), CC BY-NC 4.0. Output:
`app/src/main/assets/models/car.glb`.

1. `prep.py <scene.gltf> stage1.blend` scales to metres and merges the 1,620 pieces by kind
   (body, glass, interior, engine bay, wheels).
2. `rig.py stage1.blend car.glb`:
   - the model's panels are separate islands, so each moving part (`door_fl/fr/rl/rr`, `hood`,
     `tailgate`) is the set of islands inside its region; pieces spanning regions stay on the body;
   - origins moved onto the hinges;
   - interior, wheels and engine bay simplified (paint panels left alone to keep smooth shading);
   - paint set to Atlas White, the N's red accents to dark grey;
   - a soft contact shadow baked from the car's top-down silhouette onto an unlit ground plane
     (`ground_shadow`), so the car sits on the ground;
   - a charging cable and plug (`charge_cable`, origin on the charge port) that the app shows
     while plugged in;
   - writes `car-anchors.json` next to the model: the charge port, its normal, the cable path and
     surface profiles used to place the app's effects (copied into `ui/CarEffects.kt`).
3. `views.py car.glb <outdir> <tag> door_fl,hood|none` renders Cycles previews with parts open;
   `envlook.py car.glb studio.hdr out.png` renders it under the app's studio HDR.
4. `appview.py car.glb studio.hdr out.png <azimuth> <elevation> <cable 0|1> <parts|none> <w> <h>`
   renders the car exactly as the app frames it (`appcam.py` mirrors `ui/CarCamera.kt`). The
   still shown where 3D can't run (`res/drawable-nodpi/car_still.webp`) is
   `... 40 12 0 none 1100 748`; the screenshot-test still `car_still_port.webp` is
   `... -120 12 1 none 1100 748`.
5. `cabinview.py car.glb studio.hdr cabin.png 1.08 700 1500 -2.4 2.4` cuts the roof off at
   1.08 m and renders the cabin from above for the climate screen
   (`res/drawable-nodpi/cabin_top.webp`); seat positions are in `ui/CabinView.kt`.

Run with Blender's Python module (`pip install bpy`). Part names and hinge axes are mirrored in
`ui/CarModel.kt` (`Part`).

## Stand-ins for other cars

`generic.py <sedan|crossover|suv> out.glb` builds a stand-in from scratch (no third-party meshes):
the body is lofted from a side profile, a plan-view outline and cross-sections, with rows laid out
so the window line and roof edge fall on mesh edges. It's split into the same hinged parts as the
IONIQ 5 rig (`door_fl/fr/rl/rr`, `hood`, `tailgate`, `charge_cable`, `ground_shadow`), and the paint
is a material called `paint` so the app can recolour it. It writes `out-anchors.json` (size, charge
port and cable, bonnet profile, cowl and windshield top) for the effects and camera framing.
Outputs live in `app/src/main/assets/models/car_<kind>.glb` with their anchors; the stills are
`appview.py` with `CAR_ANCHORS=<anchors>` (so the framing matches the app's) and the map markers a
top-down orthographic render (`res/drawable-nodpi/car_still_<kind>.webp`, `car_top_<kind>.webp`).
`ui/CarBody.kt` picks one from the Bluelink model name (or Settings > Your car in 3D).

## IONIQ 6

Source: "Hyundai Ioniq 6" by Mona x Supercars (Sketchfab, CC BY 4.0), downloaded in its original
format, a SketchUp COLLADA export (`model.dae`). Output: `app/src/main/assets/models/car_ioniq6.glb`,
its anchors and `car_ioniq6-LICENSE.txt`.

1. `prep6.py model.dae stage1.blend` reads the COLLADA with pycollada (`pip install pycollada`;
   Blender 5 has no COLLADA importer), drops SketchUp's edge lines and the duplicate back faces,
   scales to the real 4,855 mm length and builds one object per material.
2. `rig6.py stage1.blend car_ioniq6.glb`:
   - paint (the material `paint`) set to Nocturne Grey and the black roof panel painted to match;
     the pale grey interior made black;
     glass, light lenses, lamps, rims and tyres given phone-friendly materials;
   - the front plate removed (Alberta);
   - islands sorted by region, as in `rig.py`: anything inside a wheel's space joins `wheels`;
     `door_fl/fr/rl/rr` (with glass, handles, trim and the door-mounted mirrors), `hood`, and
     `tailgate` (the trunk lid with its wing, emblem and stop lamp), origins on the hinges;
   - wheels, interior and trim decimated, paint panels left alone (about 263,000 triangles);
   - ground shadow, charging cable at the right-rear port, and `car_ioniq6-anchors.json` in
     `generic.py`'s format.
3. The still and the map marker: `appview.py` with `CAR_ANCHORS=car_ioniq6-anchors.json` (`... 40 12 0
   none 1100 748`), and a top-down orthographic render cropped to the car and scaled to 240 px wide.
4. The climate screen's cabin: `cabinview.py car_ioniq6.glb studio.hdr cabin.png 1.06 700 1500 -2.45 2.45`
   (`res/drawable-nodpi/cabin_top_ioniq6.webp`); its seat, steering wheel and windshield areas are
   `IONIQ6_CABIN` in `ui/CabinView.kt`.
