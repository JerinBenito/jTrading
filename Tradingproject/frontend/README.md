# Trading Signal — mobile app

Expo/React Native (file-based routing via `expo-router`) dashboard for the trading signal
backend at `https://jerintradingsignal.duckdns.org`. Three tabs:

- **Dashboard** — today's same-day close prediction, hourly trajectory chart, latest hourly
  forecast, and the live range-calibration multipliers (hourly/daily) — for whichever
  instrument (NIFTY/BANKNIFTY) is toggled at the top.
- **Signals** — pattern-based signal history and outcomes for the selected instrument.
- **Login** — the daily Upstox re-auth, embedded in-app via WebView instead of the browser
  link. Detects the OAuth redirect back to `/auth/upstox/callback` and shows a native
  "logged in" confirmation.

All data is read-only from the backend's existing REST API — nothing here writes to it
except completing the Upstox login (the same flow the backend has always exposed).

## Develop (Expo Go / fast iteration)

```bash
npm install
npm start
```

Scan the QR code with the Expo Go app (Play Store / App Store) on your phone. Requires your
phone and laptop on the same network (or use `npm start -- --tunnel` if not).

## Build a standalone installable app (EAS Build)

This project is preconfigured (`eas.json`, `app.json` package/bundle identifiers) — you just
need to run the actual build yourself, since it needs your own Expo account:

```bash
npm install -g eas-cli
eas login                                    # your Expo account, or create one free at expo.dev
eas build --platform android --profile preview
```

That produces a downloadable, installable `.apk` (no Play Store needed) — EAS emails/links
you the build when it's done, usually 10-20 minutes on the free tier. For iOS you'd need a
paid Apple Developer account (`eas build --platform ios --profile preview`), not required for
Android-only personal use.

To rebuild after code changes, just re-run the same `eas build` command — no reconfiguration
needed.
