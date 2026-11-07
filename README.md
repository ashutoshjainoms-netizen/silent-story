# BlindView Android starter

This is an Android Studio / GitHub Actions source scaffold for Samsung Galaxy S23 Ultra.

## Included in this starter
- Blindstory-inspired home, search, favorites, settings and navigation UI
- Story/profile demo cards
- Local photo/video selection using Android's system document picker
- Light/dark appearance toggle
- No password collection or private-story bypass

## Build a test APK without installing Android Studio locally
1. Create a GitHub repository and upload the contents of this folder.
2. Open the repository's Actions tab.
3. Run **Build BlindView APK** (or push to the main branch).
4. Download the `BlindView-debug-apk` artifact from the completed workflow.

The generated APK is a debug/test build, not a signed Play Store release.

## Important capability boundary
This starter does **not** fetch Instagram personal-account stories. Meta's supported APIs do not provide a general-purpose endpoint for anonymously viewing/downloading arbitrary personal or private stories. Live integration requires an eligible Meta app, approved permissions, OAuth configuration, and compliance review. The visible remote profiles are demo placeholders, not live results.

## Build details
Package: `com.arnav.blindview`
Min SDK: 26
Target SDK: 35
Compile SDK: 35
Gradle: 8.9
Java: 17

## Current status
Source scaffold only. The APK has not yet been compiled or device-tested in this environment. The included GitHub Actions workflow provides a cloud build path.
