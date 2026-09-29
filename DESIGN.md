---
# gstack: design-md-format=spec
name: Anu
description: Quiet precision. A calm, content-first assistant with Apple-grade restraint, where color is used only to tell the truth about your data.
colors:
  background: "#F5F5F7"
  background-dark: "#000000"
  surface: "#FFFFFF"
  surface-dark: "#1C1C1E"
  surface-2: "#EDEDF0"
  surface-2-dark: "#2C2C2E"
  separator: "#DCDCE0"
  separator-dark: "#38383A"
  text: "#1D1D1F"
  text-dark: "#F5F5F7"
  text-muted: "#6E6E73"
  text-muted-dark: "#98989D"
  text-faint: "#A1A1A6"
  text-faint-dark: "#636366"
  primary: "#1D1D1F"
  on-primary: "#FFFFFF"
  primary-dark: "#F5F5F7"
  on-primary-dark: "#000000"
  accent: "#1463FF"
  accent-dark: "#4D8DFF"
  local: "#1E9E5A"
  local-dark: "#30D158"
  away: "#D96A00"
  away-dark: "#FF9F0A"
  away-text: "#B35600"
  error: "#E5383B"
  error-dark: "#FF453A"
  error-text: "#C92A2A"
  error-text-dark: "#FF6961"
typography:
  large-title:
    fontFamily: Google Sans Flex
    fontWeight: 700
    fontSize: 34sp
    lineHeight: 41sp
    letterSpacing: -0.025em
  title:
    fontFamily: Google Sans Flex
    fontWeight: 600
    fontSize: 22sp
    lineHeight: 28sp
    letterSpacing: -0.015em
  live-speech:
    fontFamily: Google Sans Flex
    fontWeight: 550
    fontSize: 26sp
    lineHeight: 33sp
    letterSpacing: -0.015em
  headline:
    fontFamily: Google Sans Flex
    fontWeight: 600
    fontSize: 17sp
    lineHeight: 22sp
  body:
    fontFamily: Google Sans Flex
    fontWeight: 400
    fontSize: 16sp
    lineHeight: 23sp
  label:
    fontFamily: Google Sans Flex
    fontWeight: 550
    fontSize: 13sp
    lineHeight: 18sp
  caption:
    fontFamily: Google Sans Flex
    fontWeight: 400
    fontSize: 12sp
    lineHeight: 16sp
  mono:
    fontFamily: JetBrains Mono
    fontWeight: 400
    fontSize: 12sp
    lineHeight: 16sp
    fontFeature: tnum
rounded:
  sm: 10dp
  md: 14dp
  lg: 18dp
  xl: 28dp
  full: 9999dp
spacing:
  xs: 4dp
  sm: 8dp
  md: 12dp
  lg: 16dp
  xl: 20dp
  2xl: 24dp
  3xl: 32dp
  4xl: 48dp
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
    height: 50dp
  button-secondary:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    height: 50dp
  privacy-indicator:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
    rounded: "{rounded.full}"
    height: 30dp
  composer:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.full}"
    height: 52dp
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"
  sheet:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.xl}"
  search-field:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-faint}"
    rounded: "{rounded.sm}"
    height: 38dp
  tab-bar:
    backgroundColor: "{colors.background}"
    textColor: "{colors.text-muted}"
    height: 64dp
---

# Anu

## Overview

**Creative North Star:** quiet precision. Anu looks like it was made with care and has nothing to hide. The interface recedes so the conversation, your data and Anu's actions are what you see, and color is spent only on the truth about where your data is.

**Product context:** Anu is a private personal AI for Android. It runs Gemma models on the phone and uses Gemini only with consent. It reads your calendar, contacts, Gmail, messages and SMS, uses services you add (MCP) and stores (UCP), and acts only after you approve the exact payload. Its peers are Meta Muse, ChatGPT, Gemini, Siri, Perplexity and Alexa+. It is a native Android app built with Jetpack Compose, with Material 3 underneath for behavior and accessibility, but not its look.

**Mode per surface:**
- Talk: Experience (voice) and Operate (typed requests).
- Assistant card: Operate, over any app.
- Approval sheet: Operate. Here you're making a decision, and nothing may distract from it.
- Memory: Read and Operate.
- Settings: Operate.
- Privacy report: Read.

