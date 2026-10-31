# This fork

A copy of [alamo3/open-blue-android](https://github.com/alamo3/open-blue-android) set up for a 2024 IONIQ 6 AWD in Nocturne Grey. Everything else is the author's app, unchanged.

## What's different

- **IONIQ 6 model.** A detailed model of the 2023–25 IONIQ 6 ("Hyundai Ioniq 6" by Mona x Supercars, Sketchfab, CC BY 4.0, credited in Settings and `app/src/main/assets/models/car_ioniq6-LICENSE.txt`), converted and rigged by `tools/model/prep6.py` and `rig6.py`: doors, frunk and trunk open, Nocturne Grey paint with the roof in body colour, black interior, no front plate, charging cable at the right-rear port, and its own top-down cabin on the Climate screen (`cabin_top_ioniq6.webp`, `IONIQ6_CABIN` in `ui/CabinView.kt`). Output: `car_ioniq6.glb` and its anchors, the still `car_still_ioniq6.webp` and the map marker `car_top_ioniq6.webp`. `ui/CarBody.kt` picks it for any Bluelink model name containing "IONIQ 6". See `tools/model/README.md`.
- **Nocturne Grey** paint replaces the generic grey swatch (`ui/CarBody.kt`, `CarPaint.GREY`) and is the default until another colour is chosen in Settings > Your car in 3D. Hyundai publishes no colour value, so it's matched by eye to a dark charcoal.
- **Updates come from this fork.** `update/Updater.kt` reads this repository's `latest` release, so the app only installs builds made here.
- Screenshot tests use an IONIQ 6 as the demo car.

## Keeping up with the author

On GitHub, open this repository and tap **Sync fork**, then **Update branch**. The push builds a new APK, publishes it as the `latest` release, and the app installs it by itself within a few hours (or at once from Settings > App updates). If no build starts, open **Actions > Build APK > Run workflow**. If GitHub says it can't sync automatically, the author changed a file this fork also changes; ask Claude to merge it.

## Signing

Builds are signed with this fork's own key, held in two repository secrets: `OPENBLUE_KEYSTORE_B64` and `OPENBLUE_KEYSTORE_PASSWORD`. Without them each build gets a throwaway key and has to be installed over an uninstall.
