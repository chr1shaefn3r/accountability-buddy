# Accountability-Buddy — Specification

Status: **Approved and implemented.** A change in behaviour updates this document in the
same commit.

## 1. Purpose

Accountability-Buddy helps a person keep their app usage in check with the help of a
trusted friend. The person sets daily time limits for apps on their own phone. When a
limit is reached, the friend (the "buddy") is notified. Nothing is blocked: the effect
is purely social accountability.

The system has two parts:

- an **Android app** that contains both roles (sender and receiver), and
- a **small relay function** in Firebase (Cloud Functions) that passes encrypted messages between phones and
  knows nothing about users, apps, groups, limits or notification content.

## 2. Terms

| Term | Meaning |
|---|---|
| **Sender** | The person whose app usage is limited and tracked. |
| **Buddy** (receiver) | The person who is notified about the sender's limits. |
| **Limit** | A daily time budget for one or more apps. |
| **Pairing** | An encrypted link between one sender and one buddy. |
| **Day** | A calendar day in the sender phone's local time zone, 00:00 to 24:00. |

One installation can be a sender and a buddy at the same time. A sender can have
several buddies, and a buddy can support several senders. Two friends can therefore
hold each other accountable with the same app.

## 3. Sender features

### 3.1 Limits

- A limit has a **name**, a **daily duration** (1 minute to 24 hours; the picker in the app goes up to 23h 59m) and a
  set of **one or more apps**.
- A limit with one app is a per-app limit ("Instagram, 1h"). A limit with several apps
  is a shared budget ("Social Media, 1h" across Instagram, TikTok, ...).
- An app may belong to several limits. Its usage counts against each of them.
- Apps are chosen from the list of launchable apps installed on the phone.
- Limits apply every day. Different limits per weekday are out of scope.

### 3.2 Usage tracking

- Usage is the time an app is in the foreground, read from Android's
  `UsageStatsManager` events. This needs the "Usage access" special permission, which
  the app asks for during onboarding.
- A foreground service with a permanent, low-priority notification watches usage while
  the screen is on. A limit hit is detected within about 30 seconds.
- The service restarts after a reboot. Onboarding asks the user to exempt the app from
  battery optimisation.
- The sender screen shows today's usage against each limit.

### 3.3 When a limit is hit

- The sender gets a local notification ("Social Media: 1h limit reached").
- Every buddy is sent an encrypted **limit-hit** message.
- Each limit triggers at most once per day. Further use is not reported instantly, but
  the final total appears in the day summary.
- Nothing is blocked. The sender can keep using the app.

### 3.4 Midnight reset

- At 00:00 local time all usage counters and "hit" states reset.
- Usage that runs across midnight is split at midnight.
- At the reset the sender phone sends every buddy an encrypted **day-close** message
  with, for each limit hit that day, the name, the limit and the final usage
  (for example "Instagram: 1h 45m of 1h").
- The day-close message is sent every day, also when no limit was hit. It doubles as a
  sign of life (see 4.3).
- If the phone is off or offline at midnight, the message is sent as soon as possible
  afterwards.

### 3.5 Transparency towards buddies

Any change that weakens the setup is reported to all buddies immediately, as an
encrypted message:

- raising the duration of a limit,
- deleting a limit,
- removing an app from a limit,
- removing a buddy (that buddy is told; see 5.4),
- loss of the usage-access permission or the notification permission.

Changes that tighten the setup (new limit, shorter duration, added app) are silent.
All changes take effect immediately; there is no approval step and no cool-down.

## 4. Buddy features

### 4.1 Notifications

For each sender, the buddy can switch two things on or off:

| Setting | Default | Shown when |
|---|---|---|
| Instant notifications | on | A limit is hit or the setup is weakened. |
| Day summary | off | The sender's day closes at midnight and at least one limit was hit. |

The day summary is shown as a silent notification when the day-close message arrives,
that is right after midnight in the sender's time zone. On days without a hit no
summary notification is shown.

These settings live only on the buddy's phone. The sender always sends everything;
the buddy's phone decides what to show.

### 4.2 History

For each sender the buddy sees a list of received events and day summaries for the
last 30 days. Older entries are deleted.

### 4.3 Silence alert

The daily day-close message (3.4) is an invisible sign of life: on a day without a hit
the buddy's phone receives it but shows nothing. A well-behaved sender therefore causes
no notifications at all.

Only if no day-close message has arrived from a sender for **7 days** does the buddy
get a notification ("No sign of life from Alex since Monday"). This covers cases the
sender phone cannot report itself, such as the app being uninstalled or force-stopped.

## 5. Pairing

### 5.1 Flow

