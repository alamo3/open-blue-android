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
   `... 40 12 0 none 1100 904`; the screenshot-test still `car_still_port.webp` is
   `... -120 12 1 none 1100 904`.

Run with Blender's Python module (`pip install bpy`). Part names and hinge axes are mirrored in
`ui/CarModel.kt` (`Part`).
