# Cloud Sync setup (free)

This turns on three optional things, none of which cost anything:

1. **Sign in** so your cases sync between your phone and the web app.
2. **A scheduled check** that looks for your cases in the LHC cause list
   every 3 hours, even if neither app is open.
3. **A push notification** when that check finds something new (once the
   Android side is wired up — see the note at the end).

Nothing here is required. If you skip this entirely, both apps keep
working exactly as before, fully offline, local-only.

Everything below stays on Firebase's free **Spark** plan — no credit card,
no billing ever enabled. The scheduled check itself runs on GitHub Actions
(free on a public repo), not on Firebase, which is what keeps this free —
Firebase's own scheduled server-side code (Cloud Functions) requires a
paid plan the moment it needs to call an outside website like LHC's.

## 1. Create the Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
   and sign in with any Google account.
2. Click **Add project**, give it any name (e.g. "ccms-sync"), and finish
   the wizard. Decline Google Analytics if asked — not needed.
3. Wait for it to finish provisioning, then open the project.

## 2. Turn on Authentication

1. In the left sidebar: **Build → Authentication → Get started**.
2. Under **Sign-in method**, enable **Email/Password**. Save.

## 3. Create the Firestore database

1. Left sidebar: **Build → Firestore Database → Create database**.
2. Choose **Start in production mode** (the security rules in this repo's
   `firestore.rules` already lock it down correctly).
3. Pick any region close to you, then **Enable**.
4. Once it's open, deploy this repo's rules so they actually take effect:
   - Easiest: in the Firestore Database page, click the **Rules** tab,
     paste in the contents of this repo's `firestore.rules`, and
     **Publish**.
   - Or, if you have Node.js and want to use the CLI instead:
     `npm install -g firebase-tools`, then `firebase login`,
     `firebase use --add` (pick this project), then
     `firebase deploy --only firestore:rules` from this repo's root.

## 4. Get the web app's config, and fill it into `casediary.html`

1. In the Firebase console: the gear icon next to **Project Overview** →
   **Project settings**.
2. Scroll to **Your apps**, click the **</>** (web) icon to register a new
   web app. Any nickname is fine. You don't need Firebase Hosting.
3. It shows a `firebaseConfig` object with real values for `apiKey`,
   `authDomain`, `projectId`, `storageBucket`, `messagingSenderId`, `appId`.
4. Open `casediary.html` in this repo, find the `firebaseConfig` object
   (search for `REPLACE_ME`), and paste in those six real values in place
   of the six `"REPLACE_ME"` placeholders. Save, commit, push.
5. These values are **not secret** — Firebase's own docs say a web app's
   client config is meant to be public. What actually protects your data
   is `firestore.rules` (already in this repo), which only lets a
   signed-in user read or write their own document.
6. Open the web app, go to the **Backup** tab → **Cloud Sync** card →
   **Create account** with any email/password → **Sign in**. You should
   see "Signed in as …" and an **Upload my data to cloud** button.

## 5. Let the scheduled check run (GitHub side)

1. In the Firebase console: **Project settings → Service accounts** tab.
2. Click **Generate new private key** → confirm. A `.json` file downloads.
   Keep this one actually secret — unlike the web config, this key can
   read/write *everyone's* data in the project, so it's only ever used
   server-side (by the GitHub Actions workflow), never put in the app.
3. In this repo on GitHub: **Settings → Secrets and variables → Actions →
   New repository secret**.
   - Name: `FIREBASE_SERVICE_ACCOUNT_KEY`
   - Value: paste the **entire contents** of the downloaded `.json` file.
4. Save. The `Scheduled Cloud Cause-List Check` workflow (already in this
   repo, runs every 3 hours) will pick it up automatically on its next
   run — no further action needed. You can also trigger it immediately:
   this repo's **Actions** tab → **Scheduled Cloud Cause-List Check** →
   **Run workflow**.
5. Back in the web app's Cloud Sync card, turn on **"Let the scheduled
   server-side check scan for me"**. Without this switch on, the
   scheduled check skips you — it only scans for users who've opted in.

## 6. Try it

- In the web app, add a keyword (Keywords tab) you expect to appear in a
  real cause list, then go to Backup → Cloud Sync → **Upload my data to
  cloud**.
- Trigger the workflow manually once (Actions tab → **Run workflow**) and
  watch its log. It should say how many hits it found for your account.
- Reopen the web app (or just revisit the Backup tab) — any new match
  gets pulled in automatically and will show up in the Diary tab.

## Known limitations of this first version

- The scheduled check matches keywords, case numbers, and pending files,
  and respects the principal-seat (Lahore-only) filter — but it does
  **not** yet fold C.M./application lines into their main case, or
  extract the judge name/hearing date, the way the in-app scan does. A
  hit it finds will still show up correctly; it just won't have those
  extra touches yet.
- The live fetch against the LHC site could not be tested from the
  sandbox that built this (that network is blocked there). It should work
  from GitHub's own runners, which have normal internet access, but the
  first few scheduled runs are worth checking in the Actions log.
- **Android isn't wired up to cloud sync yet.** Adding the Firebase SDK to
  the Android build needs to be done as its own careful, separately
  tested step (it risks breaking the APK build pipeline if done
  carelessly) — ask for it explicitly when you're ready, ideally once
  you've already done steps 1–5 above and have your Firebase config and
  a way to test an Android build.