**References** (researched 2026-09-26):
- Meta Muse (Connect 2026): a character-first design with a customizable mascot, criticized as manipulative for a product that holds your data. Anu deliberately has no mascot; the user rejected cartoon characters as childish.
- ChatGPT: moved voice into the chat window with a live transcript.
- Gemini Live (2026): a floating overlay that shrinks when you switch apps.
- Siri in iOS 27: a dark glow and a Messages-style chat.
- Perplexity: a corner-swipe overlay.
- Alexa+: three tabs and stacked activity cards.
- Apple's privacy indicators and Apple Intelligence Report: the model for Anu's privacy dots and Privacy report.

**First five seconds:**
- A pearl sphere resting on a quiet gray page. It's the only object on screen, and it has depth.
- "On device" with a green dot in the corner, before you've asked anything.
- Large, confident type: "Good afternoon".
- One composer, one microphone, and a "+" to add a photo or a voice note. There's nothing to learn.

## Colors

**Strategy: restrained.** Neutrals do almost everything. There's one tint for things you can tap, two privacy signals and an error red. If a screen is only neutrals, nothing needs your attention.

**Light or dark.** Anu is used all day, and often at night in bed, so it follows the system setting. Dark mode is true black (`background-dark`) with elevated `surface-dark` cards, which saves power on OLED screens and keeps the sphere's glass readable.

**Privacy signals.** These are the heart of the system, modeled on Apple's camera and microphone dots:
- **`local` (green): it stayed on this phone.** It appears as a dot beside "On device" in the privacy indicator, in receipts and in connection rows.
- **`away` (orange): something left this phone** (Gemini, Gmail, an MCP server or a store). It appears as a dot, the orange arc on the sphere, the "N sent today" indicator, approval destination lines and every Privacy report entry.
- **Where they're allowed.** Never for decoration, emphasis, brand or illustration. A green or orange pixel always means data location.