1. The sender opens "Add buddy". The app shows a QR code, valid for 10 minutes and for
   one use.
2. The buddy opens "Support someone" and scans the code.
3. The buddy sees "Support ‹sender's name›?" with their own display name prefilled,
   can change it for this pairing, and confirms.
4. The buddy's phone sends an encrypted pairing request through the relay server.
5. The sender sees "Pair with ‹buddy's name›?" and confirms. An encrypted acceptance
   goes back.
6. Both phones show the pairing as active.

Both phones need an internet connection during pairing, but do not have to be on the
same network.

### 5.2 Display names

- During onboarding the app asks every user once for a **display name** (free text,
  for example a first name). It can be changed later in Settings.
- The sender's display name travels inside the QR code. The buddy's display name
  travels inside the encrypted pairing request. Names are therefore only ever visible
  to the two paired phones, never to the server.
- Each phone can rename a partner locally, in case two partners chose the same name.
- A changed display name is sent to existing partners as an encrypted message.

### 5.3 QR code content

The QR code holds a version number, the sender's public key for this pairing, the
sender's push address (the phone's Firebase installation ID), a one-time pairing secret,
the time the code expires and the sender's display name. It is shown only on the
sender's screen and never sent to the server.

### 5.4 Unpairing

Either side can end a pairing. The other side receives an encrypted notice and marks
the pairing as ended. Keys for the pairing are deleted on both phones.

## 6. Privacy and security

### 6.1 End-to-end encryption

- Each pairing has its own X25519 key pair on each phone. The shared key is derived
  with HKDF-SHA256 and messages are sealed with AES-256-GCM.
- Private keys never leave the phone and are stored encrypted with an Android Keystore
  key.
- Every message is padded to a fixed size before encryption, so its type cannot be
  guessed from its length.
- Each message carries a counter and an identifier. Replayed or duplicated messages are
  dropped.
- The pairing secret from the QR code is part of the key derivation for the pairing
  request, so only someone who saw the QR code can pair.
- Forward secrecy (key rotation per message) is out of scope for version 1.

### 6.2 What the server can and cannot see

The server is a stateless relay. It has **no database** and stores nothing.

| The server sees | The server never sees |
|---|---|
| The recipient's push address (Firebase installation ID), for the duration of one request | Names, app names, limits, groups, usage |
| An opaque encrypted blob of fixed size | Message type or content |
| Time and IP address of the request (as any web server does) | Who is paired with whom as a stored relation |

Request bodies are not logged.

Google's Firebase Cloud Messaging delivers the push. Google sees the same as the
server: a push address, an encrypted blob and a time. This is the accepted price for instant,
battery-friendly delivery.

### 6.3 Known limits of the trust model

- A sender who changes the phone's clock can distort the day boundary.
- Uninstalling or force-stopping the app cannot be reported by the app itself; the
  silence alert (4.3) is the safeguard.
- The relay endpoint is open. Someone who knows a phone's push address could send junk to it;
  the app discards anything it cannot decrypt.

## 7. Server

### 7.1 Stack

- One Cloud Function for Firebase (2nd generation, HTTPS, TypeScript) in the
  `functions/` folder of this repository, in the same Firebase project the app uses
  for push messages. Region: `europe-west1`.
- No database, no user accounts, no other Firebase products.
- No secret to manage: the function uses the project's built-in credentials to send
  push messages.
- Cloud Functions require the Firebase **Blaze** plan (a billing account). The expected
  usage is far inside the free quota.

### 7.2 API

| Method and path | Purpose |
|---|---|
| `POST /relay` | Body: `{ "to": "<push address>", "payload": "<base64 blob>" }`. Forwards the blob as a high-priority FCM data message with a 3-day time-to-live. Returns `204` when the push service accepted it, `410` when the address is no longer valid, `400` for a malformed request, `413` for a payload over 3 KB and `502` when the push service failed (the app retries later). |

The handshake uses the same endpoint: the pairing request and acceptance are ordinary
encrypted blobs. That is how the server "facilitates the handshake" without learning
anything.

### 7.3 Push addresses

A phone's push address is its Firebase installation ID. It stays the same until the app
is reinstalled or its data is cleared. Should it change, the phone sends the new address
to all its pairing partners as an encrypted message. If the server answers `410` for a
partner, the app marks that pairing as "unreachable" and suggests pairing again.

## 8. Android app

### 8.1 Stack

- Kotlin, Jetpack Compose, Material 3, single Gradle module `app`.
- Minimum and target version: Android 16 (API 36). Older versions are not supported,
  which keeps the code free of compatibility branches. This applies to the buddy's
  phone as well.
- Room for local data, WorkManager for the outgoing message queue, exact alarms for the
  midnight reset.
