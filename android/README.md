# TypePilot for Android: full feature guide

<sub>← [Back to the project page](../README.md) for downloads, install steps and screenshots.</sub>

**AI autocomplete, rewrite and memory on top of every app, all day, with any keyboard.**

TypePilot works two ways, and you can use either or both:

| Mode | How it works | Best for |
|---|---|---|
| **Overlay** (accessibility service) | Keep your keyboard (Gboard, SwiftKey, Samsung…). The **✦** button appears the moment a field is focused; suggestions stream in word by word; ✦ opens a small **floating assistant** over the app (the keyboard and the app stay visible). | Everyday use, in any app |
| **TypePilot Keyboard** (input method) | A full keyboard with AI built into its strip: suggestions, **✦ Rewrite**, **✎ Write** and **Fix**. | Phones where accessibility is blocked (Advanced Protection), or if you prefer one keyboard |

## Features

### Knowledge memory: instant answers without AI
- **Every field you fill is remembered as question → answer.** The question comes from the field's label, a floating label, or the text right above it, e.g. "What is your notice period?". Filling the same answer again makes it more certain.
- **Add documents** (PDF, Word `.docx`, text, Markdown) in Memory → Documents:
  - A fast offline pass picks out emails, phones, LinkedIn/GitHub links and "Label: value" lines.
  - Then the AI reads the document and writes key → value facts (name, contacts, work, education, skills, salary, notice period…) and likely question → answer pairs, each with aliases.
- **When a field opens**, a local matcher scores every fact against the question in about a millisecond. It knows 26 common form concepts and their synonyms ("Contact no." = phone, "Organisation" = company), plus word overlap and typo-tolerant trigrams.
- **More than one likely answer? You see them all.** The card lists the best memory answer and up to two more within 25 points of it, each on its own row with a coloured **% pill** beside it (green 85%+, teal 70%+, amber below), never mixed into the answer text. Tap any row to insert that one.
- **Memory + AI, like FormPilot:** for a real question ("Why do you want to join us?"), the memory answers come with an extra **AI** row that writes its own answer while you look. Plain fields (email, phone, company) get memory only. Switch: **Also offer an AI answer**.
- **App-aware memory:** answers and sentences remember which app you typed them in. Something you typed in WhatsApp ranks first in WhatsApp; in other apps it gets less trust (×0.85). Switches: **Prefer answers from the same app** (on) and **Only in the app I typed it** (strict; documents and facts you added still work everywhere).
- **First and last names** are split from your full name.
- **Feedback:** accepting a fact raises its confidence; closing it lowers it.
- **Memory → Facts → "Try a question"** shows exactly what a field would get, with its **match × trust** breakdown.
- **How the % works:** % = match × trust.
  - **Match** is 97% for the same kind of field (synonyms); otherwise it's shared words and letters. Different field kinds (email vs phone) are cut to a third.
  - **Trust** starts at 45% for something typed once and rises 15% each time you type it again. Documents give 60–95% and facts you add give 95%. Using an answer adds 10%; closing it takes 10% away.
- **Memory settings**, in Memory → Facts and in Settings:
  - **Use memory**, **Learn from what I type in forms**, **Learn from my corrections** and **Learn sentences I write often** can each be switched on or off.
  - **Show an answer when it's at least N%:** 30–95%, default 55%.
  - **Offer a typed answer after N times:** 1–5.
  - **Show the % on suggestions.**
- **Backup and restore** (Settings → Apps, memory & privacy → Backup): facts, documents, phrases, shortcuts, stats and settings in one `.zip`. API keys are never included. Restoring checks the file, replaces your data and restarts TypePilot. Use it for a new phone or a reinstall.
- **Corrections:** if you change an answer TypePilot filled in, the old one loses trust and your new one is learned.
- **Form fields stay clean:** name, email, phone and company fields get memory answers only, never AI sentence continuations or reply starters.

