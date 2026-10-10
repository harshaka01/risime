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

## After setup: test it
Do this on your Pixel with the release build that has the Google Calendar link (the release notes say so),
after the server has `RISI_GCAL=on` (root switches it on). Use a Google account you added as a test user.
Each step says what you should see. If something differs, send a screenshot and the step number.

1. **Connect.** Settings → Risi skills → Calendar → **Connect Google Calendar**.
   - Google shows "Google hasn't verified this app": tap **Continue**.
   - Both permissions are ticked (see events, see your calendar list). Tap **Allow**.
   - You see a list of your calendars. It matches Google Calendar's own list. Under **Check for busy
     times** tick the calendars Risi should read. Under **Add my Risi events to** pick one calendar (for
     example "Work"). Leave **Copy my Risi Calendar events to Google** on, and confirm.
   - The section now says "Connected on this phone · Checking N calendars · Adding events to <name>".
2. **"Am I free" sees real Google events.** Pick a real meeting in your Google Calendar. In the Risi
   chat ask "Am I free on <that day> at <that time>?".
   - Risi says you are not free and the line under the answer reads "Checked: Risi Calendar · Google
     Calendar (<your calendar names>)".
   - Ask for a time with nothing booked: Risi says you are free, and the Checked line is the same.
   - The meeting's title is never shown by Risi in the answer; only that you are busy.
3. **Add the Shenika interview.** In the Risi chat write "add my interview with Shenika on <a day> at
   2pm to my calendar". A card appears: tap **Add**.
   - Open the Google Calendar app (and calendar.google.com). The event "Interview with Shenika" is in the
     calendar you picked, with the note "Added by RisiMe". It has no guests, and no email was sent.
   - Move it in RisiMe (Calendar tab → the event → Edit). It moves in Google within a minute or two.
   - Delete it in RisiMe. It is gone from Google.
4. **A copy you delete in Google stays deleted.** Add the interview again, then delete the copy in the
   Google Calendar app. RisiMe does not add it back. The Google Calendar section shows "1 Risi event was
   removed in Google" with **Add again**.
5. **Ask from another device.** With the Pixel in airplane mode, ask "Am I free ..." from your other
   phone or the laptop. The answer says "Not checked: Google Calendar (phone didn't answer)". It never
   says you are free.
6. **Reconnect.** Either wait 7 days (testing mode) or go to myaccount.google.com → Security →
   Third-party connections → RisiMe → **Remove access**. Then ask "Am I free ..." again.
   - The answer says "Google Calendar (needs reconnecting)", and one card appears in the Risi chat:
     "Google Calendar needs reconnecting". There is only one card per day.
   - Tap **Reconnect** on the card (it opens the Calendar skill), then **Reconnect Google Calendar**. One
     tap on Allow, and reads work again.
7. **Disconnect.** Settings → Risi skills → Calendar → **Disconnect**. Choose "Remove them from Google" (or
   "Keep them in Google"), and confirm.
   - The events RisiMe copied disappear from Google (or stay, if you kept them). Your own Google events are
     never touched.
   - RisiMe is gone from myaccount.google.com → Security → Third-party connections.
   - All your chats are still there.

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
