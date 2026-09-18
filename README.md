# BasketVibe Mobile 0.2.0

Android pocket scout backed directly by the generated `basketvibe_mobile.sqlite` database.

## What this build does

- Ships with the current generated BasketVibe mobile database so the first install works immediately.
- Search all cached players from `mobile_players`.
- Shows exact cached V2, K4, K3 and Legacy Production valuations.
- Shows the V2 bargain/fair/expensive check for active listings.
- Shows PG / SG / SF / PF / C manual role scores and height-qualified recommendation.
- Shows skills, development context and CERTAINTY_HYBRID/tier context.
- Shows the 8 cached `basketball_v1` comparable sales from `mobile_comparables`.
- Shows recorded transfer and listing history from the full database.
- Includes a collapsible view of every cached `mobile_players` analysis field.
- Keeps the entire SQLite database on-device; the app does not need internet access.

## Refreshing the phone database

1. On the PC run `export_mobile_db.py` / `run_mobile_export.bat`.
2. Copy the new `basketvibe_mobile.sqlite` to the phone (Downloads is fine).
3. In BasketVibe tap **IMPORT DB**.
4. Pick `basketvibe_mobile.sqlite`.
5. The app validates the SQLite file and atomically replaces the old on-device database.

No APK rebuild is needed when the database or cached model outputs change.

## Building the APK with GitHub Actions

The included `.github/workflows/build-apk.yml` builds a debug APK on every push to `main` and exposes it as the `BasketVibe-Pocket-Scout` Actions artifact.

## App ID

`com.elem86.basketvibemobile`
