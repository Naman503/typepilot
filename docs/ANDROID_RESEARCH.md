# Android: research and decisions (October 2026)

How to run an AI writing assistant on top of every app, all day, with low latency and little battery use. Each section ends with the decision it led to in [`android/`](../android/).

## 1. Getting on top of every app

| Option | Works with | Can read the field | Can draw near the field | Play Store |
|---|---|---|---|---|
| **Accessibility service + `TYPE_ACCESSIBILITY_OVERLAY`** | Any keyboard | Yes: focused node, text, selection, bounds | Yes, without the "draw over apps" permission | Allowed with a prominent in-app disclosure and a Play Console declaration (`isAccessibilityTool=false`). Review has been stricter since 28 Jan 2026. |
| **Custom keyboard (InputMethodService)** | Itself only | Yes, via `InputConnection` | In its own strip | Fine |
| `SYSTEM_ALERT_WINDOW` overlay | Any keyboard | No (it needs accessibility anyway) | Yes | Discouraged |

**Decision: both.**
- The accessibility overlay is the main experience, because people keep their keyboard.
- The TypePilot Keyboard is the fallback. When **Advanced Protection** is on, Android refuses accessibility permission to non-accessibility tools, but a keyboard still works.

Practical findings from building and testing on Android 15 (vivo) and Android 17 (emulator):

**Event handling**
- **Service binding:** Android won't bind an accessibility service until the app has been opened once after install. A force-stop disables the service again.
- **Window events:** `TYPE_WINDOWS_CHANGED` events have **no package name**. Handle them before any package filter, or keyboard show/hide is never seen.
- **Keyboard position:** read the keyboard's real bounds from `windows` (`TYPE_INPUT_METHOD`, which needs `flagRetrieveInteractiveWindows`), and re-place the overlay when they change. At focus time the keyboard hasn't opened yet.
- **Spurious focus events:** toolkits like Compose and WebView also send `TYPE_VIEW_FOCUSED` for containers while the text field keeps input focus. Check `findFocus(FOCUS_INPUT)` before deciding the user left the field.
- **Compose text fields** in some states don't emit text events to non-tool services. Native `EditText` fields (most apps) always do.

**Reading and writing text**
- **Inserting:** `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` worked in every app tested, including native fields, Compose and the Contacts app. Paste with clipboard restore is the fallback.
- **Empty fields:** `isShowingHintText` tells you the node's text is really the hint, so the field counts as empty.
- **Sideloading:** the Android 13+ "restricted settings" block applies to sideloaded APKs (not adb installs). Users unblock it in **App info → ⋮ → Allow restricted settings**.

