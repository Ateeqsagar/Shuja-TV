# Shuja TV (Android TV, Xtream Codes, Netflix-style UI)

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo and push this folder to the `main` branch.
2. Open the repo's **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes (about 5 min), download the **Shuja-TV-apk** artifact and unzip it.
4. Sideload `app-release.apk` to your TV (Downloader app, USB stick, or `adb install app-release.apk`).

## Or build locally
Android Studio (Koala+) -> Open this folder -> Build > Build APK(s).

## Remote controls
Player: Up/Down or CH+/CH- change channel (live), Left/Right seek 10s (movies), OK pause / show info, Back exit.

## Parental lock and hide
- Long-press OK (or press Menu) on any channel or movie to open the lock/hide menu: lock or hide the item, or its whole category.
- First lock or hide asks you to create a 4-digit PIN. After that, locking and hiding are one click.
- Locked items show "LOCKED" and ask for the PIN before playing. Hidden items disappear from rows and search.
- Top bar: Unlock (PIN) shows hidden items and lets you unlock or unhide; Lock now re-locks. It also re-locks when the app leaves the screen.
- 5 wrong PINs = 30 second wait.

## EPG
Now/next info loads per visible channel via get_short_epg (6 requests in parallel, only for cards on screen), is cached until the current programme ends, and refreshes itself.
