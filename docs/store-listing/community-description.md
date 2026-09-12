# MageHand — community description

Shareable write-up for forums, subreddits and Discords (2026-09-12, app 1.16.0).
Deliberately free of private infrastructure: no host names, no party names, no LAN
addresses — see `tools/public-gate.sh` for the forbidden list this was written against.

## Short version (one-liner / Discord blurb)

> **MageHand** — a free, open-source Android tracker for D&D 5e. Spell slots, HP,
> resources, inventory and actions one tap away at the table. Run a character entirely
> on your device with no account, or sign in to sync live with a DiceCloud v2 sheet
> (dicecloud.com or self-hosted).

## Long version

> ### MageHand — an Android tracker for 5e, standalone or DiceCloud-synced
>
> The stuff you poke fifteen times an hour at the table — slots, HP, resources —
> deserves to be one tap away on your phone. MageHand does that two ways:
>
> **Standalone, no account.** Create a character on the device and run it: HP, custom
> resource rows, your own abilities with use counts and costs, rests that reset the
> right things. Nothing to sign up for, no server, no network. If you don't use
> DiceCloud at all, the app still works.
>
> **Or synced to DiceCloud v2.** Sign in to dicecloud.com or your own self-hosted
> instance (you enter the server URL) and the tracker builds itself from your sheet,
> live in both directions.
>
> **What's in it**
> - **Tracker** — spell slots, class resources (rage, ki, wild shape, inspiration…),
>   hit dice, HP with temp HP, death saves, concentration, conditions,
>   resistances/immunities. One tap to spend, undo on everything, short/long rest
>   buttons that reset exactly what the sheet says they reset, with a badge on each row
>   telling you which rest touches it.
> - **Inventory** — collapsible sections, equip/unequip, move items between containers,
>   wallet summary, search, sort by weight/value/name.
> - **Actions & spells** — what your character can *do*, by level and prepared/known,
>   with attack and damage modifiers resolved, weapon mastery lines surfaced, and a
>   "Use" button that spends the slot or charge for you.
> - **Full sheet** (synced mode) — the DiceCloud web UI embedded and already signed in,
>   so the character creator and anything not yet native is right there.
> - **DM view** (synced mode) — live trackers for a whole party on one screen plus a
>   read-only feed of who spent what and who rested. Built for a tablet.
> - **Offline** — the last synced sheet stays readable with no connection.
> - **Customizable** — pin/hide/reorder tracker rows and inventory sections,
>   per-character accent colour and portrait, configurable tab order, UI scale from
>   70% to 150%.
>
> **What it needs:** Android 11 (API 30) or newer. Nothing else unless you want sync —
> and then your credentials only mint a login token you can revoke, stored encrypted in
> the Android Keystore.
>
> **What it doesn't do:** no ads, no analytics, no crash reporting, no account of its
> own, nothing sent anywhere but the DiceCloud server you chose.
>
> Licensed **GPLv3**, same as DiceCloud itself, so code can flow either way with
> upstream.
>
> Play Store: https://play.google.com/store/apps/details?id=com.hashtagchow.magehand
> Source: https://github.com/hashtagchow/magehand
>
> Feedback and feature requests welcome.

## Notes for whoever posts it

- The headline deliberately does not lead with "companion app for DiceCloud" — that
  framing undercuts the standalone mode, which is the wider hook.
- Confirm the Play URL resolves publicly before posting.
- Keep this file in sync when the feature list moves: it claims FR-23 death saves,
  FR-29 local actions, FR-30 hit dice, FR-38 UI scale floor, FR-44 limited-use rows and
  FR-47 weapon mastery.
