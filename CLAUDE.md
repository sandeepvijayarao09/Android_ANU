# Anu for Android: working agreement

Sources of truth:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how Anu is built.
- [docs/VISION.md](docs/VISION.md): what Anu is and why.
- [DESIGN.md](DESIGN.md): how Anu looks and moves.

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
  - Pictures (ADR-082): a photo the user attaches may show a code or password to the on-device model, but answers about it are masked before they're shown or saved. A screen's codes are masked before any model sees it.
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
  - `...speculative=on` (or `off`) for the brain and extraction tests' engines; unset keeps the runtime's default (ADR-069).
  - `S11PhotoTest#takesFourPicturesAtOnceAndCountsAcrossTurns` and `#fivePicturesInOnePrefill` (ADR-094) need `visionBackend=CPU` on the emulator.
  - `...activation=F16` (or `F32`) for `S1BenchmarkTest`'s engine; unset keeps the backend's default, 32-bit on the CPU (ADR-096).
  - `S1BenchmarkTest#measuresSpeculativeDecoding` compares the runtime's default, off and on; `...modes=on+off` picks the runs and their order.
- Run one test method per command: Gradle joins runner arguments with commas, so a comma inside `class=` splits it.
- **Photos (spike S11):** `ANDROID_SERIAL=emulator-5554 ./gradlew :brain:litertlm:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.sandeepvijayarao09.anu.brain.litertlm.S11PhotoTest -Pandroid.testInstrumentationRunnerArguments.visionBackend=CPU`. The emulator needs `visionBackend=CPU`; the default is the GPU.
- **"Reply to this" (ADR-092):** draw the made-up screens with `java -Djava.awt.headless=true scripts/MadeUpScreens.java <dir>`, `adb push` `reply-chat.jpg` and `reply-settings.jpg` to `/data/local/tmp/anu/photos/`, then run `ReplyDraftEvalTest` with `visionBackend=CPU` on the emulator and read the `reply_eval` lines of `adb logcat -d -s AnuS1`. Google Messages on `anu_api36` needs its first-run setup, so Send… on a real chat waits for a phone.
- **PDFs (spike S13, ADR-095):** `ANDROID_SERIAL=<serial> ./gradlew :brain:litertlm:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.sandeepvijayarao09.anu.brain.litertlm.DocumentReadEvalTest`, then read the `document_eval` lines of `adb logcat -d -s AnuS1`. It needs no PDF file: the made-up lease's pages are text. In the app, `adb push` a PDF to `/sdcard/Download/` and choose it with "+" → PDF.
- **Gemini Nano (spike S10):** `ANDROID_SERIAL=<serial> ./gradlew :brain:nano:connectedDebugAndroidTest`, then `adb logcat -d -s AnuS10`. It never starts a model download. On the emulator it reports no AICore and skips. Ask before running it on the phone.
- If Gradle says "0 of which were compatible" for a device that's plainly connected, run it again: on a busy Mac, it times out reading the device.
- AGP saves the device's whole logcat for each test under `build/outputs/androidTest-results/`. On the phone that includes other apps' logs, so read only the `AnuS1` or `AnuS3` lines from it.
- These tests skip themselves when the model is missing.
- The encryption-at-rest check needs no model: `./gradlew :core:data:connectedDebugAndroidTest`.
- Changing an entity? Bump the `AnuDatabase` version, add a migration, and commit the new schema JSON in `core/data/schemas`.
- **Use only devices dedicated to Anu.** The emulators `daykit_ai` and `daykit_shots` belong to another project. Never install on them, push to them, or run tests on them. If the only attached devices aren't Anu's, ask first.
- **Anu's emulator is `anu_api36`:** Android 16, arm64, 10 GB RAM, 32 GB data.
  - Start it with `emulator -avd anu_api36 -no-snapshot`.
  - Its serial changes between runs. Check with `adb -s <serial> emu avd name`.
  - Emulators use the CPU model files (ADR-025), and so do phones until their chip's GPU passes S1 (ADR-059).
