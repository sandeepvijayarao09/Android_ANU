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
