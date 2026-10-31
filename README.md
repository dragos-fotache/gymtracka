# GymTracka

GymTracka is an Android workout tracker with an exercise catalogue, custom exercises, routines, workout history,
rest timers, and account synchronization. It is a single-module Kotlin application using Jetpack Compose,
Material 3, coroutines, and Room.

## Features

- Browse and filter the bundled catalogue of 899 exercises; create and edit custom exercises.
- Record sets, repetitions, weight in kilograms, notes, and configured rest durations with autosave.
- Run a rest countdown with vibration and a timed wake lock; record actual elapsed rest separately.
- Finish workouts, browse history, perform a finished workout again, and save workouts as routines.
- Create routines and start workouts from their saved exercise and set structure.
- Sign in with Google through the configured service, then reopen and edit an activated account offline.
- Synchronize while the app is in the foreground, after committed edits, or through manual sync.

The default app requires configured account access. An established matching account remains usable offline;
an unconfigured build displays a configuration gate. Background scheduling, sign-out/account switching, cloud export,
and account deletion are listed in [future improvements](docs/future-improvements.md).

## Build and configuration

| Setting | Value |
|---|---|
| Module | `:app` |
| Namespace / application ID | `com.dragosfotache.gymtracka` |
| Minimum Android | API 24 |
| Compile / target SDK | API 37 |
| Runtime Room schema | 14 |
| Gradle wrapper | 9.8.0 |

Use JDK 25 and Android SDK 37 / Build Tools 37.0.0 to match the checked-in CI configuration. Set the SDK location
through Android Studio, `ANDROID_HOME`, or an untracked `local.properties`. The Java source/target level is 11;
core-library desugaring supplies supported Java APIs on older Android versions.

Run commands from this repository root:

```sh
./gradlew :app:assembleDebug --console=plain
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Account access uses two nonsecret Gradle properties:

| Property | Meaning |
|---|---|
| `gymtracka.serviceOrigin` | Trusted HTTPS service origin, with an optional root slash and no other path, query, fragment, user-info, or whitespace |
| `gymtracka.googleServerClientId` | Google Web/server OAuth client ID used as the ID-token audience |

Both default to empty strings. Supply the intended values explicitly:

```sh
./gradlew :app:assembleDebug \
  -Pgymtracka.serviceOrigin=https://gym.example.com \
  -Pgymtracka.googleServerClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com \
  --console=plain
```

The example values are placeholders. The Google Android OAuth registration must match the application ID and installed
APK signing certificate; the service must accept the same server audience. No OAuth client secret belongs in the app.
The app uses normal certificate and hostname verification and refuses cleartext traffic and redirects.

Missing or invalid service origin prevents database, vault, and network access from the default startup screen.
A valid origin with no server client ID still permits matching established-account offline recovery; interactive
sign-in and reauthentication remain unavailable. Configuration is compiled into the APK; there is no endpoint editor.

## Documentation

Start with the [documentation index](docs/README.md) for architecture, sync, testing, and future improvements.
The nested [shared-docs](shared-docs/README.md) repository owns shared product behavior, backend contracts, and catalogue
data. Android documentation describes the checked-in implementation; shared requirements can include features beyond it.

Build inputs are defined in [app/build.gradle.kts](app/build.gradle.kts), dependency versions in
[gradle/libs.versions.toml](gradle/libs.versions.toml), and CI in
[android-tests.yml](.github/workflows/android-tests.yml).