**Contrast.**
- Text meets 4.5:1 on its background. `away` (#D96A00) is only about 3.5:1 on white, so it's for dots and arcs, which need 3:1. Orange *text* on light surfaces uses `away-text` (#B35600, about 4.9:1). On dark surfaces, `away-dark` works as both text and dot.
- Red *text* (Delete, a failure) uses `error-text`: #C92A2A is about 5.5:1 on white and 4.7:1 on `surface-2`; #FF6961 is about 4.9:1 on `surface-2-dark`. `error` itself is for fills and icons.
- `text-faint` is only for placeholders and disabled states. Inactive tab labels use `text-muted`.

**Tint.** `accent` blue is for things you can tap: text buttons, links, toggles and focus rings. Filled primary buttons are ink (`primary`), which reads as calm and serious, and it inverts in dark mode.

**No wallpaper colors.** Material You's dynamic color is not used. Anu's colors carry meaning, and a wallpaper must not repaint them.

## Typography

**Google Sans Flex** (OFL, Google Fonts) is the one family for everything. It's a variable font with an optical size axis (`opsz` 6–144), the same approach as Apple's SF Pro, and it's native to Android.
- **Bundled.** It ships as one variable TTF in `res/font`, so the app never fetches fonts at run time.
- **Axes.** Compose sets `opsz` to match each style's size and `wght` to the weights above. In dark mode, `GRAD` rises slightly (about +40) so light text on black doesn't look thin, without changing layout.
- **Tracking.** It tightens at large sizes (-0.025em for large titles) and is neutral at body size.

**JetBrains Mono** (OFL) is only for exact values: sizes, character counts, times and ids in the Privacy report and approval payloads. Model prose is never set in mono.

**Scale.**
- Large title (34) is for page titles and collapses into a 17sp headline on scroll.
- Title (22) is for sheet and card headings.
- Live speech (26) is your words while you talk. Unconfirmed words use `text-faint` and turn to `text` when final.
- Body (16) is for replies.
- Label (13) and caption (12) are for metadata.

Adjacent levels differ by at least 4sp or a clear weight step. Text scales with the system font size up to 200%, and layouts reflow rather than truncate.

## Layout

- **Grid:** 4dp base. Page margins are 20dp (`spacing.xl`), grouped lists are inset 20dp, and rows are at least 50dp. Touch targets are at least 48dp.
- **Pages:** three pages in a bottom tab bar (64dp; icons plus labels): Talk, Memory and Settings. The Privacy report opens from the privacy indicator on any page and from Settings, over the current page, with a back button named for that page.
- **Page bar:** 56dp, with the privacy indicator on the right on every page (Memory's "+" sits beside it). The large title sits below the bar and collapses into it as a centered 17sp headline once it scrolls away; a hairline appears under the bar as soon as anything scrolls beneath it. Pages inside Settings have a back button in `accent`, named for where it goes ("‹ Settings").
- **Talk:**
  - App bar (48dp): the wordmark on the left, the privacy indicator on the right.
  - Empty state: the sphere (168dp), a greeting, and up to two suggestions as cards.
  - Once you talk, the sphere moves into the app bar (26dp) and the thread fills the page.
  - The composer (52dp) is pinned above the tab bar.
- **Thread:**
  - Your turns sit in right-aligned `surface-2` bubbles, at most 78% wide. A photo you attached sits above your words, right-aligned, with `md` corners, at most 240dp wide (TalkBack: "Photo you shared"). One no longer kept keeps its place: a photo glyph and "Photo no longer kept" in `text-muted` on `surface-2`.
  - A voice note sits in your turn above your words, in its own `surface-2` bubble: a small mic glyph and "Voice note · 0:17" (or "Shared recording · 1:02", then "· Stopped early" when it was cut short) in `text-muted` label, then its words in body. While they're being written down they appear in `text-muted` and turn to `text` when done, like live speech; Anu's line says "Writing down your voice note…". One whose words weren't written down says "Not written down". There's no play button: the recording isn't kept. TalkBack reads it as one item.
  - Anu's replies are plain text at full width, with no bubble.
  - Receipts sit under the reply they belong to.
  - A caption after each answer names the model ("On device · Gemma 4 E4B"), with its privacy dot. When the answer's details are saved, the caption is a 48dp button with a chevron that opens About this answer; long-press on the answer opens it too.
  - With thinking on, the answer shows "Thinking…" while the model reasons, then a quiet 48dp line "Thought it through ›" above the answer, in `text-muted` caption. It opens the reasoning in `text-muted` callout on a `surface-2` panel with `md` corners. How long it thought is in About this answer, not here.
  - A phone command done with no model answers with its receipt only ("Timer set for 10 minutes.", then the receipt), captioned "On device · no model needed"; it offers no Retry.
  - Before the first word, an answer shows "…". After 1 s, if the model is loading, being set up, switching or re-reading the chat, that line names it in `text-muted` callout ("Getting Gemma 4 E4B ready…", "Catching up on this chat…"), and TalkBack hears it once. The sphere doesn't animate for it.
  - When the heat or battery guard changed an answer, one `text-muted` caption line under the caption says what changed: "Lighter model while the phone is warm", "Kept short to save battery".
  - Under the last answer, text buttons offer to try again: "Retry with Gemma 4 E4B" under a Base answer. While guarded, and when Gemini can be asked, "Retry on this phone" and "Ask Gemini" (with an `away` dot) side by side.
- **Tablets and foldables:** content is centered with a maximum width of 640dp.

## Elevation & Depth

- **Light:**
  - Cards and grouped lists use a soft shadow with an offset: `0 1dp 2dp` at 4% ink, plus `0 8dp 24dp -12dp` at 18% ink.
  - Sheets use a 28% scrim.
  - Nothing glows. Depth always has an offset.
- **Dark:** no shadows. Elevated surfaces are lighter (`surface-dark`, `surface-2-dark`), a hairline top highlight is allowed, and sheets use a 50% scrim.
- **The assistant card** is the one translucent surface, because it floats over another app:
  - On Android 12+, window blur behind (about 26dp) plus a material tint: `#FAFAFC` at 78% in light mode, `#1E1E20` at 72% in dark.
  - The card is its own window, exactly its size, so the blur covers only what's behind the card. The rest of the screen gets a 12% dim.
  - Its edge is a 0.5dp hairline (black at 6% in light mode, white at 6% in dark), not a shadow: a shadow under a translucent surface shows through it.
  - When blur is off, for example in battery saver, it falls back to the opaque surface color.

## Shapes

- `sm` 10dp: search and text fields.
- `md` 14dp: cards, grouped lists, receipts and buttons.
- `lg` 18dp: summary cards.
- `xl` 28dp: sheets and the assistant card.
- `full`: pills, the privacy indicator, the composer, the mic button and the sphere.

A nested element's radius is the outer radius minus the gap (a 14dp card inside a 28dp sheet with a 14dp inset). Smooth, continuous corners are optional polish, as a custom shape; plain rounded corners are acceptable.

## Components

### The sphere (Anu's presence)
There is no face, mascot or character. The sphere is procedural, not an image: Compose drawing with gradients on Android 12–12L, and an AGSL shader on Android 13+ for refraction and surface ripples.

**Materials**
- **Pearl (light):** a highlight at the upper left, a soft body gradient from `#FCFCFD` to `#CDD0D8`, a faint cool tint at the lower right, an inner shade, and a contact shadow with an offset below.
- **Obsidian glass (dark):** a small, crisp highlight, a rim of light from below, a body gradient from `#3A3B41` to `#060607`, and a 1dp hairline rim.

**Sizes:** 168dp on the empty Talk page, 200dp while listening, 26dp in the app bar, 28dp in the assistant card.

**States.** Every state is driven by a real event, never by a timer that pretends:

| State | Driven by | Behavior |
|---|---|---|
| Idle | nothing happening | rests: still, so an idle screen draws no frames |
| Listening | mic open, RMS level | surface follows your voice (up to 1.045); an inner `accent` light brightens with the level |
| Thinking | tokens streaming | the internal light turns once every 2.4 s; it stops when the tokens stop |
| Speaking | text to speech playing | a gentle pulse while speech plays |
| Leaving the phone | a Privacy Ledger write | one `away` arc sweeps once around the rim as the indicator turns orange |
| Error | a failed turn (not a declined cloud request, nor a tier that fails before a stronger one answers) | greys for about 1.5 s; the message explains what happened |

**Ripples move the surface, not the outline.** The silhouette always stays a circle; the highlight and the inner light ripple with the voice. A moving outline would read as a creature.

**Reduced motion.** When animations are off, the sphere stays still, and the state is announced in text. The error grey switches on and off without fading.

**TalkBack.** The sphere reads as "Anu, listening" and "Anu, thinking", with the action "Talk" or "Stop".

### Privacy indicator
A 30dp pill at the top right of every page, which opens the Privacy report:
- "On device" with a `local` dot.
- "N sent today" with an `away` dot and `away-text` label, once anything has left the phone.

It's always visible and never hidden by scrolling.

### Composer
- **Resting:** a 52dp pill with the placeholder "Ask Anu". The mic button on the right is a 42dp `primary` circle with an `on-primary` glyph. A 48dp "+" at the start, in `text-muted`, opens a sheet titled "Add to your message" with two grouped rows (TalkBack: "Add a photo or a voice note"):
  - **Photo** ("Choose one from your photos") opens the system photo picker.
  - **Voice note** ("Record it, and Anu writes it down on this phone") starts recording. Without a model that can hear it's disabled, with "Needs Gemma 4 on this phone".
- **With a photo:** the picked photo sits above the pill as a 64dp thumbnail with `md` corners and a 48dp Remove button, and the mic becomes Send, so a photo can go on its own.
- **Recording a voice note:** the pill becomes the recording bar: "Cancel" in `text-muted` at the start; in the middle the level bars, the time in mono ("0:12") and "Recording" in `text-muted` callout; at the end Stop, a 42dp `primary` circle (TalkBack: "Stop recording"). The sphere shows Listening, following the voice. No red: red means an error here. At 5 minutes it stops by itself and says so.
- **With a voice note:** a `full` pill above the composer, `surface-2`, with the mic glyph and "Voice note · 0:17", and a 48dp Remove button. The mic becomes Send, so a voice note can go on its own, or with words that say what to do with it. When it stopped early, or couldn't be used, one `text-muted` caption line above the composer says why, with OK.
- **Listening:** the page switches to the listening layout: a large sphere, live speech, "Listening" with a level meter, a 64dp Stop button, "Type instead" and Mute.

### Receipts
One row per action, written by the app from the Tool Broker's record, never by the model:
- a 30dp icon tile in `surface-2`;
- the action in 14sp (for example, `Moved "1:1 with Maya" to Sat 10:00 AM`);
- a 12sp line with a privacy dot and where it happened ("Calendar · on device", "Gmail · you approved");
- a trailing text action ("Undo", "View").

Failed and declined actions say so plainly, in the same row.

### About this answer
A sheet (`xl` corners, grabber, scrim), opened from an answer's caption:
1. **Heading:** "About this answer" in title style.
2. **Where your data went**, first, as a where line: a `local` dot and "Nothing left your phone", or an `away` dot and "1 item left your phone · to notes.example.com", or "Sent to Gemini (Google)".
3. **Who answered**, in `text-muted` label style: "Gemma 4 E4B · GPU, on this phone" or "Gemini · in the cloud". After an escalation, a second line: "Answered after Gemma 4 E2B's answer didn't pass its checks."
4. **Facts it was given** ("Facts you shared" for Gemini), in a detail panel: each fact as a row that opens it in Memory; a deleted or changed one reads "A fact you've since deleted or changed" in `text-muted`, and opens nothing; "Your memory summary" opens Memory.
5. **What it did:** the answer's receipts.
6. **Speed**, in a detail panel: First word and Total from the phone's clock; Reading and Writing in tokens a second on the phone. Estimated rates read "about 13 tokens/s", with the note "Reading and writing speeds are estimates." Gemini answers show only times, and a "View in Privacy report" text button.

### Approval sheet
A modal sheet (`xl` corners, grabber, scrim):
1. **Heading:** a 44dp icon tile for the app or service, and a question in title style: "Send this email?", "Start this checkout?", "Add this note?".
2. **Where it goes**, on its own line:
   - If it leaves: an `away` dot and "Leaves your phone · to your Gmail" (or "to Gemini (Google)", "to notes.example.com", "to shoes.example.com").
   - If it stays: a `local` dot and "Stays on this phone".
3. **The exact payload.** It sits in a `background` container with `md` corners: field labels in `text-muted` (To, Subject, Items, Total) and your words in body style. This is exactly what will be sent, never a paraphrase. Exact values like totals and ids are in mono.
4. **Why it asks:** a 13sp footnote, such as "Other people will see this. Nothing is sent until you tap Send." or "This goes to shoes.example.com, outside your phone."
   - While the model can see content from other people, the footnote names its kind, never the content: "Anu asks because this chat shows an email, which others can write. Check this closely." A sender or subject could pose as Anu.
5. **Extra lines when they apply:**
   - "Anu won't pay. You finish on shoes.example.com."
   - For cloud consent: the redacted text Gemini would actually receive, and the facts from memory it would also see. They sit in a `background` panel under an "Include what Anu knows" switch, on by default, and dim when it's off. What's listed is exactly what goes; sensitive facts are never listed or sent.
6. **Actions:**
   - A full-width `button-primary` named for the verb: Send, Start checkout, Add.
   - Below it, two equal `button-secondary` buttons: Edit and "Don't send" (or "Don't add"). When the tool allows no changes, "Don't send" is full width.
7. **Edit:** the fields the tool allows become text fields (one line, text, or a list with commas), with "Save changes" and Cancel. Saving approves nothing: Anu checks the change and shows the sheet again with exactly what will happen, marked "Edited by you". A change that doesn't pass stays in the form, with the reason in `error-text`.

Declining is always one tap, at the same size as approving. The primary button is never the only large target. Haptics: CONFIRM on approve, REJECT on decline.

### Assistant card (over any app)
It opens from the power button, the corner swipe or "Hey Anu".
- **Layout:** a frosted card, 10dp from the sides and 26dp from the bottom (at least 4dp above a taller navigation bar), with `xl` corners, at most 640dp wide.
- **Content, top to bottom:**
  - the 28dp sphere and "Anu";
  - the live question (20sp, weight 550);
  - when the summon was handed the screen, a "Use this screen" pill (`accent` on its tint), which reads "Will use this screen" once tapped, until the next question;
  - while the screen is being read for an answer, "Reading Messages" in `text-muted` caption, which TalkBack hears once; it shows whenever the screen is used, and never otherwise;
  - the answer (body); when the screen can't be read, the reason in its place ("Anu can't see this screen: the app keeps it private…");
  - a footer with a privacy dot and "On device · reads the screen only when you ask", or "On device · read your screen in Messages" after an answer that did;
  - a 38dp mic button.
- **Behavior:** tapping the card opens the full app. Tapping outside it closes it. The underlying app stays visible and lightly dimmed.
- **In the thread:** a question about the screen shows "Your screen · Messages" in `text-muted` label above its words. The picture is never kept, so there's nothing to show.

### Segmented control
A `full` track in `surface-2`, at least 48dp tall, with a 4dp inset. The selected segment is a `surface` pill with the soft shadow (none in dark mode) and `text` label; the others are `text-muted`. Labels are 13sp and never wrap. TalkBack hears each segment as a radio button. For a choice among two to four peers, such as the lab's template or model.

### Slider row
Inside a grouped list: the setting's name in body and its exact value in mono (`text-muted`) on one line, the slider below. The thumb and the active track are `accent`, the rest of the track `separator`, with no tick marks. TalkBack hears the value as the row shows it ("0.95", "512 tokens").

### Prompt lab
A page inside Settings (‹ Settings), titled "Prompt lab", whose subtitle says what it is and isn't: "Try a prompt on the models on this phone. The lab has no memory, tools or history, and keeps nothing."
1. A segmented control for the template (Free form, Rewrite, Summarize, Code), and under it a second one for its tone, style or language.
2. Instruction and Prompt, multi-line fields with their labels above.
3. Model: a segmented control of the installed models.
4. Sampling: one row with the four values in mono that opens the four slider rows, then Think first with a switch. The footer gives Gemma's defaults.
5. Run, a full-width primary button, disabled until there's a prompt; Stop, secondary, while it runs.
6. Answer: before the first word, what the model is doing ("Getting Gemma 4 E4B ready…"); then the words, selectable, with "Thought it through ›" above them when it reasoned; "Stopped before the end" when stopped; the speed in mono ("First word 1.2 s · reads 64/s · writes 8.4/s") and Copy.
With no model installed, the page is one row, "Download a model to use the lab", which opens Models. The lab is the one place speed sits next to an answer: it's a workspace for trying the models, not the chat.

### Grouped lists (Memory, Settings)
- **Sections:** titles are 13sp weight 600 in `text-muted`. Groups are `card` surfaces with inset 0.5dp separators.
- **Rows:** a label (16sp) with an optional secondary line (13sp `text-muted`), a trailing value in `text-muted`, and a chevron.
- **Toggles:** they use `accent` when on.
- **Connection rows** show a privacy dot and value: "On device" for Calendar, Contacts and Messages; an `away` dot for Gmail, and with a count for Services and Stores, which talk to the outside. A dot can't be heard, so those rows tell TalkBack "reached over the internet".
- **Footers:** a group can end with a 13sp `text-muted` footnote saying what the group does with your data. Explanations go there, not in every row.
- **Action rows** such as "Add a server" have an `accent` title with a plus icon.
- **Model rows** (Settings → Models) give the tier, the build and the real space: "Pro · CPU · needs 5.9 GB" before download, "Pro · CPU · 5.9 GB used" once installed. Both count the weight cache a model keeps after its first run; the footer says why a model needs more than its download.
- **A model's sheet** opens from an installed model's row (a chevron): a 44dp tile and the model's name, a `local` where line "Stays on this phone", then a detail panel: Tier ("Pro · harder requests"), Build ("Runs on the CPU"), Space, Your answers ("first word 1 min 50 s · reads 16/s · writes 2.1/s · from 2 answers", or "No answers yet"), Last test (the same, with how long ago, and "phone was warm" when it was), and Phone (Cool or Warm). TalkBack reads the speeds as words ("reads about 16 tokens a second"). Below: the test's step while it runs, in `text-muted` ("Waiting for the current answer…", "Getting Gemma 4 E4B ready…", "Setting up the GPU. First time only, about a minute.", "Reading…", "Writing…"), "Stopped" with partial numbers, or "Couldn't test: …" in `error-text`; a note that testing sets the chat aside; a full-width primary "Test speed" (or "Stop test" while it runs), and "Delete" as a secondary button in `error-text`, which asks first.
  - A phone with less memory than a model asks for gets a second line: "Made for 12 GB of memory; this phone has 10 GB, so it may be slow". It informs; Download still works. Numbers and their units never split across lines.
  - A download says how long it has left once its speed is known: "Downloading · 42% of 3.7 GB · about 3 min left".
- **From a file** (Settings → Models): one row, "Choose a file", opens the system's file picker. While a file is checked it becomes "Checking and installing" with a percentage and a progress bar. What happened appears as a notice at the top of the page, in plain words: "Installed EmbeddingGemma's tokenizer.", or why a file wasn't kept. The footer says Anu keeps only the exact files it lists.
- **Pages or sheets:** a list opens as a page (Services, Stores, Models, Activity, Vault activity); a single item with several actions opens as a sheet (a server, a store, a model, the Gemini key), with its details in a `background` panel and a destructive action as a secondary button with `error-text`.

### Memory
- **Header:** the large title "Memory", with the subtitle "What Anu knows about you, kept on this phone.", a search field, and a round "+" button (34dp) to add a fact in your own words.
- **Facts:** grouped under short section names (People, Work, Preferences). The fact is the label. The secondary line gives the relationship and where it came from ("Your manager", "Learned from 2 messages"). When the relation is a verb, the label is a sentence instead ("Priya lives in Pune").
  - Your own facts end with "You said" and a chevron.
  - Learned facts end with a Confirm pill (`accent` at 12%).
- **Details:** tapping a fact opens a sheet with "Kept on this phone", its dated source quotes (a chat message can be forgotten), and That's right, Correct and Delete.

### Privacy report
- **Title:** the large title "Privacy report".
- **Summary card** (`lg` corners):
  - a headline with its dot aligned to the first line: "Nothing left your phone today" or "N items left your phone today";
  - the line "It records what it sends and where, never the content.", preceded by "Anu handled N requests on this phone." once each answer's tier is recorded;
  - a seven-day bar row. Only days with egress get `away` bars and a mono count. It shows real counts only, never decoration.
- **Entries** are grouped into Today, This week and Earlier. Each row shows:
  - the destination as its title ("Gemini, Google", "Gmail, your account", or the host);
  - a secondary line with the purpose, the size in mono, what was replaced first ("2 email addresses replaced"), and the approval state ("you approved") once the ledger records it;
  - trailing: the time in mono today, the weekday this week, the date before that.
- **Footer:** replies and invites you approve are sent by their own apps, so the report says they're in Settings → Activity.

### Tab bar
Three tabs with 24dp line icons at stroke 1.7 and 11sp labels. The selected tab is `text`; the others are `text-muted`. Icons: concentric circles (Talk), a notebook (Memory), sliders (Settings).

## Do's and Don'ts

- **Do** use green and orange only for where data is: on this phone, or leaving it.
- **Do** write receipts, approval payloads and Privacy report entries from the system's own records, never from model text.
- **Do** give "Don't send" the same size and weight of place as "Send", one tap away.
- **Do** make every sphere animation follow a real event: mic level, tokens, speech or a ledger write.
- **Do** keep one family (Google Sans Flex), with mono only for exact values.
- **Don't** add a mascot, face or character, or any emotional appeal. No "missed you", no streaks, no celebration for approving.
- **Don't** use glow halos, gradients on text or buttons, purple or indigo palettes, or frosted glass anywhere except the assistant card.
- **Don't** nest cards in cards, or put every setting in its own card: use grouped lists.
- **Don't** let a model's wording claim an action: only a receipt can say something happened.
- **Don't** hide the privacy indicator, including while scrolling, in sheets or during onboarding.

## Motion

- **Approach:** intentional. The interface moves only to explain what changed; the sphere is the only expressive element.
- **Springs:** interface transitions use critically damped springs (`dampingRatio = 1f`, stiffness about 400), so nothing bounces. Sheets rise in about 280 ms and dismiss in about 200 ms.
- **Easing when not a spring:** enter ease-out, exit ease-in, movement ease-in-out.
- **Durations:** micro 80 ms, short 180 ms, medium 300 ms, long 500 ms.
- **The one authored moment:** when something leaves the phone, the sphere's orange arc sweeps once around its rim at the moment the ledger entry is written, and the privacy indicator turns orange. The code that writes to the ledger triggers it, so the screen can never show less than what happened.
- **Haptics:** CONFIRM on approve, REJECT on decline, a light tick on toggles. There are none in the middle of a conversation.
- **Reduced motion:** when the system says to remove animations, transitions become cross-fades and the sphere stays still.

## Decisions Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-09-26 | Initial design system: quiet precision | Created with /design-consultation after researching Meta Muse, ChatGPT, Gemini, Siri, Perplexity and Alexa+. The user asked for "professional Apple standards" |
| 2026-09-26 | No character or mascot. A procedural pearl/obsidian sphere is Anu's presence | The user rejected a cartoon character as childish. Muse's mascot is criticized as manipulative for a data-holding product |
| 2026-09-26 | Color only for privacy: green stays on the phone, orange leaves it | "Trust you can see", the user's memorable thing. Modeled on Apple's privacy indicators |
| 2026-09-26 | Google Sans Flex plus JetBrains Mono, bundled | Optical sizing like SF Pro, native to Android, OFL. No run-time font requests |
| 2026-09-26 | Orange text uses `away-text` (#B35600) on light surfaces | #D96A00 is only about 3.5:1 on white: enough for dots, not for text |
| 2026-09-26 | Red text uses `error-text` (#C92A2A light, #FF6961 dark) | #E5383B is about 4.2:1 on white and #FF453A about 4.1:1 on dark fills, below the 4.5:1 text rule |
| 2026-09-26 | The Privacy report opens over the current page, and shows only what the ledger records | Tapping the indicator answers "what left?" from anywhere. The requests count and approval state wait until they're recorded: real counts only |
| 2026-09-26 | Lists open as pages, single items as sheets; groups carry footers | Keeps Settings to four short groups, as in the approved mockup, with the explanations under each group |
| 2026-09-26 | The cloud consent sheet lists the facts from memory Gemini would see, included by default | Personal answers from either model, with informed consent: the user sees exactly what goes and keeps it on the phone with one tap |
| 2026-09-26 | Edit on the approval sheet comes back checked before anything runs | The user always approves exactly what will happen; saving a change is never approval |
| 2026-09-26 | The sphere's ripples move its surface, never its outline; Error greys it after a failed turn | A moving outline would turn the object into a character. Only a turn that really failed greys it, never a decline |
| 2026-09-26 | The assistant card is its own window with a hairline edge and no shadow | The system blur covers a window's background shape, so only a card-sized window frosts just the card. A shadow under the translucent card showed through and misplaced it on the emulator |
| 2026-09-27 | Model rows show the space a model really takes, with its weight cache | "3.7 GB" hid E4B's 2.2 GB cache, and Delete said it freed less than it did. What a download costs and what Delete frees should be exact |
| 2026-09-27 | The sphere rests when idle; its animations run only in their state and only on screen | The idle breath redrew the whole screen at 120 Hz on the Pixel 10 Pro, 43% of a CPU core, and it followed no real event |
| 2026-09-28 | Models show a memory note, a download's time left, and a From a file row | Following Google's AI Edge Gallery where it helps: its memory warning and download estimate. Installing from a file lets a model downloaded in the browser be installed without a computer, and only an exact catalog file is kept. Speed figures stay out of the chat: they help developers, not the conversation |
| 2026-09-29 | The approval sheet names the kind of outside content the chat shows, never the content | After ADR-070 a later turn can ask because an earlier email is still in view, so "Written after reading someone else's email" was no longer true of this request. Naming the kind explains the question; a sender or subject on the sheet could pose as Anu |
| 2026-09-29 | Every answer can be checked: the caption opens About this answer, which says where your data went first | Trust you can verify beats speed numbers in the chat. Where the data went is the first thing a privacy-first assistant should answer; the model, facts, actions and speed follow. Speed stays out of the thread itself |
| 2026-09-29 | Under a guarded answer, one line says what the guard changed, and Retry offers the phone and Gemini side by side | A lighter or shorter answer with no explanation reads as Anu getting worse. Keeping it private must be a visible choice, not a dead end after declining a consent sheet |
| 2026-09-29 | Each installed model opens a sheet with its speed from your answers and from Test speed | One item with several actions opens as a sheet. Speed stays out of the chat but belongs with the model: your own answers beside a controlled test, and the phone's heat, so numbers are read in context |
| 2026-09-29 | A wait before the first word says what the model is doing | A silent "…" for half a minute reads as broken. Naming the load, the GPU's first setup, a switch or a re-read says why, and that it's working |
| 2026-09-29 | A "+" in the composer adds a photo, and the photo sits in your turn above your words | Asking about a photo is how people use a vision model (the Gallery's Ask Image). The picker shows only what the user chooses; the photo reads as part of what you said, and stays visible so it's clear what the model saw |
| 2026-09-29 | A phone command answers with its receipt, captioned "no model needed" | The receipt is the truth of what happened; words from a model would add nothing. Saying no model was needed explains why it was instant |
| 2026-09-29 | Thinking shows as "Thought it through", collapsed above the answer; its seconds are in About this answer | The reasoning is there for anyone who wants to check it, without crowding the answer. A time in the thread would be a speed figure, which stays out of the chat |
| 2026-09-29 | "+" opens a small sheet with Photo and Voice note. A voice note records in the composer's place, then waits above it like a photo; in your turn it shows its words, with no player | Both are things you add to a message, so they share one door. Recording inline keeps the thread in view, and Stop before Send lets words go with the note. The words are what the model read, so they're what you see; the recording isn't kept, so nothing pretends to play it |
| 2026-09-29 | The prompt lab is a Settings page built from grouped lists, with a segmented control and slider rows, and shows speed next to its answer | It's a tool for trying the models, so it belongs with them in Settings, not in Talk. The segmented control and slider rows are the plainest way to pick among a few peers and to set a number exactly; both use the existing tokens. Speed is what people try the lab for, so here it sits by the answer, in mono, unlike the chat |
| 2026-09-29 | The assistant card reads the screen only when asked, says "Reading <app>" while it does, and its footer says so | Pointing words or one tap are how people ask about what's in front of them, and a visible line keeps it from ever happening silently. The old footer, "not reading your screen", was true of v1; the new one keeps the promise that matters: never unless you ask |
