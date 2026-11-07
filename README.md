# BlindView 1.0: private Instagram story viewer (personal use)

## Screens
- **Home**: a "Hurry Up!" card with stories about to expire (live countdown), then a grid of everyone you follow who has a story. A colored ring means you haven't watched it in BlindView yet. "New" means it was posted in the last hour. Pull down to refresh.
- **Story viewer**: tap right for next, tap left for previous, hold to pause. It moves on to the next person automatically. Buttons: **Save**, **Share** (repost to Instagram, WhatsApp and so on) and **Favorite**. It never tells Instagram you watched.
- **Search**: find any account. Its page shows the HD profile photo (downloadable), "View stories anonymously" and a favorite button.
- **Favorites**: people you starred.
- **Settings**: your account (log out or switch), and **Browse Instagram**, the website mode for downloading posts, reels and highlights with the purple button.
- **Share to BlindView**: from the Instagram app, share any post or reel link to BlindView to download it.

Downloads go to **Pictures/BlindView**.

## Build the APK with GitHub Actions
1. Create a **private** repository on github.com.
2. Upload the contents of this folder, including the hidden `.github` folder.
3. Open the **Actions** tab and wait for "Build APK" to finish (about 5 minutes).
4. Download **BlindView-apk**, unzip it and install `app-debug.apk`. 

## Notes
- Using a second Instagram account is safer, since unofficial tools can get accounts flagged.
- If something stops working after an Instagram update, the API code is in `Ig.kt` and the website-mode code is in `assets/inject.js`.
