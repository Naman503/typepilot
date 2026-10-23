# TypePilot: Feasibility study

*Researched 2026-10-06. Benchmark figures come from public sources, mostly single third-party measurements on high-end devices, so re-measure on your own hardware.*

## The idea

An assistant that runs on top of every app on **Mac, iPhone and Android** and does three things in any text field:

1. **Ghost-text autocomplete and suggestions** while you type, at the cursor.
2. **"Ask AI to write…"**: a small box at the field ("write a short message to IndiGo about a refund I haven't received") whose result is inserted into the field.
3. **Memory**: it learns from what you type manually, keeps what's worth keeping, and offers it instantly the next time (addresses, standard replies, IDs, phrasing).

It runs on free and local models (Apple on-device, Gemma/Qwen, Ollama), with a Gemini free key as an optional cloud fallback.

---

## Verdict at a glance

| Capability | macOS | Android | iOS |
|---|---|---|---|
| Works in every app's text fields | ✅ via the Accessibility API (with per-app quirks) | ✅ via a custom keyboard (IME); ✅+ with an accessibility service on your own phone | ⚠️ only through a **custom keyboard**; not in password/phone fields or apps that block custom keyboards |
| Popup / ghost text **at the cursor** | ✅ native fields reliable; ⚠️ Chrome/Electron/Docs need fallbacks | ✅ in the keyboard's suggestion bar; ✅ floating chip with an accessibility service (sideloaded) | ❌ no overlays. Suggestions live in the keyboard's top bar |
| "Ask AI to write" + insert | ✅ | ✅ | ✅ (panel inside the keyboard) |
| Record what you type → memory | ✅ (field snapshot on blur) | ✅ (through the keyboard) | ✅ only text typed **with this keyboard**; App Store risk (guideline 4.4.1) |
| Local model for ghost text | ✅ 0.5–2B model on llama.cpp/MLX, ~100–250 ms | ✅ Gemma 3 270M/1B on LiteRT-LM GPU, ~0.3–0.8 s | ⚠️ a keyboard extension has ~60 MB RAM: no own model; **Apple Foundation Models** may work (to be verified) |
| Local model for "write X" drafts | ✅ Apple Foundation Models / Qwen3.5 4B | ✅ Gemma 4 E2B/E4B in-process | ✅ Foundation Models; or the main app |
| Distribution | Outside the Mac App Store (Developer ID + notarization) | Play Store possible as a keyboard; the accessibility add-on is sideload-only | App Store possible for the keyboard; memory logging may be rejected, so personal install via Xcode |

**In short:**
- **Mac: yes, close to the full vision.**
- **Android: yes, close to it**, with a keyboard at the core.
- **iPhone: a reduced version**, keyboard-based, with suggestions in the keyboard bar rather than at the cursor.

The idea is proven by shipping products: **Cotypist** and **Typeahead** do system-wide local ghost text on Mac, and **Gboard**, **Samsung Writing Assist** and **SwiftKey** do AI writing from the keyboard on phones.

---

## Platform details

### macOS: build first
**How it works**
- **Read** the focused field with the Accessibility API: the focused element, its value, the selected range (the caret) and the screen bounds for that range.
- **Show** ghost text in a non-activating `NSPanel`, which floats above every app without stealing focus.
- **Insert** with a three-step fallback:
  1. set the selected text through AX (native apps; read it back to confirm);
  2. clipboard paste with ⌘V, then restore the clipboard (browsers, Electron, Google Docs);
  3. simulated keystrokes (terminals and fields that reject paste).
- **Watch typing** with a listen-only event tap (Input Monitoring permission). To accept a suggestion with Tab, the tap must be active.

