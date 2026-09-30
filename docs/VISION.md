# Anu for Android: Product Vision

Version 0.3 · Draft · 2026-09-26 (scope decisions recorded in ARCHITECTURE §16 and §17)
Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which says how this is built.

## In one sentence

Anu is a personal AI that lives on your phone, and you can talk to it from any app. It understands your email, messages and calendar, and keeps a picture of you that only you can see. It writes, schedules, replies and shops for you, and asks you first before anything leaves your hands.

## Why

Most assistants either know nothing about you or send everything about you to a server. Anu takes a different approach. Small on-device models (Gemma 4 E2B and E4B) are now good enough for most personal tasks. A cloud model (Gemini) is used only when a task needs it, and then only with your consent, with the least data possible, and with a record you can see.

## Who it is for

- **First:** you, on your own phone (a Pixel 8 Pro or newer) and your own accounts. This is the `personal` build.
- **Later:** privacy-conscious professionals who live in email and calendar, through a Play Store build (`play`). Choices made now must not block that.

## The five promises of the experience

1. **Private by default.** Models, memory, profile and vault stay on the phone. Anything sent off the phone is minimized, consented to, and logged in a ledger you can read.
2. **Knows you, and shows its reasoning.** Anu builds a profile from every interaction and every change in your connected accounts, and the profile keeps growing. Each thing it believes about you links to where it learned it. You can edit or delete any of it.
3. **Does the work.** It writes in your voice, triages email, drafts replies, finds meeting times and books them, summarizes chats, and briefs you on your day.
4. **You stay in control.** Anything other people will see needs your approval of the exact content: sending an email, replying to a message, inviting someone, starting a checkout. Private changes can be undone. The AI can use secrets, but it can never see them. Anu never pays for anything: you finish every purchase on the store's own site.
5. **Fast when it can be, smart when it must be.** A router picks the lightest model that can do the job well: E2B, then E4B, then Gemini. It escalates only when needed, and it tells you which model answered.

## What it feels like (v1 stories)

- You open Anu and talk to the orb. It swells as you speak, swirls while it thinks, and answers out loud. Swipe for Memory and Settings.
- In any app, you hold the power button, or say "Hey Anu, what's on my calendar today?" while the screen is on. Anu opens over the app, listens and answers.
- "Find running shoes under $150." Anu searches the stores you added. "Buy the blue ones in size 10." You approve the exact order and its estimated total, then tap **Finish** to review and pay on the store's site.
- "Add 'buy oat milk' to my Notes." Anu uses the notes service you connected, and asks before it adds anything.
- "Reply to Maya, say yes, and suggest Thursday afternoon." Anu reads the thread, checks your calendar against your preferences (it knows you avoid meetings before 10), and drafts the reply in the tone you use with Maya. You approve it, and it sends.
- "What's my day?" A short brief covers meetings with context, emails that need you, and anything you promised to do.
- You select text in any app, tap **Anu → More formal**, and the rewrite replaces your selection.
- Over a long email in any app, you hold the power button and ask "what's this about?". Anu says "Reading Gmail", reads the screen on the phone, and answers. It never looks unless you ask.
- You tap **+** and pick a photo of a sign, or share one into Anu from your gallery, and ask what it says. Gemma reads it on the phone; the photo never goes to the cloud.
- Walking to the car, you tap **+ → Voice note** and say "Call Maya on Thursday morning, and add oat milk to the groceries list." Gemma writes it down on the phone, and Anu proposes the Thursday call as an event for you to approve and lists the oat milk. The recording itself is never kept.
- A group chat has 40 unread messages. Anu summarizes it from the notification and suggests a reply. It sends only when you tap to approve.
- You say "Remember I'm vegetarian," or Anu notices a pattern on its own. Either way it lands in your profile with its source, and you can edit it in the Memory screen.
- "Save my Meta Muse API key." It goes into the vault, locked behind your fingerprint. Anu can later use it to call Meta's service, but no AI model ever sees the key itself.

## What Anu never does

