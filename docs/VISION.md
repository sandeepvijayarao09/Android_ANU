# Anu for Android: Product Vision

Version 0.2 · Draft · 2026-09-25 (scope decisions recorded in ARCHITECTURE §16)
Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which says how this is built.

## In one sentence

Anu is a personal AI that lives on your phone. It understands your email, messages and calendar, and keeps a picture of you that only you can see. It writes, schedules and replies for you, and asks you first before anything leaves your hands.

## Why

Most assistants either know nothing about you or send everything about you to a server. Anu takes a different approach. Small on-device models (Gemma 4 E2B and E4B) are now good enough for most personal tasks. A cloud model (Gemini) is used only when a task needs it, and then only with your consent, with the least data possible, and with a record you can see.

## Who it is for

- **First:** you, on your own phone (a Pixel 8 Pro or newer) and your own accounts. This is the `personal` build.
- **Later:** privacy-conscious professionals who live in email and calendar, through a Play Store build (`play`). Choices made now must not block that.

## The five promises of the experience

1. **Private by default.** Models, memory, profile and vault stay on the phone. Anything sent off the phone is minimized, consented to, and logged in a ledger you can read.
2. **Knows you, and shows its reasoning.** Anu builds a profile from every interaction and every change in your connected accounts, and the profile keeps growing. Each thing it believes about you links to where it learned it. You can edit or delete any of it.
3. **Does the work.** It writes in your voice, triages email, drafts replies, finds meeting times and books them, summarizes chats, and briefs you on your day.
4. **You stay in control.** Anything other people will see needs your approval of the exact content: sending an email, replying to a message, inviting someone. Private changes can be undone. The AI can use secrets, but it can never see them.
5. **Fast when it can be, smart when it must be.** A router picks the lightest model that can do the job well: E2B, then E4B, then Gemini. It escalates only when needed, and it tells you which model answered.

## What it feels like (v1 stories)

- "Reply to Maya, say yes, and suggest Thursday afternoon." Anu reads the thread, checks your calendar against your preferences (it knows you avoid meetings before 10), and drafts the reply in the tone you use with Maya. You approve it, and it sends.
- "What's my day?" A short brief covers meetings with context, emails that need you, and anything you promised to do.
- You select text in any app, tap **Anu → More formal**, and the rewrite replaces your selection.
- A group chat has 40 unread messages. Anu summarizes it from the notification and suggests a reply. It sends only when you tap to approve.
- You say "Remember I'm vegetarian," or Anu notices a pattern on its own. Either way it lands in your profile with its source, and you can edit it in the Memory screen.
- "Save my Meta Muse API key." It goes into the vault, locked behind your fingerprint. Anu can later use it to call Meta's service, but no AI model ever sees the key itself.

## What Anu never does

- Sends, invites, deletes or shares without your explicit approval.
- Shows a password, key or token to any AI model, or writes one into a log or into memory.
- Uploads your profile or memory. The cloud receives only the minimum needed for one request, and only when you allow it.
- Learns from anything you've excluded, such as a contact, a mail label or a topic.
- Claims to be able to do something before it has been tested.

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
- A living memory and user profile, with a screen to inspect and edit it.
- AI features: writing, email, scheduling, messages, and a daily brief.
- A vault for the keys and logins Anu uses for you, such as your Gemini key, a Meta Muse API key or email app passwords. It is not a password manager for other apps.

**Not in v1:**
- A server run by Anu, or sync across devices.
- The iOS app. The core code is kept free of Android-only parts so it can be shared later.
- Browsing the web for you, or a general tool for fetching web pages.
- Fine-tuning models on the phone.
- Extra cloud models such as Meta Muse Spark. The design leaves room for them (ARCHITECTURE §5).
- Outlook / Microsoft 365.
- Filling passwords into other apps (autofill).

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
