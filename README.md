# Anu for Android: a private, on-device AI assistant

[![CI](https://github.com/sandeepvijayarao09/Android_ANU/actions/workflows/ci.yml/badge.svg)](https://github.com/sandeepvijayarao09/Android_ANU/actions/workflows/ci.yml)

## In 30 seconds

- **What it is:** an Android assistant that runs Google's Gemma 4 language models on the phone itself. It can read your calendar, contacts, messages and email, use tools on your behalf, and learn facts about you, while keeping that data on the device.
- **Why it matters:** most assistants either know nothing about you or send everything to a server. Anu answers on the phone by default, uses the cloud (Gemini) only with your consent and with as little data as possible, and logs every request that leaves the phone in a ledger you can read.
- **Key result:** Gemma 4 E4B runs on-device through Google's LiteRT-LM runtime (tested on an Android 16 emulator and a Pixel 10 Pro, where the CPU build writes about 6 to 7 tokens per second). A router that picks the lightest model for each request puts 83% of a held-out eval set on the right tier. 547 tests across 86 test files; CI builds both app flavors and runs every unit test on each push.
- **What I did:** designed and built the app end to end: architecture (written up in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)), the agent loop and approval system, the router and its eval, encrypted storage, memory, connectors and the UI.
- **Built with:** Kotlin, Jetpack Compose, LiteRT-LM, EmbeddingGemma (Google's AI Edge RAG library), Room on SQLCipher, Android Keystore with Tink, OkHttp, WorkManager, GitHub Actions.
- **Try it:** `./gradlew assemblePersonalDebug` on JDK 21, install on an arm64 device or emulator running Android 12 or newer, then download a model in Settings, Models (see [Build and run](#build-and-run)).

## What it does

Each item below exists in the code; [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (component status table) records how far each one has been tested.

- **Chat and voice on one page.** Streaming answers labelled with the model that wrote them, on-device speech recognition, spoken replies, and an optional "Hey Anu" wake phrase that works only while the screen is on.
- **Three model tiers and a router.** Base (Gemma 4 E2B) and Pro (Gemma 4 E4B) on the phone, Cloud (Gemini, with your own API key) only after a consent sheet. A router chooses the tier per request and escalates when an answer fails validation.
- **Tools with approvals.** The model can call tools for calendar, contacts, Gmail, messaging-app notifications, SMS (owner build only), phone controls (timers, alarms, flashlight, Do Not Disturb), notes, MCP servers you add, and stores that speak the Universal Commerce Protocol. Anything another person would see, or anything destructive, waits for you to approve the exact content.
- **Memory you can inspect.** Facts are extracted on the device while the phone is idle and charging, each linked to where it was learned. A Memory screen lets you search, correct, delete or add facts. Relevant facts are found by meaning with EmbeddingGemma, a small on-device embedding model.
- **Photos, voice notes and the screen.** Ask about a photo, record a voice note that Gemma transcribes on the phone, or ask "what's this?" over any app. Codes and password fields are masked first, and none of these go to the cloud.
- **Writing in any app.** Select text, choose Anu from the text menu, and get a rewrite (for example more formal) that replaces the selection.
- **Privacy controls.** API keys live in a vault and are never shown to a model; a Privacy report lists every request that left the phone; a prompt lab in Settings lets you try prompts on the on-device models.

## How it works

Twenty-eight Gradle modules, wired together by hand in one dependency graph (`AppGraph` in `app`, no DI framework):

```
 app            Activities, navigation, AppGraph wiring, assistant overlay, "Hey Anu" service
  |
 feature:*      home (Talk page, approval sheets), memory, settings, writing
  |
 core:agent     agent loop, Tool Broker (policy, approvals, taint, audit), chat session
 core:router    picks Base / Pro / Cloud per request, escalation rules
 core:memory    event log, facts, profile, retrieval, memory tools
 core:model     brain and tool contracts shared by everything above
 core:security  secret handles, PII redaction, egress gate
  |
 brain:litertlm   Gemma 4 E2B / E4B via LiteRT-LM, model download and verification
 brain:gemini     Gemini REST client (every request passes the egress gate)
 brain:embedding  EmbeddingGemma for search by meaning
 brain:nano       Gemini Nano spike (device test only, not in the app)
  |
 connector:*    calendar, contacts, gmail, messages, sms, mcp, shopping, phone, notes
  |
 core:data      Room database on SQLCipher (encrypted at rest)
 core:vault     Android Keystore + Tink secret store, host-bound HTTP injection
 core:ui, core:voice   design system; on-device speech recognition and text-to-speech
 eval           JVM harness for the router eval
```

A request flows: surface, then router (choose a tier), then agent runtime, which builds a token-budgeted prompt from the conversation, relevant memories and tool schemas. Tool calls go through the Tool Broker, which checks the schema and policy, asks for approval where required, runs the call and audits it. Content from connectors is treated as untrusted ("taint") so it cannot quietly trigger writes.

Rules the code keeps (see ARCHITECTURE §2 and §4.2): `core:model`, `core:agent` and `core:router` are pure Kotlin with no Android imports, so they are unit-tested on the JVM; brains and connectors never call each other; only `core:vault` can decrypt secrets; personal data leaves the phone only through the egress pipeline, which writes a ledger entry.

Two build flavors: `personal` (the owner's build, includes SMS) and `play` (no SMS module; a Gradle task fails the build if its merged manifest declares any SMS permission).

## Build and run

Requirements:

- JDK 21 (Gradle 9.6, AGP 9.4.1, Kotlin 2.4.20; compileSdk and targetSdk 37, minSdk 31).
- Android SDK with platform 37.
- For running models: an arm64 device or emulator. The models are large (Gemma 4 E2B CPU build 2.6 GB, E4B CPU build 3.7 GB).

Build exactly what CI builds:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew test assemblePersonalDebug assemblePlayDebug
```

Install the debug app (`personal` flavor, package `io.github.sandeepvijayarao09.anu.personal`):

```bash
./gradlew :app:installPersonalDebug
```

Getting a model:

- **In the app:** Settings, Models downloads a model from the ungated `litert-community` Gemma 4 repositories on Hugging Face. Files are pinned by commit and checked against a SHA-256 in `brain/litertlm/src/main/kotlin/.../ModelCatalog.kt`. Emulators use the CPU builds.
- **From a file you already have** (debug builds): `scripts/dev-install-model.sh <adb-serial> gemma-4-E4B-it.litertlm` checks the hash and copies it into the app. Settings, Models, Choose a file does the same on the device.
- **Search by meaning** needs EmbeddingGemma's model and tokenizer files, which come from a gated Hugging Face repository; install each with the same script.
- **Gmail** needs a one-time Google Cloud setup: [docs/GMAIL_SETUP.md](docs/GMAIL_SETUP.md).
- **Gemini** needs your own API key, entered in Settings and stored in the vault.

The `play` build ships no model and is useful for seeing the UI without one.

## Tests

| Kind | Files | `@Test` methods | Where they run |
|---|---|---|---|
| Unit and Robolectric (JVM) | 66 | 507 | CI and locally |
| Device (instrumented) | 20 | 40 | Locally; CI compiles them |
| Total | 86 | 547 | |

Counts are from `src/test` and `src/androidTest` at the commit that added this README.

```bash
# All unit tests (what CI runs, plus both flavors)
./gradlew test assemblePersonalDebug assemblePlayDebug

# Router eval only (dev and held-out splits; fails if accuracy drops below the recorded floor)
./gradlew :eval:test

# Encrypted-database check on a connected device, no model needed
./gradlew :core:data:connectedDebugAndroidTest

# Real-model tests: push a model first; they skip themselves if it is missing
adb push gemma-4-E4B-it.litertlm /data/local/tmp/anu/models/
ANDROID_SERIAL=<serial> ./gradlew :brain:litertlm:connectedDebugAndroidTest
adb logcat -d -s AnuS1
```

Unit tests use a scripted fake model (`ScriptedBrain`) so the agent loop, broker and router are deterministic. Measured evals so far, from ARCHITECTURE §15 and its component table:

- **Router:** 52 of 60 (87%) on the dev split, 33 of 40 (83%) on a held-out split written separately. Runs in CI.
- **Voice notes:** 12 of 12 runs across four made-up notes proposed exactly the right tasks (on the emulator with E4B).
- **Device control:** 24 of 24 with E4B on the emulator.
- **Search by meaning:** 11 of 12 facts found on both the emulator and a Pixel 10 Pro.

All eval data is made up; no real user data is used.

## Status

Working prototype, built by one developer since September 2026. It runs end to end on an Android 16 emulator with Gemma 4 E4B, and parts have been run on a Pixel 10 Pro. It is not on the Play Store, and the `play` flavor exists to keep that path open. Known gaps, from [TODOS.md](TODOS.md) and the component status table in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md):

- **GPU and NPU:** on the Pixel 10 Pro, E4B's GPU build produced garbage output in testing, and the Tensor G5 NPU build does not run with LiteRT-LM 0.17.1, so phones use the CPU builds for now.
- **Not yet tried against live services:** Gmail with a real mailbox, the live Gemini API, real third-party MCP servers and UCP stores, WhatsApp and Telegram notifications.
- **Voice:** real speech and "Hey Anu" wake-ups need a physical phone; the emulator has no English recognition pack.
- **Evals still to build:** memory extraction precision and recall, prompt-injection red-teaming, writing quality. Peak RAM and context limits are still to measure.
- **Planned, not built:** a vault UI with biometric prompt, proactive agents, a learned router, release hardening.
- **Open TODOs:** split `AppGraph` by area; check each claimed action against the specific write that backs it; keep new core code ready for Kotlin Multiplatform.

## Related

- [Anu-iOS](https://github.com/sandeepvijayarao09/Anu-iOS): the iOS sibling, Gemma 4 E4B on-device through LiteRT, written in Swift. Anu for iOS won the Apple Swift Student Challenge 2026.
- Design documents: [docs/VISION.md](docs/VISION.md) (what and why), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how, with a decision log), [DESIGN.md](DESIGN.md) (visual design).