- Sends, invites, deletes or shares without your explicit approval.
- Shows a password, key or token to any AI model, or writes one into a log or into memory.
- Uploads your profile or memory. The cloud receives only the minimum needed for one request, and only when you allow it.
- Sends a photo you attach off the phone, even with your consent. The same goes for a voice note, and for the words written down from it.
- Learns from anything you've excluded, such as a contact, a mail label or a topic.
- Claims to be able to do something before it has been tested.
- Pays for anything, or handles your card or payment details.
- Listens without your say-so. "Hey Anu" is off until you turn it on, works only while the screen is on, and shows a notification while it listens.

## Scope

**In v1:**
- Models: Gemma 4 E2B (Base) and Gemma 4 E4B (Pro) on the phone, plus Gemini (Cloud).
- A router that picks the model based on how complex the task is.
- An on-device agent that uses tools, with approvals.
- Connectors:
  - calendar and contacts on the phone (every synced account);
  - Gmail;
  - messaging apps such as WhatsApp, Google Messages, Telegram and Slack, through their notifications;
  - full SMS history, in the personal build only.
- Phone controls: "set a timer for ten minutes", an alarm, the flashlight, Do Not Disturb or a Settings page, done at once with no model when that's all you ask, and through the model when it's part of something longer.
- Photos: ask about a photo you pick or share into Anu. Gemma reads it on the phone, and it's kept with its message in Anu's encrypted store only while Anu can still use it in the chat; after that, its place says it's no longer kept, and your original stays where it was.
- Voice notes: record one of up to 5 minutes, or share a recording into Anu. Gemma writes it down on the phone, and Anu proposes the events and other things it asks for, each approved. Only its words are kept; a shared recording is only summarized unless you ask for more, and memory never learns from it.
- Voice: a Talk page with an orb that reacts to your voice, Anu as the phone's assistant over any app, and opt-in "Hey Anu" while the screen is on. Speech is recognized on the phone.
- Questions about the screen: over any app, "what's this?" or a tap on "Use this screen" lets Anu read the screen on the phone, with codes and password fields blacked out first. It never reads the screen unless you ask, and the picture is never kept.
- More connectors:
  - services you add as MCP servers, such as notes or tasks;
  - stores that support the Universal Commerce Protocol (UCP): search, and checkouts you approve.
- A living memory and user profile, with a screen to inspect, edit and add to it.
- AI features: writing, email, scheduling, messages, shopping, and a daily brief.
- A prompt lab in Settings, for trying prompts and sampling on the models on the phone, with nothing from your data.
- A vault for the keys and logins Anu uses for you, such as your Gemini key, a Meta Muse API key or email app passwords. It is not a password manager for other apps.

**Not in v1:**
- A server run by Anu, or sync across devices.
- The iOS app. The core code is kept free of Android-only parts so it can be shared later.
- Browsing the web for you, or a general tool for fetching web pages.
- Fine-tuning models on the phone.
- Extra cloud models such as Meta Muse Spark. The design leaves room for them (ARCHITECTURE §5).
- Outlook / Microsoft 365.
- Filling passwords into other apps (autofill).
- Paying inside Anu, such as UCP payment handlers or AP2 mandates.
- Listening for "Hey Anu" while the screen is off.

## How we'll know it works

These are first targets. They'll be reset once Phase 0 has measured real-device performance.

- Most requests (target: 80% or more) are handled on the phone.
- Four promises are backed by automated tests that must never fail:
  - no secret ever reaches a model prompt or a log;
  - nothing goes to other people without approval;
  - every fact in the profile has a source;
  - nothing leaves the phone without a ledger entry.
- The router picks the right model on a separate test set it wasn't tuned on. We also track how often you tap "retry with Pro", which means it under-routed.
- Replies start quickly on a mid-range phone. The target speed is set after Phase 0.

## Where this comes from

This builds on earlier work: Anu-iOS, Swift-Agents, DeviceControlSlm and on-device-ai-games. It keeps what worked and fixes what didn't. ARCHITECTURE §18 lists each lesson and the design choice it led to.
