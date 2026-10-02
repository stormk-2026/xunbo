# Xunbo (寻播)

[简体中文](README.md) | **English**

Point a phone's camera at a TV and control its set-top box through USB infrared. The project aims to let users provide a goal while the system finds a route and verifies each key press.

Current status: **M1 implementation is complete; physical-device acceptance is pending.** Implemented features include USB infrared single-key debugging, key learning/import, a CameraX viewfinder with four-corner calibration, foreground sessions, and record export. The M0 offline FakeTv feedback loop is retained. OCR, Jev integration, and automated tasks are not yet connected.

## Build and test

Open this directory in Android Studio, select the bundled JBR as the Gradle JDK (21 in the original development environment), and install Android SDK 36. Java/Kotlin bytecode targets Java 17; minSdk is 26.

```bash
./gradlew ktlintCheck lint test assembleDebug
```

If a system JDK is unavailable in the macOS terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew ktlintCheck lint test assembleDebug
```

Android Studio configures `sdk.dir` in the untracked `local.properties` file. The first build downloads dependencies; unit tests do not access the network or call Jev.

APK: `app/build/outputs/apk/debug/app-debug.apk`. JVM feedback-loop test report: `core/testing/build/reports/tests/test/index.html`.

Do not use an emulator. Select a connected physical device in Android Studio and run the app manually. Follow the [M1 acceptance record](docs/verification/M1.md).

## Documentation

- [Documentation index](docs/README.md) and [coding constraints](AGENTS.md)
- [Workflow and harness](docs/HARNESS.md), [M0 task](docs/tasks/M0.md)
- [M0 acceptance record and procedure](docs/verification/M0.md)
- [M1 task](docs/tasks/M1.md), [M1 acceptance record and procedure](docs/verification/M1.md)

The owner has approved work on M1; the manual M0 acceptance conclusion remains for the owner to complete. M2 begins only after M1 passes physical-device acceptance. This is a single-device experiment, not a claim of verified hardware operation or commercial readiness. Linked project documents are currently in Chinese.

## Repository scope

Only source code, build configuration, Room schemas, required Android XML, and project documentation are committed. API keys, signing material, `local.properties`, real infrared code libraries, databases, camera captures, media, test resource files, traces, exports, and build outputs remain local. The Gradle wrapper JAR is retained as the build bootstrap.

Protocol regression tests use synthetic vectors embedded in test source, not real remote-control code tables. Evidence logs and capture references in acceptance documents point to local evidence that is not included in the repository. Commits do not include automated-tool attribution.
