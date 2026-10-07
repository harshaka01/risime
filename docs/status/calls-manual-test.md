# Calls: manual test for Harsha and Shirazi (two phones)

## Quick 4-step check (needs the one-way-audio fix, nightly.22 or later, on BOTH phones; TURN is live)
Use one phone on Wi-Fi and the other on mobile data (that forces the relay).
1. **A calls B.** B answers. Both talk; **each must hear the other**. A hangs up.
2. **B calls A.** A answers. Again both must hear each other. B hangs up.
3. **B answers locked.** Lock B with the screen off; A calls; B answers from the lock screen (unlock
   first if asked for the microphone). Both hear each other. Hang up.
4. **A normal phone call afterwards.** Right after step 3, make an ordinary mobile call to B (or
   from B). Its microphone and speaker must work normally (RisiMe released the audio).
Report: which step failed, who couldn't hear whom, and the time to the minute.

This is for the first build with the call fix (decision 054). Both phones need that build:
Settings → About shows the version.

Before you start, on both phones:
- Settings → Calls should say nothing is missing.
- Allow notifications.
- Allow the microphone when RisiMe asks.

What the results mean:
- **"Can't connect the call"** after about 10 s means the two phones couldn't reach each other.
  Expected on mobile data until the call ports are opened (see part B). This is not the old bug.
- **The old bug** is any of these. If you see one, report it with the time (to the minute):
  - "You're already in a call" when you're not in a call;
  - "<name> is on another call" when they're not;
  - Answer does nothing;
  - a call screen that won't go away.

## A. Both phones on the same Wi-Fi

1. **Normal call.** Harsha opens the chat with Shirazi and taps the phone icon. Shirazi's phone rings
   and he taps Answer. Within a few seconds both see a running timer and can hear each other.
   Harsha taps End. Both screens close, and the chat shows "Voice call · 0:xx" on both phones.
2. **Call straight back.** Shirazi calls Harsha at once. It rings, then Harsha answers and hangs up.
   *Expected:* no "already in a call" on either phone.
3. **Locked phone.** Shirazi locks his phone with the screen off, and Harsha calls him. The phone
   shows the full-screen call, so Shirazi answers from the lock screen.
   *Expected:* it connects. If RisiMe has never had the microphone, the phone asks you to unlock
   first and then asks for the microphone.
4. **Closed app.** Shirazi swipes RisiMe away from recent apps, and Harsha calls him. *Expected:* it
   rings within a few seconds. Answer it, then hang up.
5. **Decline.** Harsha calls and Shirazi taps Decline. Harsha sees "Call declined", and both chats
   show the declined line.
6. **Cancel.** Harsha calls and taps End before Shirazi answers. Shirazi's ringing stops, and he sees
   "Missed voice call".
7. **No answer.** Harsha calls and nobody answers. After 45 s Harsha sees "No answer", and Shirazi
   gets "Missed voice call" plus a notification.
8. **Kill, then call again.** Start a call and answer it. While talking, the caller force-stops
   RisiMe (Settings → Apps → RisiMe → Force stop).
   *Expected:* the other phone ends the call on its own within about 20 s. When the caller opens
   RisiMe again and calls, it rings normally and never says "already in a call".
9. **Kill while ringing.** Harsha calls, and while it rings he force-stops RisiMe.
   *Expected:* within about a minute Shirazi stops ringing and gets "Missed voice call". Harsha
   opens RisiMe and calls again, and it rings.
10. **A real phone call.** During a normal mobile phone call on Shirazi's phone, Harsha calls him on
    RisiMe. *Expected:* Harsha sees "Shirazi is on another call".

## B. Mobile data on one or both phones

11. Repeat steps 1 and 8 with one phone on mobile data, then with both on mobile data.
    *Expected:* it connects through the relay (TURN is live since 2026-10-07 06:34 UTC) and both
    hear each other. "Can't connect the call" now means a real network problem: report the time.
12. If a call fails in a way that doesn't match the expected result, send the time (to the minute).
    The server now logs every call signal and every call push, so the attempt can be traced.
