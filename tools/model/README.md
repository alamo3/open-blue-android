# Car model pipeline

`rig.py` turns the CC BY 4.0 "Hyundai Ioniq 5 - Lowpoly" glTF by andikapratamaw into the app's
`app/src/main/assets/models/ioniq5.glb`:

- cuts the four doors, the frunk (hood) and the tailgate out of the single body mesh along the
  model's own panel seams and moves each part's origin onto its hinge;
- adds a dark cabin so open doors don't show an empty shell;
- makes materials double-sided.

Run with Blender's Python module (`pip install bpy`):

```sh
python rig.py <dir containing model/gltf/scene.gltf> out/ioniq5.glb
python preview.py out/ioniq5.glb renders open   # Cycles renders with everything open
```

Part names and hinge axes are mirrored in `ui/CarModel.kt` (`Part`).
