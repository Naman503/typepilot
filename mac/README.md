# TypePilot for Mac

A menu-bar app that brings AI suggestions to **every text field on your Mac**: Mail, Messages, Notes, Slack, browsers and more.

| | |
|---|---|
| **Suggestions the moment you focus a field** | Click into a reply box and TypePilot suggests how to start, based on what's on screen (the message you're replying to). Fields you've filled before get your usual value instantly. No typing needed. |
| **Inline suggestions while typing** | Pause and the continuation appears right after your cursor. **Tab** (or a click on it) accepts all of it, **⌥→** accepts one word, **Esc** or its **✕** closes it. |
| **Reads the screen** | Uses the visible text near the field (the email or chat thread) so suggestions and drafts fit the conversation. |
| **Ask AI to write…** | Press **⌥ Space** in any field, type what you need ("write a short message to IndiGo about a refund I haven't received"), and press **⏎**. Pick a length (**Short / Medium / Long**), then adjust with one click: **Shorter, Longer, More formal, Friendlier, Fix grammar, Bullet points**. Or type any change ("mention order 4521"). **Undo** goes back a version; **⏎** inserts. With text selected, it rewrites the selection. |
| **Memory and text shortcuts** | It learns what you type when you leave a field or press Return. Phrases you've written before complete **instantly**. Give an entry a shortcut and typing `;addr` anywhere expands it with Tab. |
| **About me** | One paragraph about you and how you write, used in every suggestion and draft. |
| **Free and private** | Apple's on-device model is the default: free, offline, nothing leaves your Mac. Ollama works too, locally. Gemini and any OpenAI-compatible API (Groq, OpenRouter, LM Studio) are optional. |

## Requirements
- macOS 14+. The Apple on-device model needs **macOS 26 + Apple Intelligence** (Apple silicon).
- Xcode 16+ / Swift 6 toolchain to build.

## Build and run

```bash
cd mac
./scripts/build-app.sh
```

This builds a release, signs it with your Apple Development identity, installs it to `~/Applications/TypePilot.app` and launches it. The ✎ icon appears in the menu bar.

**First run: grant Accessibility.** TypePilot opens its Permissions page. Click **Open System Settings**, then turn on **TypePilot** under *Privacy & Security → Accessibility*. It starts working automatically, without a restart.

> Because the app is signed with a stable identity, the permission survives rebuilds. If you ever see it stop working after a rebuild, toggle TypePilot off and on in the Accessibility list.

### Self-test (no UI)
```bash
~/Applications/TypePilot.app/Contents/MacOS/TypePilot --selftest
```
It prints the permission status, each provider's availability, and real suggestion and writing latencies.

Measured on an M4 MacBook (16 GB), with the Apple on-device model:

| | Latency |
|---|---|
| Suggestion from memory / shortcut | < 5 ms |
| AI suggestion while typing (model warm) | ~360 ms |
| AI suggestion on focus (empty reply, using the screen) | ~0.5–0.6 s |
| "Write X" draft | first words ~330 ms, a full short message ~1.4 s |
| "Shorter" revision | ~0.75 s |

The first request after a cold start takes a few seconds while the model loads; the app pre-loads it at launch.

## Using it

| Action | How |
|---|---|
| Accept the suggestion | **Tab** |
| Accept the next word only | **⌥ →** |
| Close the suggestion | **Esc**, its **✕**, click outside the field, or keep typing |
| Close Ask AI | **Esc**, its **✕**, or click anywhere else |
| Ask AI to write / rewrite the selection | **⌥ Space** |
| Expand a text shortcut | type `;name`, then **Tab** |
| Pause / turn off | Menu bar icon → *Pause for 1 hour* / *Suggestions enabled* |
| See and edit what it remembers | Menu bar → *Memory…* |

**Where suggestions appear.** By default they sit **below the field**, left-aligned with it and 10 pt below its bottom edge, or above it only when a dropdown is below. They never appear off to the side. In tall editors like an email body they sit under or over the current line. They never cover what you're typing. TypePilot also watches for dropdowns attached to the field (browser autofill, a site's own suggestion list, the system password AutoFill) and moves out of their way, going past them if both sides are taken. Windows of other apps never count. Suggestions and the Ask box show only on the display and desktop (Space) where the field is: switching desktops closes them, and a field in a window on another desktop gets none. Prefer ghost text right after the cursor? Choose *Settings → General → Where suggestions appear → Next to the cursor*.

