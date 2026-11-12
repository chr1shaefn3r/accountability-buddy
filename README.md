# Accountability-Buddy

Set daily time limits for your apps. When you reach a limit, a friend you trust — your
buddy — is told. Nothing is blocked; knowing that your buddy knows is what helps.

One Android app contains both roles, so two friends can hold each other accountable.
Everything the phones tell each other is end-to-end encrypted. The only server part is a
small relay function that forwards opaque blobs and knows nothing about users, apps,
limits or usage.

[SPEC.md](SPEC.md) describes the behaviour, the privacy model and the engineering
standards in full.

## Install the app

Requirements: a JDK (17 or newer), the Android SDK, and a phone with Android 16 and
Google Play services connected with USB debugging switched on.

```bash
git clone https://github.com/chr1shaefn3r/accountability-buddy.git
```

```bash
cd accountability-buddy
```

```bash
./gradlew installDebug
```

Gradle finds the Android SDK through the `ANDROID_HOME` environment variable or a
`local.properties` file with `sdk.dir=...`; Android Studio creates that file for you.

Sender and buddy must install a build from the same state of this repository.

## One-time Firebase setup

Pairing and notifications need a Firebase project. Until this is done the app builds and
runs, but cannot pair: `app/google-services.json` is a placeholder.

1. Create a project in the [Firebase console](https://console.firebase.google.com) and
   switch it to the Blaze plan (Cloud Functions require it; this usage stays within the
   free quota).
2. In the project, add an Android app with the package name
   `dev.chr1shaefn3r.accountabilitybuddy`. Download its `google-services.json`, replace
   `app/google-services.json` with it and commit the file.
3. Deploy the relay function:

   ```bash
   npm install -g firebase-tools
   ```

   ```bash
   firebase login
   ```

   ```bash
   firebase use --add
   ```

   ```bash
   firebase deploy --only functions
   ```

4. Build and install the app again.

The app finds the function at `https://europe-west1-<project-id>.cloudfunctions.net/relay`,
derived from the project in `google-services.json`. If messages do not arrive, check the
function's log in the Firebase console: an error `push failed` with a permission code
means the function's service account lacks the role "Firebase Cloud Messaging API Admin".

## Development

```bash
./gradlew check
```

runs all tests, ktlint, detekt and Android lint. The tests need no device: the domain is
plain Kotlin, and the database adapters and screens run on Robolectric.

```bash
./gradlew ktlintFormat
```

fixes formatting.

The relay function lives in `functions/`:

```bash
npm --prefix functions ci
```

```bash
npm --prefix functions run check
```

### Layout

| Path | Content |
|---|---|
| `app/src/main/kotlin/.../limits` | Limits context: limits, usage, the day, hits (`domain`) and its use cases (`application`) |
| `app/src/main/kotlin/.../buddies` | Buddies context: pairing, encrypted messages, history (`domain`) and its use cases (`application`) |
| `app/src/main/kotlin/.../application` | Connects the two contexts |
| `app/src/main/kotlin/.../infrastructure` | Adapters: database, usage statistics, push, relay client, background work, notifications |
| `app/src/main/kotlin/.../ui` | Compose screens |
| `functions/` | The relay as a Cloud Function for Firebase |
