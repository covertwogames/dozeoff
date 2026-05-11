# DozeOff-FOSS Build Notes

This is the off-Play distribution build of DozeOff. Differences from the Play
build:

- **No Google Play Billing** — the three tip-jar buttons are replaced with one
  "❤️ Support Development" button that opens https://ko-fi.com/covertwo in the
  browser. `BillingManager.kt` and the `com.android.billingclient` dependency
  are gone.
- **No Google Play In-App Review** — the `play:review` and `play:review-ktx`
  dependencies, the `requestInAppReview()` call in `MainActivity`, and the
  "Leave us a Review on Google Play" button in Settings are all removed.
- **applicationId suffix `.foss`** — installed package is
  `com.covertwogames.dozeoff.foss`. This means the FOSS build can sit
  alongside the Play build on the same device for testing.
- **Builds as a signed APK**, not an AAB.

The unrelated app/note `dashboardOpenCount` and `lastReviewRequestOpen`
properties in `PrefsManager` were left in place since they're harmless dead
code; remove them later if you want.

---

## One-time setup

### 1. Generate a keystore

You only do this once. Pick a directory **outside** the project tree (so it
doesn't accidentally get committed or zipped up). For example:

```
keytool -genkey -v -keystore C:\keystores\dozeoff-foss.jks ^
  -keyalg RSA -keysize 2048 -validity 10000 -alias dozeoff
```

(On Windows that's one logical line — the `^` is the line-continuation
character. On Mac/Linux use `\` instead.)

It will prompt you for:
- A keystore password (used to open the keystore file)
- A key password (often the same as the keystore password — that's fine)
- Some identity fields (name, org, city, country) — these show up in the cert
  but aren't user-visible, so just fill in something reasonable

**Back up this file somewhere safe.** If you lose it, you cannot publish
updates to existing FOSS-build users — they would have to uninstall and
reinstall to update. There is no recovery mechanism.

### 2. Wire the keystore into local.properties

Open `local.properties` in the project root. The bottom of the file already
has a commented-out template. Uncomment the four lines and fill them in:

```
RELEASE_STORE_FILE=C:/keystores/dozeoff-foss.jks
RELEASE_STORE_PASSWORD=yourpassword
RELEASE_KEY_ALIAS=dozeoff
RELEASE_KEY_PASSWORD=yourpassword
```

Use forward slashes in the path even on Windows. `local.properties` is
already gitignored.

---

## Building a signed APK

From the project root:

```
gradlew assembleRelease
```

(Use `./gradlew assembleRelease` on Mac/Linux.)

When it finishes, the signed APK is at:

```
app/build/outputs/apk/release/app-release.apk
```

That's the file you upload to your website, Amazon Appstore, or Samsung
Galaxy Store.

If you ever want to sanity-check the signature on the built APK:

```
%ANDROID_HOME%\build-tools\36.0.0\apksigner verify --verbose app\build\outputs\apk\release\app-release.apk
```

(Adjust the build-tools version to whatever is installed on your machine.)

---

## What if I don't configure a keystore?

The `build.gradle` is set up to skip the signing config block if
`RELEASE_STORE_FILE` is missing from `local.properties`. In that case
`assembleRelease` will produce an unsigned APK in the same output location,
and you'd have to sign it manually with `apksigner` afterward. This is just
a fallback — for normal use, configure the keystore once and let gradle
handle signing.