- **The reference phone is a Pixel 10 Pro** (Tensor G5, Android 17, arm64); find its serial with `adb devices -l`. Spike S3's model files are in its `/data/local/tmp/anu/models/`, so `ANDROID_SERIAL=<serial> ./gradlew :brain:embedding:connectedDebugAndroidTest` runs there as is. Ask before installing the app or large models on it, and that includes copying a model into its `/data/local/tmp` for a test, even from Anu's own storage there. Before a heavy test, check that it's idle: `adb shell dumpsys power | grep lastUserActivityTime`. The calendar, contacts and messages device tests write into real data, so run them on `anu_api36` only.
- **Installing a local model into the app** (debug builds only, no download): `scripts/dev-install-model.sh <serial> <file.litertlm>`. It checks the SHA-256 against `ModelCatalog` first.
- **Search by meaning (ADR-055)** needs EmbeddingGemma's two files, which only the owner can download (the Hugging Face repo is gated). Install each with the same script: `scripts/dev-install-model.sh <serial> ~/Downloads/embeddinggemma-300M_seq256_mixed-precision.tflite`, then `… ~/Downloads/sentencepiece.model` (ADR-062 moved from the 512-token file). Settings → Models shows it as installed; facts get their vectors when the app starts, after a change to memory, or when you leave the app.
  - On the device itself, Settings → Models → Choose a file installs either file from its storage, such as its Downloads folder (ADR-067). The name doesn't matter; the bytes must match the catalog. To try it on the emulator, `adb push` a file to `/sdcard/Download/`.
  - Its eval (spike S3) needs the files in `/data/local/tmp/anu/models/` too: `adb push` both, then `ANDROID_SERIAL=<serial> ./gradlew :brain:embedding:connectedDebugAndroidTest` and `adb logcat -d -s AnuS3`. It skips itself without them.
  - Keep the emulator's window in front while timing: behind other windows the Mac runs it about 4× slower.
  - In the app, `adb logcat -s Anu:D` shows `Relevant facts: N in X ms, search by meaning on, I of M facts indexed` for each message.
- **Test data on `anu_api36`.** Created once with `scripts/emu-test-data.sh <serial>` (`adb shell content insert`; running it again adds nothing, and it refuses any other emulator):
  - a local calendar "Personal" (`account_type=LOCAL`);
  - a made-up contact "Maya Chen", `maya.chen@example.com`.
  Only use made-up `example.com` people.
- **Typing into the app from adb:** use `scripts/emu-type.sh <serial> "<text>"`, because fast `input text` loses characters on Gboard. It escapes shell characters such as apostrophes.
- **Messaging on the emulator** (made-up numbers only):
  - Grant notification access to the debug app: `adb shell cmd notification allow_listener io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.connector.messages.AnuNotificationListener`.
  - Receive a text: `adb emu sms send 5551234567 "…"`. Google Messages is the SMS app and posts the notification.
  - Check what was really sent: `adb shell content query --uri content://sms --projection address:body:type` (type 2 is sent).
  - Android 15+ hides one-time-code notifications from Anu, so test code masking through `sms_search`.
- **Memory on the emulator:** memory learns only when the phone is idle and charging (ADR-064), or with "Learn now" in the Memory tab. To run the queued job now, find its id with `adb shell dumpsys jobscheduler io.github.sandeepvijayarao09.anu.personal` (Trace tag: MemoryWorker), then `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler io.github.sandeepvijayarao09.anu.personal <id>`; `adb logcat -s WM-WorkerWrapper` shows `MemoryWorker`. Plain chat about yourself is learned from; requests and "Remember that…" commands are not (the tools handle those). Resetting the chat database wipes memory too.
  - A deleted or corrected fact is never learned again from chat, but typing it in Memory → Add brings it back (ADR-057).
  - To see what the model extracts, run `ANDROID_SERIAL=<serial> ./gradlew :brain:litertlm:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.sandeepvijayarao09.anu.brain.litertlm.MemoryExtractionTest`, then `adb logcat -d -s AnuS1`. It needs the E4B file in `/data/local/tmp/anu/models/`.
