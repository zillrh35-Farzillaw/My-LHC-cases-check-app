# Case Diary — LHC

An offline Android case-management app for a Lahore High Court practice.

Everything you type stays in a SQLite file on the phone. The only thing that
touches the internet is the cause-list check, and even that is optional.

---

## What it does

**Cases (offline)**

- Add a case: type, number, year, petitioner, respondent, client name and phone,
  bench, judge/court number, stage, next hearing date, fee agreed vs. received, notes.
- Search across every field; filter by Active / Decided / Archived.
- Per-case hearing history — date, what happened, and the next date. Recording a
  next date on a hearing moves the case's next-hearing date forward automatically.
- Tap "Call client" to dial straight from the case.
- Export/import a JSON backup so nothing is lost when you change phones.

**Cause list checking**

- Runs twice a day by default: **18:30** — LHC posts the urgent cause list
  between 5 and 6pm, so this catches it the same evening — and **07:30**, which
  picks up anything added overnight. Both times are adjustable in Settings.
- The **Urgent Cause List** is switched on out of the box and points at
  `data.lhc.gov.pk/index.php/case_management/urgent_cause_list_final`.
  The Regular, Supplementary, Supplementary (Red) and Joint lists are pre-loaded
  but **switched off**, because their exact URLs are unverified — turn one on,
  press "Check cause lists now", and the Diary tab will tell you whether it
  resolved. You can add, edit or delete any page.
- Matches on **keywords you control** — your name as counsel, party names,
  case numbers — plus, automatically, every case saved in the app.
- Results are split into **My cases fixed** (the row was tied to a case you have
  saved) and **Other keyword matches** (caught by a keyword but not linked to any
  of your cases), each row labelled with what caught it — counsel name, party
  name or case number.
- Matching ignores case, spacing and punctuation, and requires every word of a
  term to appear in the row. So `Ahmad Raza` still matches `RAZA, AHMAD ADVOCATE`,
  and `W.P. 12345/2025` still matches `W P No. 12345 / 2025`.
- A high-priority notification fires when something **new** appears. Each row is
  fingerprinted, so the same listing never notifies twice.
- An evening reminder for hearings you entered yourself, the day before.

**Built-in browser — the important fallback**

Some LHC lists are only reachable by filling in a form (pick a date, pick a
bench, submit). A background HTTP fetch cannot do that. So the app ships with a
browser: open the site, drive the form yourself, tap **Scan page**, and the same
matcher runs over whatever is on screen. It can also scan automatically each time
a page finishes loading.

If the automatic check ever comes back with "could not read the cause lists"
— the site changed, or it is blocking plain requests — the browser route still works.

---

## Getting an APK without installing anything

The repo builds itself on GitHub.

1. Create a new repository on GitHub (private is fine).
2. Upload this whole folder to it — either drag the files into GitHub's web
   uploader, or:

   ```bash
   git init
   git add .
   git commit -m "Case Diary"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```

3. Open the **Actions** tab. The "Build APK" workflow starts on its own
   (if not, open it and press **Run workflow**).
4. When the green tick appears, open the run and download the
   **CaseDiary-debug-apk** artifact. Unzip it — `app-debug.apk` is inside.
5. Copy the APK to your phone and open it. Android will ask you to allow
   installing from this source; that is expected for an app not from the Play Store.

The debug APK is signed with Android's standard debug key, which is all you need
to sideload it. If you later want to put it on the Play Store you will need a
release key of your own.

### Or build it locally

Open the folder in Android Studio (Koala or newer) and press Run. Or from a
terminal with the Android SDK installed:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

---

## First run — three things to set up

1. **Allow notifications** when Android asks. Without this the whole alerting
   half of the app is silent.
2. **Settings → Keywords to watch** — add your name exactly as it tends to be
   printed in the cause list. Add a second spelling if it varies. Add party names
   for matters where the case number keeps changing.
3. **Settings → Cause list pages** — the five LHC lists are pre-loaded. Turn off
   the ones you do not care about; each one you leave on is one more page fetched
   twice a day.

