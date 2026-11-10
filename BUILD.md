# Building NOVA Browser

Requirements: Android Studio Koala (2024.1.1) or newer, JDK 17+ (Studio's bundled JDK is fine), Android SDK Platform 34 + Build-Tools 34, internet access for Gradle. Device/emulator: Android 8.0+ (API 26+); private tabs need Android System WebView 128+.

## Android Studio
1. File > Open > select the `nova` folder (the one containing `settings.gradle.kts`). Accept "Trust project".
2. Let Gradle sync finish. If it asks, install SDK Platform 34. Studio creates `local.properties` itself.
3. Pick a device/emulator (API 26+; use a Google Play image so WebView stays updatable) and press Run.

## Command line
```
export ANDROID_HOME=$HOME/Android/Sdk     # or write sdk.dir=... into local.properties
chmod +x gradlew
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint (abortOnError=true)
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Pure-Kotlin core tests without the SDK: `tools/run-core-tests.sh /path/to/gradle-8.x`.

## If sync fails
- "Could not find info.guardianproject:tor-android / jtorctl": check the version on Maven Central and edit `app/build.gradle.kts`.
- Compile error in `TorEngine.kt` on `TorService.EXTRA_*`: open the AAR's `TorService` class and use its real constant names.
- Lint `RequiresFeature` on Profile APIs: guard with `WebViewFeature.isFeatureSupported(MULTI_PROFILE)` in the same method or add a targeted `@SuppressLint`.
Paste the first error here and I'll fix it.

## VPN backend
Set `VPN_API_BASE` in `app/build.gradle.kts` to your own https control plane (see `backend/README.md`). Without it the VPN screen shows "not configured".

## Release signing (your own key)
```
keytool -genkeypair -v -keystore ~/nova-release.jks -alias nova -keyalg RSA -keysize 4096 -validity 10000
```
Build > Generate Signed Bundle/APK in Studio, or add a `signingConfigs` block reading the path/passwords from `~/.gradle/gradle.properties`. Never commit the keystore or passwords.
