# Anu for Android: working agreement

Sources of truth:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how Anu is built.
- [docs/VISION.md](docs/VISION.md): what Anu is and why.

## Before any task

1. Read `docs/ARCHITECTURE.md`. At minimum read §2 (principles and invariants), §4 (modules and dependency rules), and the sections for the modules you will touch.
2. Check that the work fits the architecture. If it doesn't:
   - For a big shift (a new runtime, a new connector, a change to security rules, or any change to an invariant), propose the change first.
   - Otherwise, update the architecture as part of the change.

## Whenever a change alters the design

Update `docs/ARCHITECTURE.md` **in the same change**:
- the affected sections;
- a new row in the decision log (§17);
- the component status table;
- a changelog entry with a version bump (minor for design changes, patch for clarifications).

If the product scope changes, update `docs/VISION.md` too.

Docs describe what exists, not what we hope for. Use these statuses: Planned → In progress → Implemented → Verified (its eval passes).

## Invariants that must never break (ARCHITECTURE §2)

- Secrets never enter model context, logs, memory or crash reports. Code and models use `SecretRef` handles. Only the vault's HTTP injector attaches values, and only for bound hosts.
- `WRITE_EXTERNAL` and `DESTRUCTIVE` tools run only through the Tool Broker, after an approval of the exact payload.
- Personal data leaves the device only through the egress pipeline, and every egress writes a ledger entry.
- Chat templates and tool formats come from the runtime. Never hand-build them.
- Connector content is untrusted data. It taints the run.
- `core:model`, `core:agent` and `core:router` stay pure Kotlin (no `android.*`).

## Build and test

Gradle must run on JDK 21. Homebrew's default JDK (26) is not supported.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test assemblePersonalDebug assemblePlayDebug   # unit tests + both flavors (what CI runs)
```

**Real-model tests (spike S1).**
- Push a model to the device once:

  ```bash
  adb push gemma-4-E4B-it.litertlm /data/local/tmp/anu/models/
  ```

- Target one device with `ANDROID_SERIAL`, or connected tests run on every attached device:

  ```bash
  ANDROID_SERIAL=emulator-5558 ./gradlew :brain:litertlm:connectedDebugAndroidTest
  adb logcat -d -s AnuS1   # metrics
  ```

- Optional runner arguments:
  - `-Pandroid.testInstrumentationRunnerArguments.backend=GPU` (or `GOOGLE_TENSOR`)
  - `...modelPath=/data/local/tmp/anu/models/<file>.litertlm`
- These tests skip themselves when the model is missing.
- The encryption-at-rest check needs no model: `./gradlew :core:data:connectedDebugAndroidTest`.
- Changing an entity? Bump the `AnuDatabase` version, add a migration, and commit the new schema JSON in `core/data/schemas`.
- **Use only devices dedicated to Anu.** The emulators `daykit_ai` and `daykit_shots` belong to another project. Never install on them, push to them, or run tests on them. If the only attached devices aren't Anu's, ask first.
- **Anu's emulator is `anu_api36`:** Android 16, arm64, 10 GB RAM, 32 GB data.
  - Start it with `emulator -avd anu_api36 -no-snapshot`.
  - Its serial changes between runs. Check with `adb -s <serial> emu avd name`.
  - Emulators use the CPU model files (ADR-025).
- **Installing a local model into the app** (debug builds only, no download): `scripts/dev-install-model.sh <serial> <file.litertlm>`. It checks the SHA-256 against `ModelCatalog` first.
- **Test data on `anu_api36`.** Created once with `adb shell content insert`:
  - a local calendar "Personal" (`account_type=LOCAL`);
  - a made-up contact "Maya Chen", `maya.chen@example.com`.
  Only use made-up `example.com` people.
- **Typing into the app from adb:** use `scripts/emu-type.sh <serial> "<text>"`, because fast `input text` loses characters on Gboard. It escapes shell characters such as apostrophes.
- **Messaging on the emulator** (made-up numbers only):
  - Grant notification access to the debug app: `adb shell cmd notification allow_listener io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.connector.messages.AnuNotificationListener`.
  - Receive a text: `adb emu sms send 5551234567 "…"`. Google Messages is the SMS app and posts the notification.
  - Check what was really sent: `adb shell content query --uri content://sms --projection address:body:type` (type 2 is sent).
  - Android 15+ hides one-time-code notifications from Anu, so test code masking through `sms_search`.
- **Memory on the emulator:** memory learns after you leave the app (`adb logcat -s WM-WorkerWrapper` shows `MemoryWorker`), or with "Learn now" in the Memory tab. Plain chat about yourself is learned from; requests and "Remember that…" commands are not (the tools handle those). Resetting the chat database wipes memory too.
- **Context and tokens:** debug builds log each turn's window and token sizes, never content: `adb logcat -s Anu:D` shows `Context: from message X of N, ~T of B tokens, scale S · last turn estimated E, runtime counted C`. The learned ratio is kept in the app's settings (`token_ratio`); clearing app data resets it.
- **Debugging a stuck turn:** the app is debuggable, so `adb forward tcp:8711 jdwp:<pid>` and `jdb` can print the pending approvals. Don't hold a breakpoint on the main thread while touching the screen: the ANR dialog that follows keeps coming back until the app restarts.
- **Resetting the chat without re-copying the model:** `adb shell run-as io.github.sandeepvijayarao09.anu.personal rm -rf no_backup/db`, with the app stopped.