Then open **Diary → Check cause lists now** to confirm it can reach the site.
Whatever happens is reported honestly at the top of the Diary tab, including
which sources failed and why.

---

## Battery settings that matter

Android aggressively suspends background work, and Pakistani-market phones
(Xiaomi, Oppo, Vivo, Infinix, Tecno) are among the worst for this. If checks
stop firing:

Settings → Apps → Case Diary → Battery → **Unrestricted** (or "No restrictions" /
"Allow background activity"). On Xiaomi/Redmi also enable **Autostart**.

The app re-arms its own schedule every time you open it, so a missed run repairs
itself on next launch — but an unrestricted battery setting is what makes the
overnight run reliable.

---

## Honest limitations

- **The LHC site's HTML was never inspected.** I could not reach `data.lhc.gov.pk`
  from the machine this was written on, so the scraper is written to be
  structure-agnostic: it reads every table row, list item and paragraph rather
  than depending on specific CSS selectors. This is robust to layout changes but
  cannot promise a perfect parse of a page nobody has seen. The browser "Scan page"
  button exists precisely because of this, and is the reliable path.
- **PDF cause lists are not read.** If a list is published as a PDF, the scan
  will find nothing. Open it in the browser tab instead.
- **Match quality depends on your keywords.** Too broad ("Ahmad") and you will
  get noise; too specific and a typo on the court's side loses the match. Two or
  three spellings of your own name is usually the right amount.
- **Nothing is verified against the court.** Treat a notification as a prompt to
  go look, not as a substitute for checking the list yourself.

---

## Project layout

```
app/src/main/java/pk/advocate/casediary/
├── App.kt                    Application: notification channels, re-arm schedule
├── db/
│   ├── Models.kt             Case, Hearing, Fixture, WatchTerm, Source
│   └── Db.kt                 SQLiteOpenHelper + all queries
├── ui/
│   ├── MainActivity.kt       Bottom nav shell
│   ├── CasesFragment.kt      List, search, filters
│   ├── CaseEditActivity.kt   Add/edit form
│   ├── CaseDetailActivity.kt Detail + hearing history
│   ├── DiaryFragment.kt      Upcoming hearings + cause list hits
│   ├── SettingsFragment.kt   Keywords, sources, schedule, backup
│   └── BrowserActivity.kt    WebView + Scan page
├── work/
│   ├── Scheduler.kt          Two self-renewing wall-clock jobs
│   ├── CheckWorker.kt        The scheduled run
│   ├── CauseListScraper.kt   Fetch + parse + record
│   ├── Matcher.kt            Normalisation and keyword matching
│   └── BootReceiver.kt       Re-arm after reboot
└── util/
    ├── Prefs.kt  Dates.kt  Notifications.kt  Backup.kt
```

No annotation processors, no Room, no Compose — plain SQLite, XML layouts and
ViewBinding, so the build is fast and there is little to go wrong.

---

## Performance notes

Measured on a 2,000-row list matched against 20 keywords and 100 saved cases:

| | before | after |
|---|---|---|
| full scan | 281 ms | **20 ms** (14× faster, identical results) |

What changed, and why it mattered:

- **Keywords are tokenised once per scan, not once per row.** The old code
  re-normalised every keyword against every row — 200,000 tokenisations for the
  scan above, versus about 200 now. This was the whole 14×.
- **One database transaction per page** instead of one insert per matched row.
  Each insert was its own fsync; a busy urgent list made that noticeable.
- **The search box is debounced by 250 ms and queries off the main thread.**
  Typing ten characters used to fire ten ten-column `LIKE` queries on the UI
  thread. It now fires one, in the background, and stale results are discarded
  if you keep typing.
- **Indexes on `cases(status, next_date)` and `fixtures(seen)`** — the two
  filters every screen runs.

Run `./gradlew testDebugUnitTest` for the unit tests covering the matcher
(including the tricky cases: surname-first names, missing dots in case numbers,
hyphenated spellings, and short keywords that must *not* match inside longer
words) and the scheduling maths.
