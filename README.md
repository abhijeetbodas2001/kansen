# sukarna

A small, offline native Android ear-training app built with Kotlin and Jetpack Compose / Material 3. Android 8.0 (API 26) or newer.

Developer: Abhijeet Prasad Bodas <abhijeetbodas2001@gmail.com>.

## First milestone

- Scales page: 12 harmonium tonics, White 1–7 and Black 1–5, in chromatic order.
- Each scale has 13 keys from Sa through upper Sa: Sa, komal Re, Re, komal Ga, Ga, Ma, tivra Ma, Pa, komal Dha, Dha, komal Ni, Ni, upper Sa.
- Hold a swara key to hear a continuous locally synthesized tone; release to stop. Replay Sa using the tonic card.
- Responsive grids, system back navigation, dark theme, accessible button labels, and saved scale selection.
- English and Marathi interface with a persistent language switch; every swara button includes Devanagari notation.
- No permissions, backend, accounts, or internet connection required. Tones are synthesized and cached locally, then preloaded for responsive playback.

White 1 = C4, Black 1 = C♯4, White 2 = D4, Black 2 = D♯4, White 3 = E4, White 4 = F4, Black 3 = F♯4, White 5 = G4, Black 4 = G♯4, White 6 = A4, Black 5 = A♯4, White 7 = B4. Every swara is relative to that tonic and may cross into the next Western octave.

Tuning uses 12-tone equal temperament (harmonium-style), A4 = 440 Hz. This milestone is a chromatic swara explorer, not a raga or thaat selector, and does not model raga-specific shrutis.

## Build without Android Studio

Use Java 21 and the Android SDK with platform 37 and Build-Tools 37.0.0. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=/opt/android-sdk`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
./gradlew installDebug
adb shell am start -n in.sukarna.app/.MainActivity
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. For an optimized production build use `./gradlew assembleRelease`; configure your own signing key before distributing a release APK.

The local manual-testing device is named `sukarna_phone` (Android 16). Its system image is stored under `~/Android/Sdk` alongside links to the package-managed SDK tools in `/opt/android-sdk`. Launch it with:

```sh
ANDROID_HOME="$HOME/Android/Sdk" QT_QPA_PLATFORM=xcb emulator -avd sukarna_phone
```
