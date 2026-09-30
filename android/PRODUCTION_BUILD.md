# Signed Android release

Build the full `app.mascot3` application (not the offline demo):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
MASCOT3_TEST_JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  node scripts/build-android-release.mjs
```

Run this from the repository root. It assembles a signed release, runs lint and
offline release unit tests. No generation requests are made by this command.
Output: `android/app/build/outputs/apk/release/app-release.apk`.

Version 0.1.40 uses compileSdk 36, AGP 8.10.1 and Gradle 8.11.1 for stable
Health Connect 1.1.0. The launcher JVM remains JDK 17, minSdk 26 and targetSdk 35.

Production disables debugging, HTTP request logging, Android backup and cleartext
traffic. The release build requires an explicit HTTPS server, its client token and
a production signing key; it never silently uses the debug certificate or localhost.
Minification is intentionally disabled; this release does not rely on obfuscation to
protect credentials or risk reflection/serialization changes in the paid pipeline.

The persistent local signing identity is `mascots-release`, stored outside the
repository at `~/Library/Application Support/Mascots/signing/mascots-release.p12`.
The generated signing password is derived as base64 from the adjacent private
`release-password.bin` file. Both have owner-only permissions. Back up both files
securely: losing the key prevents signing future updates for this release.
Do not commit, share or include these files in APK distribution.

On another build host set `MASCOT3_RELEASE_STORE_FILE`,
`MASCOT3_RELEASE_STORE_PASSWORD`, `MASCOT3_RELEASE_KEY_ALIAS` and optionally
`MASCOT3_RELEASE_KEY_PASSWORD`. Provide `MASCOT3_SERVER_URL`,
`MASCOT3_CLIENT_TOKEN` and the owner-authorized `MASCOT3_OPENROUTER_API_KEY`.
On this host the script can reuse the previous local debug BuildConfig connection
settings, without writing their values into tracked source files.

**Update compatibility:** earlier team APKs use a debug certificate. A release
signed with this production identity cannot update those installations in place.
Do not uninstall an old installation without backing up its characters, settings
and paid-job state. This build does not uninstall or install anything on a phone.

**Distribution scope:** the owner-requested common OpenRouter key is preserved
for the team build. Anyone with the APK can extract it and spend its balance.
This is a release APK for trusted distribution, not a claim that embedding a shared
provider key is safe for a public/store launch. A public version should use server-side
keys or each user's personal key; changing that mode is a separate decision.