## 2. Staying alive all day
- **No foreground service:** an accessibility service doesn't need one, and it keeps running as long as it's enabled.
- **Aggressive phone makers:** OEM battery managers still kill background apps (see [dontkillmyapp.com/vivo](https://dontkillmyapp.com/vivo)). On vivo: Settings → Battery → Background power consumption management → Allow, plus lock the app in Recents. The app detects the manufacturer and shows the right steps.
- **Battery permission:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is requested once from the setup checklist.
- **Zero idle work:**
  - no wakelocks;
  - overlays hidden and jobs cancelled when the screen turns off or the keyboard closes;
  - CPU and memory sampled once a minute only while the screen is on.

## 3. Models: latency vs. privacy

| | Latency (first token) | Quality | Cost | Background use |
|---|---|---|---|---|
| **Gemini 3.5 Flash-Lite** (`thinkingLevel: minimal`) | ~0.3–0.6 s | Good | Free tier | Yes |
| **Groq** (Llama 3.1 8B, OpenAI-compatible) | ~0.15–0.3 s | OK–good | Free tier | Yes |
| **LiteRT-LM on-device** (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`) | Load ~2.5–3.5 s, then prefill-bound | Depends on model | Free | Yes (our own process) |
| **ML Kit GenAI / Gemini Nano** (Prompt, Rewriting, Proofreading) | Fast on supported phones | Good | Free | **No:** only the top foreground app; services get `BACKGROUND_USE_BLOCKED`. Limited device list. |

**LiteRT-LM details**
- **API** (checked with `javap` on the AAR): `Engine(EngineConfig(modelPath, Backend.CPU()/GPU(), cacheDir))`, then `createConversation(ConversationConfig(systemInstruction, samplerConfig, maxOutputToken, thinkingConfig = ThinkingConfig(false)))`, then `sendMessageAsync(text): Flow<Message>`. Cancel with `cancelProcess()`.
- **Native library:** about 22 MB (arm64).
- **Models that download without a Hugging Face login:**
  - `litert-community/Qwen3-0.6B` (345 MB);
  - `litert-community/gemma-4-E2B-it-litert-lm` (2.6 GB).
- **Gated models:** Gemma 3 270M and 1B need a token and license acceptance.
- **Emulator results** (Apple silicon, CPU): Qwen3 0.6B loads in 2.4–3.5 s, and a 300-token prompt takes ~5–7 s end to end. A Dimensity 7200 is several times faster.
- **Quality:** the 0.6B model echoes prompt scaffolding, so it needs a minimal prompt (just the text) and output filters. It also occasionally answers in snake_case, which is now fixed automatically.

**Decisions**
- **Default to Gemini Flash-Lite** with minimal thinking, and offer Groq as a preset.
- **On-device is opt-in:**
  - loaded only when you focus a field, on one thread;
  - unloaded after N idle minutes;
  - given compact prompts.
- **Memory first:** shortcuts, field recall and repeated phrases answer in microseconds without any model.
- **Skip AI in search, address and filter boxes.**
- **No ML Kit GenAI in the always-on path.** It could be added later for the Rewrite sheet, because that is a foreground activity.

### Model library research (October 2026)
All `.litertlm` models from `litert-community` on Hugging Face were checked with the HF API for sizes and gating, plus their model cards for benchmarks.

**Measured speeds from the cards**

| Model | Phone (chip) | Backend | Prefill | Decode | First token |
|---|---|---|---|---|---|
| Qwen3 0.6B | TECNO LJ9 = Tecno Pova 7 Ultra (Dimensity 8350, Mali-G615 MC6) | GPU | 637 tok/s | 33.5 tok/s | 0.43 s |
| Qwen3 0.6B | TECNO LJ9 (Dimensity 8350) | CPU | 231 tok/s | 8.3 tok/s | 1.2 s |
| Qwen3 0.6B | vivo V2502A | GPU | 1056 tok/s | 22 tok/s | – |
| Qwen3 1.7B (wi4b32) | Galaxy S26 | GPU | 40.7 tok/s decode | | 0.4 s |
| Qwen3 1.7B (wi4b32) | Galaxy S26 | CPU | 8 tok/s decode | | 2.8–4.3 s |
| LFM2.5 1.2B int4 | Pixel 8a | GPU | | 21 tok/s | 1.4 s (263-token prompt) |
| Gemma 4 E2B | Galaxy S26 Ultra | GPU | 3,808 tok/s | 52 tok/s | |
| Gemma 4 E2B | Galaxy S26 Ultra | CPU | 557 tok/s | 47 tok/s | |
| Qwen3 4B | vivo V2502A | GPU | | 12.6 tok/s | |
| Qwen3 4B | TECNO LJ9 | GPU | | 9.65 tok/s | |

The Qwen3 1.7B wi4b32 build is 3× faster on GPU than CPU. Gemma 4 E2B's CPU memory footprint is 1.7 GB.

**What it means for an 8 GB phone**
- **GPU specialists:** many INT4 (wi4b32) files are ~3× faster and use 2–3× less memory on the GPU than on the CPU, hence the per-model "Auto" backend.
- **MediaTek GPUs use more memory:** the vivo V2502A measured 1.9 GB for Qwen3 0.6B and 4.7 GB for Qwen3 4B.
- **Mistral's advice:** the Ministral 3B card says 8 GB phones should use CPU only and free RAM first.
- **Quality numbers used:**
  - LFM2.5 GSM8K: 72% (1.2B int4) and 83% (2.6B int4), from the cards;
  - Qwen3 MMLU (base models): 52.8 (0.6B), 62.6 (1.7B), 73.0 (4B);
  - Gemma 3 1B GSM8K 62.8, Gemma 3 270M IFEval 51.2.
- **Estimates for a Dimensity 7200** (2× A715 @ 2.8 GHz, Mali-G610 MC4) use ~60% of the Dimensity 8350 numbers. The in-app benchmark measures the real figure.

## 4. Latency engineering
- **Debounce:** 220 ms by default. A new keystroke cancels the pending coroutine, which cancels the OkHttp call (`callbackFlow` + `awaitClose { call.cancel() }`) or the local decode.
- **One shared OkHttp client** (HTTP/2, pooled), with a `HEAD` request on focus to open TLS early.
- **Cache:** LRU keyed on app, field and text tail. **Type-through** keeps a suggestion alive while you type it.
- **Output caps:** `maxWords × 2 + 4` tokens for continuations, with a `"\n\n"` stop sequence.
- **Measured pipeline (emulator, mock server with 120 ms TTFT):** suggestion visible ~320–400 ms after the pause. Field recognition and overlay render are single-digit milliseconds.

## 5. Measuring our own footprint without root

| Metric | How |
|---|---|
| CPU % | `Process.getElapsedCpuTime()` deltas over wall time, shown per device and per core |
| Memory | `Debug.getPss()` (proportional, more honest than RSS: RSS showed 287 MB vs. a much smaller PSS) |
| Threads | `/proc/self/status` |
| Battery | `BatteryManager` capacity and current (device-wide), plus temperature from the sticky battery intent. Per-app drain isn't available without system permissions, so the app shows its **CPU time** instead. |
| Latency | Per-stage ring buffers (p50/p90/p99) in memory. User-facing events are batched to SQLite every 5 s. |

## 6. Native integration points used
- `ACTION_PROCESS_TEXT`: "Rewrite with TypePilot" and "Fix grammar" in every app's text-selection menu. The result replaces the selection when the field is editable.
- `ACTION_SEND` (share target), a Quick Settings `TileService` (pause/resume) and static app shortcuts.
- Not used: autofill service (it targets forms and credentials, a poor fit), and background clipboard reading (blocked since Android 10; "Rewrite clipboard" runs from the foreground app instead).

## Sources
- [LiteRT-LM Kotlin getting started](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md), [litertlm-android on Maven](https://mvnrepository.com/artifact/com.google.ai.edge.litertlm/litertlm-android)
- [ML Kit GenAI APIs overview](https://developers.google.com/ml-kit/genai) (foreground-only restriction)
- [Play Console: Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16558241) (Accessibility API, prominent disclosure)
- [Android Advanced Protection and AccessibilityService](https://pepelac.news/en/posts/id27710-google-s-new-android-security-feature-restricts-accessibilityservice-api)
- [Gemini 3.1 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite), [What's new in Gemini 3.5](https://ai.google.dev/gemini-api/docs/interactions/whats-new-gemini-3.5) (thinking levels)
- [dontkillmyapp.com: vivo](https://dontkillmyapp.com/vivo)
- litert-community model cards: [Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B), [Qwen3-1.7B](https://huggingface.co/litert-community/Qwen3-1.7B), [LFM2.5-1.2B-Instruct](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct), [gemma-4-E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), [Qwen3-4B-Instruct-2507](https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507), [Ministral-3-3B](https://huggingface.co/litert-community/Ministral-3-3B-Instruct-2512)
- [Tecno Pova 7 Ultra (LJ9): Dimensity 8350](https://www.gsmarena.com/tecno_pova_7_ultra_5g-13976.php)
- Hugging Face `litert-community` model listings (sizes and gating checked via the HF API)