- Firebase Cloud Messaging for receiving messages. Requires Google Play services.
- Google Tink for cryptography. ZXing for drawing QR codes; the Google code scanner of
  Google Play services for scanning them, which needs no camera permission.
- User interface language: English.
- User interface: see section 9.

### 8.2 Permissions

Usage access, notifications, internet, foreground service,
start on boot, exact alarms, battery-optimisation exemption.

### 8.3 Reliability

Outgoing messages are stored in a local queue and sent when a network is available,
with retries. Messages are delivered in order per pairing.

## 9. User experience

### 9.1 Paradigm

- **Material Design 3** as shipped with Jetpack Compose, using stock components only.
  No custom design system, no custom widgets, no custom animations.
- **Dynamic colour** (Material You): the app takes its colours from the user's
  wallpaper and follows the system light or dark theme. There is no in-app theme
  setting.
- Platform conventions are followed as they are: edge-to-edge layout, predictive back,
  system font size, system notification settings.
- The app should feel like a part of Android's own settings: calm, predictable, quick
  to leave.

### 9.2 Navigation

A bottom navigation bar with two destinations, one per role:

| Destination | Content |
|---|---|
| **My limits** | The sender role. |
| **Supporting** | The buddy role. |

Settings is reached through an icon in the top bar. Everything else opens as a
full-screen page with a back arrow. Navigation is never more than two levels deep.

### 9.3 Screens

- **Onboarding**: one step per screen: what the app does, display name, then one
  permission per screen, each with a one-sentence reason before the system dialog
  appears. Permissions for a role are requested when that role is first used: a user
  who only supports others is never asked for usage access.
- **My limits**:
  - A row at the top shows the buddies ("2 buddies" or "No buddy yet — add one") and
    opens **My buddies**.
  - Below, one card per limit: name, app icons, a progress bar, and "45m of 1h".
    A limit that was hit is shown in the theme's error colour.
  - A floating action button adds a limit.
- **Edit limit**: name, duration picker (hours and minutes), and the list of apps.
  "Add apps" opens the **app picker**: a searchable list with icons and checkboxes.
- **My buddies**: list of buddies with their status, "Add buddy" (shows the QR code
  with a countdown), remove buddy.