### Suggestions while you type
- **Memory first, in microseconds:** phrases you write often, your usual value for a field ("Address", "Email"), and **text shortcuts** (type `;addr` anywhere to expand it). No AI, no network, no battery cost.
- **AI continuation** after a short pause (160 ms by default). Tap to insert all of it, **→** to insert one word, **✕** to close.
- **Reply starters** when a chat box opens, using the message on screen ("Sounds good, see you then!").
- **Type-through:** if you type exactly what's suggested, the rest stays on screen and no new request is made.
- Placed **above or below the field with a clear gap**, never over your text, the keyboard or the app's own popups. It moves when the keyboard opens or changes height.
- **The ✦ is a translucent edge tab**, docked to the side of the screen in your wallpaper's Material You colour. It slides in when a field is focused and fades to about half opacity after 2.5 s, so it feels part of Android rather than a floating extra. Tap it for the assistant, drag it up or down (it snaps to the left or right edge), or hold it to hide TypePilot in that app. Prefer the old round button next to the field? Settings → Suggestions → ✦ style: **Next to the field**. **Reset** puts it back where it started.
- **Only while the keyboard is open:** nothing is looked up or sent to the AI until the keyboard is on screen. A field an app focuses on its own (with the keyboard closed) shows just the ✦; suggestions start when you tap it and the keyboard opens, and disappear when you close the keyboard. No wasted tokens.
- **Always the current question:** only text above the field (or on its border, or to its left) can be its label, never the next field's label below it or a toast. Answers are cached per question, and the label is re-read after the keyboard scrolls the form. The assistant popup also remembers where you drag it (by its ═ handle), with its own Reset.
- **Every field, every time:** re-tapping a field that already has focus, coming back from Recents or the notification shade, or reopening the keyboard all bring the ✦ and suggestions back. Android sends no new focus event in those cases, so TypePilot asks which field has focus.
- **Instant feedback:** the ✦ button shows as soon as you tap a field and pulses while the AI is thinking; the suggestion **streams in word by word**, so the first words appear at about the model's first-token time.

