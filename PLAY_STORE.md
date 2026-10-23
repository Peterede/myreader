# MyReader — Play Store release notes

Everything in this file is a checklist for publishing `com.myreader.app`.

## 1. Signing

The release build is signed with the upload key in `keystore/upload-keystore.p12`.
Credentials are in `keystore.properties` (same password for store and key). Both
files are git-ignored and must **never** be committed or shared.

**Back both files up somewhere safe and offline now.** If the upload key is lost,
you can ask Play support to reset it (one time), but a leaked key means a new
upload key must be registered. The app signing key itself lives with Google once
Play App Signing is enabled during the first upload.

Key details:

| Field | Value |
| --- | --- |
| Keystore | `keystore/upload-keystore.p12` (PKCS12, RSA 4096) |
| Alias | `upload` |
| Validity | 10,000 days |
| Subject | `CN=MyReader Upload, OU=Mobile, O=MyReader` |

`app/build.gradle.kts` reads `keystore.properties`; without the file, release
builds are unsigned (handy for CI without secrets).

> The debug build and the upload key have different signatures. Uninstall the
> debug app from a phone before installing a release APK from the same package.

## 2. Build the release artifact

```bash
./gradlew clean bundleRelease   # Play Store upload: app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease       # optional APK for local device testing
```

Verify signing before uploading:

```bash
jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab | tail -3
```

Each upload to Play needs a **higher `versionCode`**. Current values in
`app/build.gradle.kts`: `versionCode = 1`, `versionName = "1.0"`. Bump both for
the next release (`1.0.1` / `2`).

## 3. Store listing copy

**App name** (≤30 chars): `MyReader: EPUB Read Aloud`

**Short description** (≤80 chars):

> Listen to your EPUB and text books read aloud with follow-along highlighting.

**Full description** (≤4000 chars):

> Turn any EPUB or plain-text book into an audiobook with MyReader.
>
> MyReader reads your own books aloud using your device's text-to-speech engine,
> sentence by sentence, and highlights each sentence as it is spoken so you can
> follow along. It sounds like a narrator pausing at punctuation — not a robot
> racing through a wall of text.
>
> **Features**
> - Import EPUB and TXT files straight from your device — no account, no uploads
> - Natural, sentence-aware reading that handles abbreviations and decimals
> - The page follows the voice: the current sentence is highlighted and scrolled into view, and you can tap any sentence to jump there
> - Play/pause, skip sentences, jump chapters, six speeds from 0.75× to 2×, and a position slider
> - Choose the narration voice: pick male or female and American, British, Australian and other English accents available on your device
> - Keeps reading in the background with playback controls in the notification
> - Pauses automatically for calls and other audio, and resumes your place when you come back
> - Works offline: all books stay on your device
>
> **Who it's for**
> Commuters, students, language learners, or anyone who would rather listen than
> read. Bring your own books and let MyReader do the reading.
>
> MyReader uses the text-to-speech engine installed on your phone. Voice
> availability depends on the engine and installed language packs.

**Category:** Books & Reference
**Tags:** audiobook, text to speech, EPUB reader, read aloud
**Contact email:** `<your-support-email>`
**Privacy policy URL:** `<hosted PRIVACY.md URL>`

## 4. Graphics

| Asset | File | Notes |
| --- | --- | --- |
| App icon | `store/icon-512.png` | 512×512 PNG, required |
| Feature graphic | `store/feature-graphic-1024x500.png` | 1024×500 PNG, required |
| Phone screenshots | missing | 2–8 PNG/JPEG, min 320 px, portrait ideally 1080×2400 |

Launcher icon vectors live in `app/src/main/res/` (`adaptive-icon` with a
monochrome variant for themed icons).

Screenshots to capture (library with books, reader playing with highlighted
sentence, voice picker, notification controls). They can be taken with
`adb exec-out screencap -p > store/screenshot-1.png` while the app is open.

## 5. Play Console declarations

**App access:** all features available without login — no credentials needed.

**Ads:** app contains no ads.

**Data safety:**
- Does your app collect or share any of the required user data types? **No.**
- Is all user data encrypted in transit? Not applicable (no data leaves the device).
- Do you provide a way for users to request that data be deleted? Not applicable;
  data (imported books, reading positions) is stored only on-device and is
  removed by clearing app storage or uninstalling.

**Content rating questionnaire:** category Books & Reference; no violence, sexual
content, profanity, drugs, gambling, or user interaction. Answer "No" to
user-generated content, location sharing and purchases. Expected rating:
Everyone / 3+.

**Target audience:** 13+ (imported books are user-supplied and may contain adult
themes; adjust if you want a stricter or broader audience).

**Government apps / financial features / health:** not applicable.

**Permissions used:**
- `POST_NOTIFICATIONS` — shows the playback notification with play/pause/skip controls.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — keeps narration
  running when the app is in the background, declared with
  `foregroundServiceType="mediaPlayback"`.
- No `INTERNET` permission: the app cannot send data anywhere.

## 6. Upload sequence

1. Back up `keystore/upload-keystore.p12` and `keystore.properties`.
2. Bump `versionCode`/`versionName`, run `bundleRelease`, verify the AAB.
3. Create the app in Play Console, enroll in **Play App Signing** (Google holds
   the app signing key; the local key becomes the upload key).
4. Upload the AAB to **Internal testing**, add yourself as a tester, install via
   the test link, and exercise import/playback/background controls.
5. Fill in store listing (copy above, graphics, screenshots), data safety and
   content rating.
6. Host `PRIVACY.md` at a public URL and paste it into the listing.
7. Promote internal → closed/production after the review.

## 7. Known gaps to check before submission

- Phone screenshots are not generated yet.
- Support email and privacy-policy URL are placeholders in this file.
- Only one debug/test device has been exercised; run the Play pre-launch report
  on the internal track before production.