- **Writing on the emulator:** the real path is long-press text in any app's text field, then ⋮, then "Anu". The system Settings search box works without any account. To open the sheet directly: `adb shell "am start -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.WritingActivity -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT 'some text'"`. Don't sign into or set up other apps (such as Google Messages' first-run screen) to test it.
- **Context and tokens:** debug builds log each turn's window and token sizes, never content: `adb logcat -s Anu:D` shows `Context: from message X of N, ~T of B tokens, F relevant facts, scale S · last turn estimated E, runtime counted C`. F is how many facts from memory went with the message (ADR-054). The learned ratio is kept in the app's settings (`token_ratio`); clearing app data resets it.
  - Each on-device turn also logs its speed from the runtime's own counters (ADR-068): `Speed: Pro on CPU read R tokens at … tok/s, first token after … ms, wrote W at … tok/s, conversation kept`. The end says whether the turn reused a cached conversation: `kept`, `new`, or `rebuilt (system, tools, sampling, thinking, history)` naming what differed (ADR-097).
- **Debugging a stuck turn:** the app is debuggable, so `adb forward tcp:8711 jdwp:<pid>` and `jdb` can print the pending approvals. Don't hold a breakpoint on the main thread while touching the screen: the ANR dialog that follows keeps coming back until the app restarts.
- **Resetting the chat without re-copying the model:** `adb shell run-as io.github.sandeepvijayarao09.anu.personal rm -rf no_backup/db`, with the app stopped.
- **Anu as the assistant on `anu_api36`** (development shortcut, Anu's emulator only): `adb shell cmd role add-role-holder android.app.role.ASSISTANT io.github.sandeepvijayarao09.anu.personal`. Open it with `adb shell input keyevent KEYCODE_ASSIST`; `cmd voiceinteraction show` needs a system permission.
- **Voice can't be tested on the emulator.** Its on-device recognizer has no English language pack (error 13), so listening and "Hey Anu" end with that notice. Use the reference phone. Don't start the pack download without asking.
- **MCP and UCP test servers** (debug builds only, made-up data):
  - `python3 scripts/dev-mock-mcp.py` serves Notes on port 8765; add `--legacy` for a 2025 server, and `--port <n>` to run both.
  - `python3 scripts/dev-mock-store.py` serves a shoe store on port 8766.
  - In the app: Settings → Services → Add a server, `http://10.0.2.2:8765/mcp`; Settings → Stores → Add a store, `http://10.0.2.2:8766`. Each prints what Anu sent. A server that was added while its mock wasn't running shows the error in red; its sheet has Try again.
- **Typing through adb** sends key events, which takes Android out of touch mode, as a hardware keyboard does. Keyboard focus then moves when dialogs close.
- **Seeing an empty Talk page without wiping your chat:** install the `play` build too (`assemblePlayDebug`). It's a separate app with its own data.
- **Dark mode on the emulator:** `adb shell cmd uimode night yes` (and `no` to switch back).
- **Edit on the approval sheet:** with the mock MCP server running, ask "Add a note to Notes: …". The sheet's Edit shows the note's text; after Save changes, the sheet comes back checked, and the mock prints exactly what it received once you tap Send.
- **The approval sheet as the keyboard hides (ADR-086):** `ANDROID_SERIAL=<serial> ./gradlew :feature:home:connectedDebugAndroidTest`, then `adb logcat -d -s AnuSheetIme` for each round. It opens the sheet 8 times as the keyboard hides, with 4 busy threads and a spinning animation; the runner arguments `rounds`, `burners`, `spin=off` and `hide=off` change that. Its activity takes the screen for about 2 minutes.
- **The sphere** is an AGSL shader on Android 13+, and the emulator runs it. Its listening ripples need a real voice, so they can't be seen there. To see the Error state, send a message in the `play` build, which has no model; then reset its chat (`adb shell run-as io.github.sandeepvijayarao09.anu rm -rf no_backup/db`, with the app stopped).
- **The assistant card's blur** works on `anu_api36`, which supports cross-window blur. Debug builds log which look the card got: `adb logcat -s Anu:D` shows `Assistant card: frosted`, or `opaque` when the system has blurs off. The home screen's wallpaper and icons show the blur best.
- **The Privacy report** opens from the privacy indicator at the top right of any page, or Settings → Privacy report. It lists the Privacy Ledger, so it has entries only after something left the phone (for example a mock MCP or store request).
- **Links that open the browser:** Chrome's first-run screen asks to accept its terms. Don't accept it; check the link with `adb logcat -d | grep 'START u0.*VIEW'`.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec

## Design System
Always read DESIGN.md before making any visual or UI decisions.
All font choices, colors, spacing, and aesthetic direction are defined there.
Do not deviate without explicit user approval.
In QA mode, flag any code that doesn't match DESIGN.md.