**What will be flaky, and the plan for each**
- **Chrome, Electron (Slack, Notion, Discord) and Firefox:** their accessibility tree must be switched on (`AXManualAccessibility` / `AXEnhancedUserInterface`, set per app). Caret bounds are sometimes wrong. → Keep a per-app adapter table, and fall back to a small popup near the field.
- **Google Docs** (canvas rendering) → needs Docs' screen-reader mode.
- **VS Code's editor and terminals** → disable by default.
- **Password fields and Secure Keyboard Entry** block the event tap. → Detect them and stay silent, which is what we want anyway.
- **The "ask AI" box needs keyboard focus** → remember the target field and selection first, then restore focus before inserting.

**Distribution:** sandboxed apps can't get the Accessibility permission, so it ships **outside the Mac App Store** as a notarized Developer ID app, like Cotypist and Typeahead.

**Stack:**
- **Language:** Swift + AppKit for the core; SwiftUI for the panels and settings.
- **Ghost text:** an in-process **llama.cpp** or **MLX** with a small *base* model and KV-prefix caching.
- **The "write" box and memory extraction:** **Apple Foundation Models** (free, offline, structured output).
- **Not Electron or Tauri:** every hard part needs native code anyway.

### Android: keyboard core, accessibility optional
**The custom keyboard (IME) is the core.**
- **It works almost everywhere:** apps can't block a third-party keyboard.
- **It reads** the text before and after the cursor.
- **It inserts** with `commitText`.
- **It has its own suggestion strip.**
- **It gets clean privacy signals:** a field's password type and the incognito flag.
- **Starting point:** fork **FUTO Keyboard** or AOSP LatinIME.

**An accessibility service** is an add-on for your own phone. It draws floating chips at the cursor in any app and works alongside any keyboard.
- It must be **sideloaded**: Play policy forbids non-accessibility use.
- Android 13+ needs "Allow restricted settings" switched on.
- It fails on views marked sensitive and on some custom editors.

**Models:**
- **Don't count on Gemini Nano.** ML Kit only allows it when the app is in the foreground, so it's blocked inside a keyboard.
- **Ship your own model on LiteRT-LM** (Gemma 3 270M/1B, Gemma 4 E2B) in a separate process, so the keyboard isn't killed for memory.

### iOS: keyboard only
- **No overlays and no reading other apps.** A custom keyboard is the only way in.
- **What the keyboard sees:** limited context around the cursor (the host app decides how much), and no cursor coordinates. Suggestions go in the keyboard's top bar; the "write X" panel lives inside the keyboard.
- **About 60 MB of memory.** A keyboard extension can't run its own LLM. **Apple Foundation Models** runs out of process and is the only realistic on-device option, but whether it's allowed in keyboards, and whether it's throttled there, is unverified.
- **Fields it can't reach:** password fields, phone pads and apps that block custom keyboards always get the system keyboard.
- **"Allow Full Access"** is needed for network and for a memory store shared with the main app.
- **App Store guideline 4.4.1** limits collecting typed text, so memory logging is risky for the store. Installing it yourself from Xcode is fine.

---

## Speed: making "instant" real

The target, following Gmail Smart Compose, is a suggestion **under 100 ms** after you pause (Google aimed for p90 under 60 ms).

**Layers, fastest first:**
1. **Snippet / phrase memory**, which needs no LLM:
   - a prefix trie plus n-gram lookup over your own past text, ranked by frequency × recency × app/field;
   - **under 10 ms**;
   - this is what makes repetitive info instant.