> macOS has its own inline predictive text in native apps. If you see two suggestions at once, turn it off: *System Settings → Keyboard → Text Input → Edit → Show inline predictive text*.

Suggestions appear only when the cursor is at the end of a line. On focus, empty fields get an AI "how to start" suggestion only when there's context: a message on screen, or a multi-line field. Search boxes are skipped. Password fields, secure input and excluded apps are skipped. Excluded by default: password managers, terminals, VS Code and Cursor.

## Settings
- **General:**
  - on/off;
  - suggest on focus;
  - read the screen;
  - the delay after typing;
  - suggestion length;
  - empty-field recall;
  - **About me & writing style**;
  - default draft length.
- **AI:** which model does suggestions and which does writing; Ollama server and models; Gemini and OpenAI-compatible keys (stored in the Keychain); **Test** buttons with latency.
- **Memory:** turn learning on or off, choose whether memory is shared with the AI, add entries (with an optional field label and **shortcut**), search, pin, set shortcuts, delete, clear all.
- **Apps:** the per-app exclusion list.
- **Permissions:** status and setup steps.
- **Logs:** a live log with level control. In a terminal, run:
  ```bash
  log stream --predicate 'subsystem == "com.typepilot.app"' --level debug
  ```
- **About.**

## How it works

```
focus a field ─► memory recall now ─► read the screen (AX tree, ≤250 ms) ─► AI "how to start" suggestion
keystroke ─► event tap ─► debounce (350 ms) ─► read focused field (Accessibility API)
                                                   │  app, window, field label, text before caret, caret rect
                                                   ▼
                       AssistantEngine:  memory phrases (µs) ─► cache ─► model (Apple / Ollama / Gemini / API)
                                                   │
                       suggestion pill at the caret (non-activating panel) ─► Tab ─► insert
                                                                               (AX → paste → keystrokes)
leave field / Return ─► field text ─► sensitive filter ─► SQLite memory (on-device)
```

- **Insertion**: AX `selectedText` for native apps (read back to verify), then clipboard paste with the clipboard restored (browsers, Electron), then simulated typing.
- **Chromium and Electron apps** are asked to expose their accessibility tree (`AXEnhancedUserInterface` / `AXManualAccessibility`).
- **Privacy**:
  - secure fields and Secure Keyboard Entry are never read;
  - card numbers (Luhn-checked), OTPs, API keys, IBANs and IDs are never stored, and are redacted before any cloud call;
  - memory lives in `~/Library/Application Support/TypePilot/memory.sqlite` (mode 600);
  - keys are in the Keychain.

## Project layout
```
mac/
├─ Package.swift
├─ Sources/
│  ├─ TypePilotCore/          # testable core (no AppKit)
│  │  ├─ AI/                  # TextModel protocol + Apple, Ollama, Gemini, OpenAI-compatible
│  │  ├─ Engine/              # AssistantEngine, prompts, FieldContext
│  │  ├─ Memory/              # SQLite store, PhraseCompleter
│  │  ├─ Text/                # ContinuationCleaner, DraftCleaner, SensitiveFilter
│  │  └─ Support/             # settings, Keychain, logging
│  └─ TypePilot/              # the app
│     ├─ Accessibility/       # focused-field reader, per-app adapters
│     ├─ Input/               # event tap, text inserter
│     ├─ UI/                  # suggestion pill, Ask panel, settings window
│     └─ App/                 # coordinator, app delegate, self-test
├─ Tests/TypePilotCoreTests/  # 39 unit tests: swift test
├─ Resources/                 # Info.plist, app icon
└─ scripts/                   # build-app.sh, make-icon.mjs
```

## Development
```bash
swift build           # debug build
swift test            # unit tests
./scripts/build-app.sh  # release .app → ~/Applications, launches it
```
`swift run` works for quick checks, but macOS grants Accessibility to the signed `.app`, so use the script for real testing.

## Known limitations
- **Caret position in some web and Electron fields:** sometimes it can't be read exactly. The suggestion then appears below the field instead of at the cursor.
- **Google Docs:** needs its screen-reader support turned on (Tools → Accessibility).
- **Apple's on-device model:** its context window is 4K tokens, so only the last ~1,200 characters before the cursor are sent.
- **Distribution:** the app is distributed outside the Mac App Store, because sandboxed apps can't use the Accessibility API.

Developed by Naman Pathak.