- **Supporting**: one card per sender with the latest event ("Hit Social Media
  yesterday" or "All quiet for 5 days"). "Support someone" opens the scanner.
- **Sender detail**: the two notification switches (4.1), the history as a list
  grouped by day, rename, stop supporting.
- **Settings**: display name, permission status with a "Fix" button for anything
  missing, about.

### 9.4 Principles

- **Empty states guide.** A screen without content explains in one sentence what it is
  for and offers the one action that fills it.
- **No surprises about what buddies learn.** Before a change that weakens the setup
  (3.5) is saved, a dialog says "Your buddies will be told about this change" and asks
  for confirmation. The pairing screen states what a buddy will and will not see.
- **Neutral wording.** Texts state facts ("Social Media: 1h limit reached") and never
  judge or shame, on either phone.
- **Problems are visible.** If tracking does not work (missing permission, service
  stopped), a banner on **My limits** says so and offers the fix.
- **Notification channels.** Each kind of notification has its own Android channel
  (limit reached, day summary, changes by the sender, silence alert, tracking
  service), so users can tune sound and visibility in the system settings.
- **Accessibility.** Screen-reader labels on every control, touch targets of at least
  48 dp, layouts that hold up at the largest font size, and information never
  conveyed by colour alone.
- **Durations** are always written as "1h 45m".

## 10. Build and deployment

### 10.1 App

```bash
git clone https://github.com/chr1shaefn3r/accountability-buddy.git
```

```bash
cd accountability-buddy
```

```bash
./gradlew installDebug
```

- The Gradle wrapper and `app/google-services.json` are committed, so no further setup
  is needed besides a JDK (17 or newer), the Android SDK and a connected phone with USB
  debugging.
- The app derives the relay function's URL from the Firebase project in
  `google-services.json`; there is nothing else to configure.
- Sender and buddy must install a build from the same repository state, because both
  must belong to the same Firebase project.

### 10.2 Server

```bash
firebase deploy --only functions
```

Run from the repository root, with the Firebase CLI logged in to the project.

### 10.3 One-time setup by the repository owner

1. Create a Firebase project, switch it to the Blaze plan, and add an Android app with
   the application ID `dev.chr1shaefn3r.accountabilitybuddy`.
2. Replace the placeholder `app/google-services.json` with the file from the Firebase
   console and commit it. With the placeholder the app builds and runs, but cannot pair.
3. Deploy the function (10.2).

The README has the commands for each step.

## 11. Engineering standards

### 11.1 Domain-driven design

- The terms of section 2 are the **ubiquitous language**. Code, tests and user
  interface use exactly these words (`Limit`, `Pairing`, `Buddy`, `Day`, ...).
- Two bounded contexts: **Limits** (limits, usage, the day) and **Buddies** (pairing,
  messages, history). They talk to each other through domain events such as
  `LimitHit`, `LimitWeakened` and `DayClosed`.
- The **domain layer is plain Kotlin** with no Android, Firebase, Room or Tink imports.
  Everything outside (usage statistics, clock, storage, push, encryption, relay) is
  reached through interfaces owned by the domain (ports) and implemented in an
  infrastructure layer (adapters).
- Aggregates guard their own rules, for example "a limit has at least one app" or
  "a limit triggers once per day". Domain objects are immutable; invalid states cannot
  be constructed.
- Time is always injected through a clock port, never read directly, so that midnight
  and time-zone behaviour is testable.

### 11.2 Test-driven development

- Red, green, refactor: a failing test is written before the production code it covers.
- Domain and application logic is covered by fast JVM unit tests. They are the bulk of
  the test suite and need no device or emulator.
- Adapters get a small number of integration tests (Room, message encoding, encryption
  round trip). The Cloud Function has unit tests with the Firebase SDK replaced.
- The screens are tested by driving the real app on Robolectric, with only the Keystore,
  the push service, the relay and the job scheduler replaced. No device is needed.
- Hand-written fakes for ports are preferred over mocking libraries.
- Tests describe behaviour in the ubiquitous language, not implementation details.
- A feature is done when its tests pass and `./gradlew check` is green.

### 11.3 Simplicity

- The fewest components that solve the problem: one Gradle module, one Cloud Function,
  no database on the server, no abstractions without a second use.
- Layers are packages, not modules. No dependency-injection framework; wiring is done
  by hand in one place.
- No speculative features. Anything not in this spec is not built.

### 11.4 Third-party dependencies

- Established, maintained libraries are used instead of own implementations wherever
  they fit: AndroidX, Compose, Room, WorkManager, Firebase, Google Play services, Tink,
  ZXing.
- **No self-made cryptography.** Only Tink's primitives are used.
- Each dependency must earn its place; a few lines of own code beat a large library
  used for one function.
- Versions are pinned in a Gradle version catalog (`gradle/libs.versions.toml`).

### 11.5 Further standards

- **Static analysis**: ktlint for formatting and detekt for code smells in the app,
  ESLint and strict TypeScript in the function. Compiler warnings are errors.
- **Continuous integration**: a GitHub Actions workflow builds the app and runs all
  tests and checks on every push.
- **Commits**: small, one concern each, with a message that explains why.
- **Secrets**: none in the repository. `google-services.json` is public client
  configuration and is the only exception to "no config files in git".
- **Privacy in code**: no analytics, no crash reporting, and nothing personal in logs.
- **Living spec**: a change in behaviour updates this document in the same commit.

## 12. Out of scope for version 1

- Blocking or overlaying apps.
- iOS, tablets-specific layouts, Wear OS.
- Phones without Google Play services.
- Limits per weekday, schedules, website limits.
- Buddy approval for changes, cool-down periods.
- Cloud backup or transfer of pairings to a new phone.
- Release builds and Play Store publication.

## 13. Open points

None at the moment.

## 14. Decisions taken

| Question | Decision |
|---|---|
| Who is notified on a limit hit? | The buddy, instantly. The sender only sees a local notice. |
| Who can subscribe to the day summary? | The buddy. |
| What happens on the sender phone at the limit? | Notification only, no blocking. |
| How are messages delivered? | Firebase Cloud Messaging through a stateless Cloud Function relay. No Vercel. |
| Application ID | `dev.chr1shaefn3r.accountabilitybuddy` |
| Pairing model | Several buddies per sender; one phone can have both roles. |
| Weakening the setup | Buddies are notified immediately. |
| Day summary time | Right at midnight, when the day resets. |
| Use beyond the limit | One instant notification; final total in the summary. |
| `google-services.json` | Committed to the repository. |
| Days without a hit | No summary notification. |
| Silence alert | After 7 days without a sign of life. |
| What a buddy knows | Only events (hits, weakenings, summaries); not the full list of limits or live usage. |
| Minimum Android version | Android 16 (API 36), on both phones. |
| Interface language | English only. |
| Design language | Material Design 3 with dynamic colour, stock components only. |
| Push address | The Firebase installation ID, as Firebase has deprecated registration tokens. |
| QR scanning | Google code scanner (Play services) instead of an own camera screen. No camera permission. |
