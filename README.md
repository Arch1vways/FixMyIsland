# FixMyIsland

An LSPosed module for OriginOS that detects and force-enables music players on Origin Island.

## Build

Requires **JDK 17** and **Android SDK 35** (`ANDROID_HOME` set or `sdk.dir=...` in `local.properties`).

```bash
# Using Make (creates dist/*.apk)
make

# Or using Gradle directly
./gradlew :app:assembleDebug
```

The built APK is located at `dist/*.apk`.