# Cell Tower Locator (free, open-source)

An Android app that shows **which cell tower your phone is connected to, where that tower is,
how far away it is, and an arrow pointing to it**. There are no ads and no premium plan, and
it uses only free data sources.

## Features
| Feature | How it works |
|---|---|
| Live connected-cell info | Reads 2G/3G/4G/5G: MCC-MNC, TAC/LAC, Cell ID, **eNB + sector** (LTE), PCI, channel, operator. Supports dual SIM. |
| **Timing Advance distance** | When the modem reports LTE/GSM Timing Advance, shows the tower's distance (±39 m on LTE) and draws it as an orange ring on the map |
| **Best-fit tower position** | Fits a radio path-loss model to all your signal readings, the TA rings and the database position together (see "How it works"). Gives an **honest ±error radius**. |
| Database position | Free **OpenCelliD** lookup, cached on the phone |
| Three target modes | BEST (everything fused) / OpenCelliD only / My measurements only. The app also shows how far apart the database and your measurements are. |
| Saved measurements | Every point is saved per tower, so accuracy keeps improving across days. Export to CSV, or long-press Export to clear. |
| Hunt sound | Beeps faster as the signal gets stronger, so you can walk toward the mast without staring at the screen |
| Trend indicator | ▲ stronger / ▼ weaker over the last few seconds |
| Direction arrow | Compass, corrected to true north, points to the target. It fades when you're inside the error circle. |
| Free map | OpenStreetMap with the database marker (blue), best fit (green), error circles, TA ring and a line to the target |
| Directions | Opens walking directions to the tower in Google Maps |
| Neighbour cells | Nearby cells by signal strength |
| Verified by tests | `TowerSolverTest` simulates hidden towers and checks the solver's accuracy on every build |

## What "precise" really means (please read)
No free or paid app knows exact tower coordinates. Operators don't publish them in India.
Every app, including the paid ones, uses **crowdsourced estimates**: the average position
of the phones that saw that cell. In practice:
* OpenCelliD positions are often **100 m – 1 km** away from the real mast. The app shows the `±range`.
* For the most precise result, combine three things:
  1. Use the OpenCelliD position to reach the right area.
  2. Then **walk around** with the app open. The "My estimate" point improves as you collect points, and you should
     **circle** the area rather than walk in a straight line.
  3. Watch the **dBm number**. It rises as you get closer and is strongest in front of the antenna sector.
     The last 100 m is best done by looking up for the mast.
* **5G SA (Jio True5G)** cells are often missing from the database. Your own estimate still works for them.
* **iPhones can't do this.** iOS doesn't give apps cell IDs. This is Android-only.

## Step 1: Get a free OpenCelliD key
1. Sign up at https://opencellid.org (free).
2. Copy your **access token** (it starts with `pk.`).
3. Paste it in the app when it asks, or later with the **API key** button.

## Step 2: Get the APK. Option A needs no software install.
### A) Build free on GitHub
1. Create a free GitHub account and a new repository.
2. Upload all the files in this folder, keeping the folder structure. On GitHub use "Add file → Upload files" and drag the whole folder in.
   Make sure `.github/workflows/build-apk.yml` is uploaded. Hidden folders are sometimes skipped.
   If it's missing, use **Add file → Create new file**, type the name `.github/workflows/build-apk.yml`,
   and paste in the contents of that file.
3. Open the **Actions** tab. The "Build APK" job starts automatically and takes about 5 min.
4. Open the finished run and download **CellTowerLocator-apk** (a zip). Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone and install it. Allow "Install unknown apps" when asked.

### B) Build with Android Studio (free)
1. Install Android Studio and choose **Open** on this folder.
2. Let Gradle sync finish. It downloads what it needs.
3. Connect your phone (with USB debugging on) and press ▶ Run.
   Or use **Build → Build APK(s)**.

## Step 3: Use it
1. Allow **precise** location and keep **Location ON**. Android hides cell IDs otherwise.
2. Go outdoors for a GPS fix. Hold the phone **flat**, with the top pointing ahead.
3. Tap **Locate tower**. Follow the red arrow or tap **Directions**.
4. If the database doesn't have the tower, or you want better accuracy, walk around for a few minutes
   and tap **DB / My estimate** to switch the target.
5. If the compass says it's inaccurate, wave the phone in a figure-8 away from metal and magnets.
6. With two SIMs, use **Switch SIM / cell**.

## Checking your result for free
* View the free CellMapper map in a browser (cellmapper.net). Choose your operator and search by eNB ID,
  which this app shows. Their community often marks confirmed mast locations.
* You can improve the free data for everyone with **Tower Collector** (F-Droid / Play Store). It uploads to OpenCelliD.

## How it works (for your report or viva)
* `CellReader.kt` reads `TelephonyManager.requestCellInfoUpdate()` and parses `CellInfoLte/Nr/Wcdma/Gsm`.
  For LTE, eNB = ECI >> 8. One LTE TA step is 16 Ts = 0.52 µs round trip, which is about 78.12 m one way.
* `TowerLookup.kt` calls `opencellid.org/cell/get` (JSON) and caches the result.
* `TowerSolver.kt` (the core) minimises a combined χ² cost over candidate tower positions p:
  * **Signal term:** RSRPᵢ = A − 10·n·log₁₀(dᵢ(p)). A and n are solved by linear regression at every candidate, with n limited to 1.6–5.0 and σ = 6 dB shadowing.
  * **TA term:** (dᵢ(p) − TAᵢ·78 m)² / (80 m)².
  * **Prior term:** distance to the OpenCelliD point divided by σ (half the database range).
  * The search is a coarse-to-fine grid (81² then 41² ×3, searching up to 15 km, then refining to about 0.1 m).
    The ±error is the 68 % contour (Δχ² = 2.3) measured in 16 directions.
  * Unlike a centroid, the fit can place the tower outside the area you walked.
    In the simulated tests the error falls from about 280 m to about 120 m when walking beside a tower,
    is about 30 m when looping around it, and is about 40 m from just three TA readings.
* `MeasurementStore.kt` saves points as JSON per site and exports CSV.
* `Compass.kt` uses the rotation vector, adds `GeomagneticField` declination and applies a wrap-safe low-pass filter. `GeoMath.kt` has the haversine distance and bearing.

## Getting the most accurate result
1. Tap **Locate tower** to get the database position.
2. **Start a big download or a video.** The phone only reports Timing Advance during active data. Each reading is a ring with a radius known to ±39 m.
3. Walk or drive so the tower is **surrounded on at least two sides**. A single straight line past it leaves a left/right ambiguity that only TA or the database can resolve.
4. Watch the green ±error circle shrink. Once you're inside it, switch on **Hunt sound** and look up for the mast.

## Limits
* OpenCelliD free keys have a daily request limit. The cache keeps you well under it in normal use.
* Some phones (some Xiaomi/Realme ROMs) report a partial or no Cell ID for 5G NSA. The app then shows the LTE anchor cell.
* Data licence: OpenCelliD data is CC BY-SA 4.0, and map tiles © OpenStreetMap contributors. The app shows the attribution.
