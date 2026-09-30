# TODOS

Work deferred by a decision, with the context to pick it up. Planned work that's already designed lives in
docs/ARCHITECTURE.md (component status, §16 spikes). These came from the review of the plan to beat Google AI Edge
Gallery (`/plan-ceo-review`, 2026-09-28).

## Router

### Router eval set before routing changes

**Done 2026-09-29:** the `eval` module and `RouterEvalTest` (ARCHITECTURE §15). Kept here for its context.

**What:** Build the router eval set that ARCHITECTURE §15 calls for: made-up requests labelled with the right tier,
a held-out split, and tier accuracy with under- and over-routing.

**Why:** The plan changes routing twice, sending hot or low-battery requests to Base (EXP-4, BATTERY-1) and adding a
Direct tier for phone commands (G6). Unit tests check each rule; only an eval shows whether answers get worse when
requests move.

**Context:** §15 lists the router eval; component status says Router v1 isn't Verified because no eval set exists.
It needs the `eval` module that §4.1 lists but that hasn't been created yet; S3EmbeddingTest's made-up messages are a style guide for the requests. Decision TODO-2, D13.

**Effort:** human M / CC S
**Priority:** P1
**Depends on:** None; due before stage 1's heat and battery guard (EXP-4).

## App

### Split AppGraph by area

**What:** Split `app/src/main/kotlin/io/github/sandeepvijayarao09/anu/AppGraph.kt` into per-area graphs: models
and brains, memory, connectors, voice, settings.

**Why:** It's 461 lines, changed 14 times in 30 days, and every parity item adds wiring to it.

**Context:** ADR-023 chose one hand-wired graph and said to reconsider once it outgrows one file. Keep the manual
style; split by area first, and reconsider Hilt only if that isn't enough. Decision TODO-1, D12.

**Effort:** human M / CC S
**Priority:** P2
**Depends on:** None; best before stage 1.

## Agent

### Check each claim against the write that backs it

**What:** Back each kind of claim only by its own kind of write: "added to your list" by a list tool's write, "I've
scheduled" by a calendar write, and so on. Today any write in the run backs every claim.

**Why:** The voice note eval (ADR-085) saw "Your list has been updated with: oat milk" beside a real calendar event.
The event's write let the false claim about a list through, because Anu has no lists. The voice note's framing now
asks for list items at the end of the answer, which fixed it in the eval, but only a check can be proven.

**Context:** `ActionClaims` in `core:agent` (ARCHITECTURE §7.4, ADR-027). Branch `claude/admiring-aryabhata-345091`
gives claims kinds, and backs memory claims only with `memory_remember`; a claim about a list could be a kind that
no built-in tool backs.

**Effort:** human S / CC S
**Priority:** P2
**Depends on:** that branch landing on main.

## Platforms

### Keep new core code iOS-ready

**What:** A rule, with a check, that new code in `core:model`, `core:agent`, `core:router`, `core:memory` and
`core:security` avoids JVM-only APIs (`java.time`, `java.security.MessageDigest`, `java.util`), using
multiplatform libraries instead.

**Why:** The approved iOS app (G14) needs the core on Kotlin Multiplatform; every new JVM-only call adds port work
for spike S12.

**Context:** The core modules are JVM modules today and already use these APIs. S12 decides the final approach;
this only stops the pile growing. Decision TODO-3, D14.

**Effort:** human S / CC S
**Priority:** P2
**Depends on:** None.

### macOS app

**What:** A Mac version of Anu, matching the Gallery, which ships on macOS.

**Why:** Deferred because the approved parity scope keeps iOS; a Mac app can't read notifications or SMS or sit over other
apps, so it would be a lesser Anu, and it would compete with iOS before either works.

**Context:** Decision MACOS-1, D10 ("iOS only now"). Decide it on its own once the iOS app works, with VISION.md's
scope updated in the same change.

**Effort:** human XL / CC L
**Priority:** P3
**Depends on:** The iOS app (G14), after spike S12.

## Completed
