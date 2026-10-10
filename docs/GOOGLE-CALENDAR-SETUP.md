# Google Calendar setup (Google Cloud Console, ~10 minutes)

For Harsha, with your own Google account, at **https://console.cloud.google.com** (works on a
phone browser; use "Desktop site" if a menu is hidden). Nothing here is secret. Android OAuth
clients have **no client secret**: the phone proves it is RisiMe through the package name and the
signing key's SHA-1. The Google token stays on the phone and is never sent to our server.

Google renames these menus often. The newer name is **Google Auth Platform** (Branding, Audience,
Data Access, Clients); older consoles call it **APIs & Services → OAuth consent screen /
Credentials**. Both lead to the same settings.

## 1. Project
- Top bar → project picker → **New project** → name **RisiMe** → Create → select it.
- (Alternative: the existing Firebase project **risime** (number 734811134567, used for push
  notifications) works just as well. Use one project, not both: an Android client for the same
  package and SHA-1 can exist in only one project.)

## 2. Enable the Google Calendar API
- ☰ → **APIs & Services → Library** → search **Google Calendar API** → **Enable**.

## 3. Consent screen (Google Auth Platform → Get started, or OAuth consent screen)
1. **App information:** app name **RisiMe**; user support email: your address. Next.
2. **Audience:** **External**. Next.
3. **Contact information:** your address. Next → agree to the policy → **Create**.
4. **Branding** (optional for testing): home page `https://risime.risicloud.ai`, authorized
   domain `risicloud.ai`. Leave the logo empty (a logo triggers a review).
5. **Audience → Publishing status:** leave it **Testing**. Do not press "Publish app".

## 4. Scopes (Data Access → Add or remove scopes)
Scroll to **Manually add scopes**, paste these two lines, **Add to table → Update → Save**:

```
https://www.googleapis.com/auth/calendar.events
https://www.googleapis.com/auth/calendar.calendarlist.readonly
```

- `calendar.events`: read your events (busy times for "am I free") and add or update the
  events Risi makes, only on the calendars you pick in RisiMe.
- `calendar.calendarlist.readonly`: list your calendars so you can pick them.

Nothing else: no full `calendar` scope, no Gmail, no contacts.

## 5. Test users (Audience → Test users → Add users)
- Add **your Google account first** (the one whose Google Calendar has your events).
- Then the pilot testers' Google accounts (up to 100). Only listed accounts can connect.

## 6. Android client (Clients → Create client, or Credentials → Create credentials → OAuth client ID)
- Application type: **Android**.
- Name: `RisiMe Android`.
- Package name: **`lk.codegen.risime`**
- SHA-1 certificate fingerprint (our release signing key, read from the published
  nightly.47 APK):

```
4C:E1:A7:54:30:D4:D9:CB:E6:B2:1D:0A:D6:9B:12:5C:F8:67:D4:D0
```

- **Create**. You don't need to download anything. There is no secret.
- Optional, for the developer build on the emulator: a second Android client with package
  `lk.codegen.risime.debug` and SHA-1
  `E5:09:19:56:B7:4E:F6:58:2E:90:BD:3E:46:FD:7B:6A:9E:5A:30:4E`.

No Web client, no API key and no google-services.json change are needed.

## 7. What to send back
Send in chat: **"Google clients done"** plus
- the project ID (e.g. `risime-123456`; shown in the project picker);
- the Android client ID (looks like `123…-abc….apps.googleusercontent.com`). Not secret, but
  only used to check the setup;
- which Google accounts you added as test users (names are enough).

**Never paste** a password, a verification code, or anything labelled "secret" or "private key".
None of these steps produces one.

## Good to know
- **Testing mode:** only the test users can connect. Google ends a testing-mode grant after
  7 days; RisiMe then shows "Reconnect Google Calendar" (one tap).
- **The consent screen says "Google hasn't verified this app":** expected in testing. Tap
  **Continue**.
- **Before a public launch** (Google verification, later): verify `risicloud.ai` in Search
  Console, a public privacy policy on that domain, a short reason for each scope, and a short demo
  video. Both scopes are "sensitive", not "restricted", so no security assessment is needed.
- **Disconnect:** RisiMe → Settings → Risi skills → Calendar → Disconnect Google Calendar, or
  myaccount.google.com → Security → Third-party connections → RisiMe → Remove access.