2. **A small local model** for continuations:
   - KV-prefix cache (re-encode only what's new);
   - n-gram speculative decoding;
   - 8–15 token cap;
   - cancel stale requests on every keystroke;
   - pre-warm.
   - Expected: **~100–250 ms on Apple Silicon** and **~0.3–0.8 s on a flagship Android phone**.
3. **"Write X" drafts**, on Apple Foundation Models or a 2–4B local model: a 150-word draft in ~3–6 s while streaming. Gemini Flash-Lite is an opt-in cloud fallback. It's too slow for ghost text, and its free tier uses your prompts to improve Google's products.

---

## Memory: learning from what you type

- **Capture:** on Mac, snapshot the field's value when you leave it (not raw keystrokes). On phones, take the text committed through the keyboard.
- **Never capture:** passwords and secure fields, incognito or private windows, the keyboard's incognito flag, password managers, banking apps, or apps you exclude.
- **Filter before storing:** card numbers, OTPs, government IDs.
- **Store:**
  - SQLite with FTS5 for phrases;
  - **sqlite-vec** with a small embedding model for "what's my address?";
  - encrypted at rest, with keys in Keychain/Keystore.
- **Curate** with a small local LLM in the background, never while you type. It decides "is this worth remembering?" and extracts facts, Mem0-style (add / update / delete / ignore).
- **You stay in control:** a memory viewer, edit, delete, pause, and a per-app on/off switch.

Prior art: **Rewind** (local, encrypted, app exclusions; shut down in 2025) and **Screenpipe** (open source, local SQLite, PII redaction).

---

## Prior art to learn from

| Project | Platform | Notes |
|---|---|---|
| [Cotypist](https://cotypist.app) | Mac | Local Gemma/Qwen ghost text, Tab to accept; documents its Google Docs, Arc and VS Code limits |
| [Typeahead](https://www.typeahead.ai) | Mac | Ghost text in 30+ apps, phrase learning, bring your own GGUF model |
| [shadowtype](https://github.com/dario-valles/shadowtype) (MIT) | Mac | A clean reference: event tap → AX → llama.cpp → NSPanel; notarized |
| [cotabby](https://github.com/FuJacob/cotabby) (AGPL) | Mac | The most complete: three insertion strategies; Apple FM, llama.cpp and OpenAI-compatible engines |
| [TabType / S1S](https://github.com/m-ghalib/TabType) (MIT) | Mac | MLX with a KV-prefix cache |
| [KeyType](https://github.com/johnbean393/KeyType) | Mac | Open-source Cotypist-style autocomplete |
| [FUTO Keyboard](https://github.com/futo-org/android-keyboard) | Android | Keyboard with an on-device transformer through llama.cpp |
| [Unpackd](https://github.com/ohitsdoh/unpackd) | iOS | A keyboard reportedly using Apple Foundation Models |
| Gboard / Samsung Writing Assist / SwiftKey | Android/iOS | AI rewrite and proofread from the keyboard |

[FormPilot](https://github.com/Naman503/ai-personal-assistant-projects/tree/main/formpilot), the companion browser extension, already covers the in-browser case. Its memory, provider and prompt ideas carry over.

---

## Recommended plan

1. **Phase 1: Mac MVP** (highest value, fully possible)
   - Menu-bar app: AX field reader, ghost-text panel, Tab to accept, the three-step insertion chain.
   - Phrase memory, plus llama.cpp with a small model for completions.
   - A ⌥Space "Ask AI to write" box using Apple Foundation Models, with Ollama or Gemini optional.
   - Per-app adapters for Chrome, Safari, Slack and Notion; terminals and IDEs off.
2. **Phase 2: Mac memory**
   - Field snapshots on blur, background curation, encrypted store, memory viewer.
3. **Phase 3: Android keyboard**
   - FUTO/LatinIME fork, suggestion strip, "write X" panel, LiteRT-LM model, the same memory design.
   - Then the optional sideloaded accessibility overlay for cursor chips.
4. **Phase 4: iOS keyboard**
   - Suggestion bar plus a "write X" panel.
   - A feasibility spike first: does Foundation Models work inside a keyboard extension, and how hard is it throttled?

## Open questions to test early
- **iOS:** Foundation Models inside a keyboard extension (allowed? throttled on battery?).
- **Mac:**
  - caret-bounds accuracy in Chrome, Slack and Notion on your machine;
  - real first-token latency of a 1–2B model on your Mac with a cached prefix;
  - whether Tab interception conflicts with the apps you use most.
- **Android:** LiteRT-LM latency on your phone (which model?), and whether a separate model process stays alive in the background.
