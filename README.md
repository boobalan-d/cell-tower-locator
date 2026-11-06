# Cell Tower Locator (free, open-source)

An Android app that shows **which cell tower your phone is connected to, where that tower is,
how far away it is, and an arrow pointing to it**. There are no ads and no premium plan, and
it uses only free data sources.

## Features
| Feature | How it works |
|---|---|
| Live connected-cell info | Reads 2G/3G/4G/5G: MCC-MNC, TAC/LAC, Cell ID, **eNB + sector** (LTE), PCI, channel, operator. Supports dual SIM. |
| Live signal meter | RSRP/RSSI in dBm, refreshed every 3 s, with a quality label |
| Tower position | Looked up in the free **OpenCelliD** database, showing the accuracy radius and the number of samples |
| Your own tower estimate | Records GPS + signal strength as you move and computes a weighted position. This works **even when OpenCelliD doesn't have the tower**. |
| Direction arrow | Compass, corrected to true north (magnetic declination), points to the tower |
| Distance + bearing | Haversine distance, bearing in degrees and compass point (N/NE/…) |
| Free map | OpenStreetMap showing you, the tower, the accuracy circle and a line between them |
| Directions | Opens walking directions to the tower in Google Maps |
| Neighbour cells | Lists nearby cells by signal strength |
| Quota saver | Each lookup is cached on the phone |

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
  For LTE, `eNB = ECI >> 8` and `sector = ECI & 0xFF`.
* `TowerLookup.kt` calls `https://opencellid.org/cell/get?key&mcc&mnc&lac&cellid&radio&format=json`.
* `SignalEstimator.kt` computes a weighted centroid with weight `10^((dBm − best)/20)`. Stronger readings
  pull the estimate harder, it needs at least 5 points spread over 50 m or more, and LTE sectors of one eNB are pooled.
* `Compass.kt` uses the rotation-vector sensor (falling back to the accelerometer + magnetometer), adds `GeomagneticField`
  declination to get true north, and applies a low-pass filter that handles the 0°/360° wrap-around.
* `GeoMath.kt` contains the haversine distance and the initial great-circle bearing.
* Arrow angle = `bearingToTower − phoneHeading`.

## Limits
* OpenCelliD free keys have a daily request limit. The cache keeps you well under it in normal use.
* Some phones (some Xiaomi/Realme ROMs) report a partial or no Cell ID for 5G NSA. The app then shows the LTE anchor cell.
* Data licence: OpenCelliD data is CC BY-SA 4.0, and map tiles © OpenStreetMap contributors. The app shows the attribution.
