# 3D surroundings tools

Python prototypes behind the app's 3D view (`app/.../svm3d`). Put one set of surround-view
frames in `DIR/in/` as `front.jpg`, `rear.jpg`, `left.jpg`, `right.jpg` (960x720 fisheyes) and
`top.jpg` (the car's stitched top view), and the ONNX models in `DIR/models/`. Photos are never
committed: they show where the car parks.

1. `calib.py DIR`, then `calib2.py DIR`, then `calib3.py DIR`: solve each camera's pose and the
   shared fisheye lens by matching the cameras' view of the ground to the car's own top view
   (photometric search, then optical-flow correspondences with least squares). Writes
   `DIR/calib.json`; `check.py DIR` draws a checkerboard overlay to judge the fit. The result is
   hard-coded in `SvmCalibration` (Lens.kt).
2. `depth.py DIR vits|vitl`: flatten each fisheye into three perspective crops, run Depth Anything
   V2, scale its relative depth to metres from the floor.
3. `build.py DIR` + `render.py`: approach A, photo-textured meshes (too messy at night).
4. `hybrid.py DIR` + `render_hybrid.py`: the approach the app uses: YOLO for vehicles (placed as
   stand-in cars), a parking-sensor-style sweep for walls, the stitched floor.