### Rewrite with AI: the floating assistant
Tap ✦ and a compact card (13 sp text, small chips) floats over the app in the larger free area above or below the field; if neither has room, it sits at the top and covers the field (it shows the field's text). Drag it by its header. It never closes the keyboard: it only takes keyboard focus while you type a Write instruction, then hands it back. It closes when you switch apps. The same card opens from the text-selection menu and the share sheet.

Three headline modes, plus tones and formats:

| | |
|---|---|
| **✓ Fix grammar** | Spelling, grammar and punctuation only. Your wording stays. |
| **≋ Grammar + flow** | Better sentence formation, same meaning. |
| **✦ Best version** | Polished, clear and natural, and it fits the app (a chat stays a chat). |
| More | Shorter · Longer · Formal · Friendly · Confident · Simpler · Bullet points · Add emoji · English · हिन्दी · Hinglish · your own instruction |

- **Changed words are highlighted**, with a change count. Undo, retry, copy and share are all there.
- Works on the **selection or the whole field**.

### Write with AI
Describe what you need ("polite reply saying I'll send it Friday"), pick a length, and the draft is inserted into the field. The prompts forbid made-up dates, numbers and `[placeholders]`.

### Native Android integration
- **Text-selection menu** in every app: *Rewrite with TypePilot* and *Fix grammar* (`ACTION_PROCESS_TEXT`). The result replaces the selection.
- **Share to TypePilot** from any app → rewrite → copy.
- **Quick Settings tile:** pause for an hour, or resume.
- **App shortcuts** (long-press the icon): Write with AI, Insights, Memory.
- **Rewrite clipboard** quick action.

### Insights: usage and performance
Everything is measured on the device and kept there for 90 days. You can export it as CSV.

**Usage**
- **Volume:** suggestions shown and accepted, acceptance rate, and what happened to each one (accepted, word by word, typed yourself, closed, ignored).
- **Savings:** words saved, keystrokes saved, and **time saved** (calculated from your measured typing speed).
- **Activity:** daily chart, an hour-of-day heat strip, top apps, where suggestions come from (memory, shortcuts or AI), and which rewrite modes you use.

**Performance**
- **Live charts:** CPU (TypePilot's share of the whole device, and of one core) and memory (PSS), sampled every second while you watch. Threads, CPU time, battery level and temperature.
- **Latency pipeline:** p50/p90/p99 for each stage:
  - field recognition and event delivery;
  - memory lookup;
  - model first token and model total;
  - overlay render and end to end.
- **Speed histogram** and efficiency counters: events skipped instantly, cache hit rate, requests cancelled as stale.
- **24-hour history** from background samples.

**AI**
- Provider table: calls, average time, first token and errors.
- Error breakdown, and the on-device model's load state and load time.

## Models

| Provider | Notes |
|---|---|
| **Gemini** (default) | Free key at [aistudio.google.com](https://aistudio.google.com). Measured on a Dimensity 7200: `gemini-3.5-flash-lite` 0.8–1.1 s to first token, `gemini-3.1-flash-lite` 2.5–3.3 s; 2.x Flash-Lite is retired (404). **Load the models my key can use** lists only valid ones. |
| **OpenAI-compatible** | Presets: **Groq** (fastest, free tier) and OpenRouter. **Find Ollama on Wi-Fi** scans the network (and USB `adb reverse`) and lists each server's models; **Load models** fills the list for any server. Ollama must listen on the network: on a Mac run `launchctl setenv OLLAMA_HOST 0.0.0.0` and restart Ollama. |
| **On-device** (LiteRT-LM) | Private, offline and free. A **model library** with 18 curated models (SmolLM2, Gemma 3 270M/1B, Qwen3 0.6B/1.7B/4B, Granite 4.0, LFM2.5 1.2B/2.6B, MiniCPM5, OLMo 2, Gemma 4 E2B/E4B, Ministral 3, SmolLM3, Hunyuan MT…) plus a **live list** of everything new on Hugging Face (builds for other chips are hidden). See below. |
| **Memory only** | No AI: phrases, shortcuts and field values. Instant and offline. |

### Model library
- **Your phone:** chip, cores and clock speeds, RAM (total and free), GPU (OpenCL) support, free storage, and a speed factor relative to a Dimensity 7200.
- **For every model:**
  - download size and peak RAM;
  - estimated speed (tokens/s) and time to the first word on *your* chip;
  - a quality rating, with published benchmarks where available;
  - what it's good for (autocomplete, rewrite, drafting, translation);
  - a fit badge: **Runs great / Works well / Heavy / Too large**.
- **Developer details:** parameters, quantization, context length, best backend (many INT4 files are GPU specialists), license, file name and model card link.
- **Best for your phone:** separate picks for autocomplete and for rewriting. You can use **different models for each**.
- **Benchmark** runs a real prompt on the phone and shows the measured load time, first token, tokens/s and memory.
- **Run on:** Auto (the backend each model was built for), CPU or GPU. The model unloads after N idle minutes.
- **Import** any `.litertlm` file.

### Where TypePilot works (and the smart switch for payment apps)
Each app is **On** (suggestions and ✦), **Off** (ignored) or **Hidden**. Payment, UPI, banking and trading apps are detected and listed first.

**Hidden** means TypePilot's accessibility service is switched **fully off** while that app is open, so banking and payment apps that refuse to run alongside accessibility apps never see it. A small watcher turns it back on within a few seconds of leaving the app. In "Only in apps I pick" mode, unpicked apps are simply ignored: accessibility stays on, so it can't get stuck off.

**It never stays off.** If the watcher dies while accessibility is switched off (an app update, the phone maker's battery cleanup, a reboot), any of these turns it back on:
- the app-update and boot receivers;
- a backup alarm about every minute while it's off;
- TypePilot's process starting for any reason.

Tested on a vivo phone: after a kill while switched off, it was back on within 15 s.

The smart switch needs two one-time permissions:
- **Usage access** (in Settings).
- **Write secure settings**, granted once from a computer:

```bash
adb shell pm grant com.typepilot.android android.permission.WRITE_SECURE_SETTINGS
```

### Where TypePilot works: modes
**All apps, except…** or **Only selected apps**, in a full-screen picker with app icons, search, Enabled/Off filters, system apps, and turn-all-on/off. Inside TypePilot itself only the "Try it here" field gets suggestions.

Text sent to cloud providers is **redacted first**: card numbers, keys, IBAN, Aadhaar, PAN and SSN are removed.

## Built for all-day use

| Rule | How |
|---|---|
| Do nothing unless needed | Only six event types are subscribed: focus, click, text changed, selection changed, window state and window list. Events from non-text views return immediately (the Insights screen shows how many). |
| Cheap per keystroke | The field is read from the event's own node, with no tree walk. The screen around the field is read **once per focus**, capped at 450 nodes or 90 ms. |
| One request per pause | Debounced. Any keystroke cancels the pending request, which also cancels the HTTP call or local decode. |
| Warm, cheap network | One shared OkHttp client (HTTP/2 + connection pool). The connection is pre-opened on focus, so there's no TLS handshake per suggestion. |
| No background CPU | No wakelocks. The only foreground service is the small app watcher, and it runs only while a **Hidden** app is open. Overlays hide and work stops when the screen turns off or the keyboard closes. |
| Light UI on top of apps | The overlay chip and the keyboard are plain Views drawn on one canvas. Compose is only used inside the app's own screens. |
| Batched stats | Events go to an in-memory queue and are written to SQLite every 5 s in one transaction. CPU and memory are sampled once a minute in the background (and never with the screen off), once a second only while a dashboard is open. |
| Local models don't squat on RAM | Loaded lazily on a single thread and unloaded after N idle minutes. |

Measured on a vivo I2301 (Dimensity 7200, Android 15) with the dashboard open: about **1% of total CPU**.

## Privacy
- **Never reads password fields.** It also skips fields labelled PIN, OTP, CVV, card, Aadhaar, PAN and so on, and any app you exclude (password managers are excluded by default).
- Never learns OTPs, card numbers, keys or ID numbers.
- Memory and stats are stored only on the phone. API keys are encrypted with an Android Keystore key.
- **Anonymous usage reporting** (`telemetry/UsageReporter.kt`, on by default, off switch in Settings → Apps, memory & privacy): feature, screen and setting names with fixed values (on/off, enums, buckets), daily counts (suggestions shown/accepted/dismissed/ignored, typing sessions), AI request outcomes with error category and latency bucket, rewrite results applied vs discarded, setup steps and permission states, memory size buckets, provider *type*, keyboard family, app *category* (chat/email/browser/other, decided on the phone) and crashes plus caught errors (type and stack only) with a random install id kept outside backups. Sent to `POST /v1/app/typepilot` on the author's server, batched every 6 hours. Never text, memory, file names, app or package names, model names, hosts or keys; turning it off deletes what was sent. Events go through `telemetry/Usage.kt`; fixed values come from `telemetry/UsageMapping.kt`.
- The app shows Google Play's required **prominent disclosure** before you enable the accessibility service.

## Setup on the phone
1. Install and **open TypePilot once**. Android won't start the service for an app that has never been opened.
2. **Home → Setup → Turn on TypePilot in Accessibility.** Read the disclosure, then turn on *TypePilot AI suggestions*.
   - If the switch is greyed out, the app was sideloaded and Android 13+ blocks restricted settings. Go to **App info → ⋮ → Allow restricted settings**, then try again.
3. **Keep it running.** Allow unrestricted battery. Some phone makers kill background apps and need one extra switch, which the app shows for your phone:
   - **vivo / iQOO:** Settings → Battery → Background power consumption management → TypePilot → Allow. Also lock TypePilot in Recents.
   - **OPPO / realme / OnePlus:** App battery management → Allow background activity, plus Auto-launch.
   - **Xiaomi / Redmi / POCO:** Battery saver → No restrictions, plus Autostart.
   - **Samsung:** Never sleeping apps.
4. **Connect a model:** Settings → AI (Gemini key, Groq key, or download an on-device model).
5. Optional: enable the **TypePilot Keyboard** in Settings → Keyboards.

## Build

Requirements: JDK 17 and the Android SDK (platform 37 is fetched automatically).

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

```bash
./gradlew :app:assembleRelease      # ~34 MB, R8-shrunk, arm64
```

```bash
./gradlew :app:testDebugUnitTest    # privacy filter, cleaners, memory, word diff, percentiles
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Stack: Kotlin 2.4, AGP 9.4, Gradle 9.8, Jetpack Compose (Material 3), OkHttp 5, LiteRT-LM 0.18. There is no annotation processing: memory and stats use plain SQLite and settings are JSON.

## Code map

```
app/src/main/java/com/typepilot/android/
├── TypePilotApp.kt              process-wide singletons
├── core/                        Settings (StateFlow), Secrets (Keystore AES-GCM), Log (ring buffer)
├── text/                        SensitiveFilter, ContinuationCleaner, DraftCleaner
├── memory/                      MemoryStore (SQLite), PhraseCompleter (instant suggestions, shortcuts)
├── ai/                          TextModel + SSE client, Gemini, OpenAI-compatible, LiteRT-LM manager & downloader
├── engine/                      AssistantEngine (memory → cache → model), Prompts, RewriteMode
├── metrics/                     MetricsStore (batched SQLite), LiveMetrics (latency rings), PerfSampler
├── service/                     Accessibility service, FieldReader, OverlayController, TextApplier
├── ime/                         TypePilot keyboard (canvas KeyboardView + AI strip)
├── rewrite/                     Rewrite sheet (text-selection menu, share, ✦), word diff
├── tile/                        Quick Settings tile
└── ui/                          Compose app: Home, Insights, Write, Memory, Settings
```

## Google Play notes
- The overlay mode uses the Accessibility API for a non-accessibility purpose (`isAccessibilityTool="false"`). Play allows this only with the in-app prominent disclosure (included) and an accessibility declaration in Play Console. Review has been stricter since January 2026.
- When Android **Advanced Protection** is on, accessibility permission is refused for non-accessibility tools. The **TypePilot Keyboard** still works there.
- **Gemini Nano (ML Kit GenAI)** isn't used for the always-on parts: it only runs for the foreground app and returns `BACKGROUND_USE_BLOCKED` from services. The supported device list is also short.

The research behind these choices is in [docs/ANDROID_RESEARCH.md](../docs/ANDROID_RESEARCH.md).
