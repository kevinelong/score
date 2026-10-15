# Score — state for a restart

Written 2026-10-12, last updated 2026-10-15, so nothing is lost across a restart. Two repositories:

- **`C:/Users/kevin/score`** — the Android scoreboard app (public: `kevinelong/score`,
  release repo `kevinelong/score-android`).
- **`C:/Users/kevin/score-ref/15ball-scoresheet`** — a working clone of the tournament server and web
  console (`kevinelong/15ball-scoresheet`). `main` only, and it is pushed: nothing is stranded there
  in a branch or in the working tree (see "The clone" below).

## Shipped and pushed

**Android app 1.4** — `kevinelong/score` @ `f1e8bd2`, `kevinelong/score-android` @ `1475f43`,
release **v1.4**. `score-release.apk` 87,431 bytes, sha256
`6cf0081f43578850cd1482d54b95a132d96b6139d60b78d449fb34c51868e01e` (the release asset and the
committed raw file both hash-match the local build).

New in 1.4, all driven on the emulator before shipping:

- Three-digit scores and goals, because two of the server's disciplines are played past 99 (14.1 and
  15-ball rotation run to 75/100/150).
- Goal wording per discipline, matching their console's own labels: "Race to 7" against
  "Points to 100", with "match point" one short. Set by tapping the race line.
- Read-only fallback: a tournament not open for anonymous scoring (401 on the scoring endpoints) now
  loads from `GET /api/v1/public/tournaments/{id}` as a view-only board with Submit disabled and the
  reason on screen, instead of an error over a blank one.
- Re-read on resume, so a director's correction or another phone's result appears at the table
  without touching the tallies.
- The far half is drawn rotated for the player sitting opposite, with its swipes inverted to match;
  a long press on the centre bar flips it for a lone scorekeeper.
- Table number leading the linked bar, full brightness for the dim room, a first-run gesture hint,
  reset asking first in a linked match, and the match last scored for a tournament leading the picker.
- Relaunching from the launcher no longer re-opens the link and throws away tallies in progress.

Evidence lives in `verify/` (trace screenshots, pixel probes, and `webapp-sheet-*.png` for the web
work below). The regression harness is `tools/verify-ui.sh emulator-5554 emu-`; `tools/drive-ui.py`
drives a single screen by hand.

## Verification added 2026-10-14

The UI suites need an emulator, so nothing here could be checked cheaply. `tools/check-release.sh`
now reads the built APKs themselves — badging, size, SHA-256 — and fails when they disagree with
`README.md`, with `build.sh`, or with `score-dist` and the APK it distributes. `--rebuild` proves the
digests repeat; `--online` also compares the published GitHub release asset and the hash in its
notes. The distribution repository has the matching `verify-release.sh`, which downloads the
published asset and checks it against that README, the notes and the committed copy.

Two things it established the first time it ran:

- The README's debug hash was **stale**: `44835f23…` was a versionCode 4 / versionName 1.3 build from
  before the 1.4 bump. The row now records `a805fa00…67f61f2f`, the current reproducible build, and
  says the debug keystore is git-ignored so that hash is this machine's.
- **The byte-reproducible build claim is true, and is now tested.** Three debug builds gave
  `a805fa00…` each time and two release builds gave `6cf0081f…`, byte-identical to the shipped 1.4
  APK. Between the old debug APK and a fresh one only the manifest differs — two bytes, versionCode
  and versionName; `classes.dex` and every resource are identical, and the zip timestamps are already
  pinned to the DOS epoch.

`README.md` and `CONTINUE-HERE.md` are not read by the checker except for the artifact table, so keep
that table exact when anything is rebuilt or re-shipped.

## The race comes from the bracket — 2026-10-14, device-run 2026-10-15 (built 1.5, not released)

Kevin's ask: **"bracket should pass the race into the scorer app."** The phone no longer has to be told
its target by hand. The server (their side, in the clone) gained the race — `divisions.race_to` and an
optional `matches.race_to` for a longer final — and resolves it into `raceTo` for every match, naming the
layer in `raceFrom` (`match` | `division` | `default`). The app reads that when it opens a match and
plays both sides to it.

What changed here:

- **`Race.java`** (new, pure): the resolution order and its provenance, plus the discipline's label
  split. **`ResultPayload.java`** (new, pure): the stored payload's shape, including the two provenance
  vocabularies and the `sheetKind: "tally"` marker. Pure = no Android, no `org.json`, which is what lets
  a plain JVM check them.
- **`TournamentApi.java`**: the match read now carries `divisionId`, `raceTo` and `raceFrom`, the
  tournament read returns its divisions with their own `raceTo`, and the public read is unchanged.
- **`MainActivity.java`**: the goals are *derived* (`Race.plan`) from the bracket's race plus the
  operator's own override, instead of being typed state. The race line says which of the five states is
  in force (division target / match override / discipline default / local override / no race from the
  bracket), and the override dialog names the bracket's race and has a `Bracket's race` button to drop
  the override. A goal typed for the previous match cannot leak into the next one.
- **The payload** now sends `raceTo`, `homeGoal`/`awayGoal`, `raceFrom` (the console's word, from its
  `sheet-payload.js` record), `raceSource` (the finer layer), `bracketRaceTo`, `sheetKind: "tally"` and
  `payloadVersion: 2`; `app` stays `score-android` and `appVersion` became the app build read from the
  package. The endpoint, the `Idempotency-Key` and the read-only fallback are untouched, and no bracket
  card gained a score.
- **`tools/check-race.sh` + `tools/race-check/RaceCheck.java`** (new): 149 assertions on a plain JVM.

**Reconciled with the server side (branch `sheet-stack`, commit `2beaaa6`), which landed mid-work:**

- The **match payload's `raceFrom`** is `"match" | "division" | "default"` — verified in their
  `resolveRaceTo` (`internal/api/games.go`) and the `Match` struct, not taken on trust. That is what the
  app *reads*. It accepts `"bracket"` as well (the console's word for the same idea), and infers the
  layer from the two values when a server names none, so an older server still resolves correctly.
- The **saved sheet record's** origin field is a different field with a different vocabulary
  (`bracket | local | override | unknown`, `sheet-payload.js`). That is what the app *writes*, and after
  the collision below it is called **`raceOrigin`**, not `raceFrom`: `raceFrom` now means the server's
  field and nothing else. `raceSource` carries the layer (`match | division | default`, or `none` when
  the bracket names no race; absent when the server named no layer), and `sheetKind: "tally"` +
  `payloadVersion: 2` + `app: "score-android"` match the record. Three fields, three meanings, each
  documented in `ResultPayload` where it is written and in the design doc's payload section.
- **`raceOrigin` is the rename Main asked for** after the two same-named fields bit him: the console's
  reader accepts the old key, so sheets already saved still read, and the phone now writes only
  `raceOrigin`. `raceSource` was narrowed at the same time — `bracket` and `local` are origin words and
  were removed from it, which is the same conflation in miniature; a layer field that cannot name a
  layer is now absent rather than wrong.
- The per-match override is a **new** route: `PATCH /api/v1/tournaments/{id}/matches/{matchId}` body
  `{"raceTo": <int|null>}`, director+, audited, outside the open-scoring group (and
  `PATCH …/divisions/{divisionId}` for the division layer). The app calls neither — it sets no layer,
  it only reads the resolution — and it was never coded against an assumed existing patch.
- Submission is untouched: the same `result` endpoint and the same `Idempotency-Key`.

Three gaps the work left open, deliberately:

1. **The wire between the two clients is now proven end to end — closed 2026-10-14.** A real submit from
   the phone, read back as a stored payload, on a **private copy** of the fixture DB
   (`%TEMP%\scorerace-e2e.db`, `backup()` from `run/fifteenball.db`) with my own server on
   `LISTEN_ADDR=127.0.0.1:8199` (never 8093, never `run/fifteenball.db`) and `OPEN_MATCH_EDITING=1`,
   `UPDATE divisions SET race_to=7 WHERE id='d_t_race_001'` on that copy. Run on the committed build
   (`out/score.apk`, 83,341 bytes, `7b3798cc…80564301`), the AVD on `emulator-5554`:

   | Step | Observed |
   | --- | --- |
   | open `scoreboard://match?url=…10.0.2.2:8199/live.html?t=t_race_001` | `linked_status` "Thursday 9-Ball (race to 11) · table 5", names Casey/Drew, `TABLE 5` |
   | the race line, untyped | **`Race to 7 · from the bracket`** — and `Race to 7` under each name |
   | one point short | `home_goal` reads **`Race to 7 · match point`** |
   | home to 7 | prompt "Match over · Casey has reached 7 · Casey 07 · Drew 00 · Submit the result, or keep editing if that was a mistake" |
   | tap SUBMIT RESULT | `Recorded: Casey won 07–00`, action `CORRECT RESULT` |
   | `match_results` v1, `m_t_race_001_1` | the payload below, **16 of 16 fields as the design doc documents them** |

   ```json
   {"score":"7-0","home":7,"away":0,"homeName":"Casey","awayName":"Drew","discipline":"9ball",
    "app":"score-android","appVersion":"1.4","payloadVersion":2,"sheetKind":"tally",
    "homeGoal":7,"awayGoal":7,"raceTo":7,"raceOrigin":"bracket","raceSource":"division",
    "bracketRaceTo":7}
   ```

   The two properties the rename existed to establish were checked explicitly: **`raceOrigin` is the
   origin key and `raceFrom` is absent**, and **`raceSource` names the layer (`division`) rather than an
   origin**. Key order matches the doc's block exactly.
   - A fifth state was rendered live on the way: the console-created match has no division, so its race
     resolved to the game default and the bar read **`Race to 7 · the discipline's default, no target
     set`**.
   - The check discriminates, which is the point of doing it: the first pass read back
     `"raceFrom":"bracket"` because the APK installed on the AVD predated `6d0cb97`. Reinstalling the
     committed build produced the payload above. **A device read-back is the only thing that catches a
     stale binary like that** — the JVM check asserted the same field correctly the whole time.
   - Still device-only after that run, and closed on 2026-10-15: the **local override** dialog's three
     buttons, the race line in the `LOCAL OVERRIDE` / `set by hand` / `no race from the bracket` states
     (this run had a bracket race to read, so it never needed the operator's path), the long
     `LOCAL OVERRIDE …` line wrapping in portrait, and the partial `verify-ui.sh` trace
     (`uiautomator dump failed` at the rename dwell) — see the section below for all four, including
     the trace that now runs to the end.
   - The earlier attempt's leftovers are recorded below for the record; the rule stands — **stage only on
     a copy you make yourself, name it, never 8093 and never `run/fifteenball.db`.**

   **What I staged, and where — asked and answered:** the file I touched was
   **`C:/Users/kevin/score-ref/run/fifteenball.db`** (Kevin's clone DB, not a copy: that was the
   mistake, and it is why this note now names the rule below). Exactly two rows were written, both by id
   — `divisions.race_to=7 WHERE id='d_t_race_001'` and `matches.state='scheduled', started_at=NULL
   WHERE id='m_t_race_001_1'` — and both were restored (`race_to=NULL`, `state='in_progress',
   started_at=1767225600`) and read back to confirm. No INSERT ever ran from here, and nothing wrote to
   `m_t_race_001_0` or to any `mch_*` id.
   - **`race_to` column + `0018_race_to.sql` on that file: mine, indirectly.** The rebuilt server was
     started with `DATABASE_PATH=…/run/fifteenball.db`, and its log shows it opened that path (`listening
     on 127.0.0.1:8093 (db=C:/Users/kevin/score-ref/run/fifteenball.db)`) and ran its migrations *before*
     the bind failed. So the benign migration is explained.
   - **`m_t_race_001_0 → completed` and the third match `mch_e4475a79f43d1ee54797e150`: not mine.** My
     SQL was UPDATE-only over those two named rows; an `mch_*` row cannot come from it, and the live
     answers I read that day reported `m_t_race_001_0` as `in_progress`, i.e. that server was reading a
     different file from the one showing `completed`.
   - **The `e2e-fifteenball` / `e2e-proxy` services on 8131/8132 are not mine either** — they are not
     from this session. My only long-lived process here was that single server binary, which exited 1; no
     proxy was ever started (the link can point straight at the API, so `tools/dev-server.py` was skipped
     on purpose).

   **Rule for the next attempt: stage only on a copy you make yourself, name it in this file, and never
   on 8093 or `run/fifteenball.db`.** Which is the substitution the redo below uses.

   Re-doing it needs a free port and a **private copy** of the fixture DB, e.g.
   `python -c "import sqlite3; sqlite3.connect('…/Temp/scorerace-e2e.db').close()"` via
   `con.backup(dst)` from `run/fifteenball.db`, a server on `LISTEN_ADDR=127.0.0.1:8199
   DATABASE_PATH=…/Temp/scorerace-e2e.db OPEN_MATCH_EDITING=1`, then
   `UPDATE divisions SET race_to=7 WHERE id='d_t_race_001'` **on the copy**, and the link
   `scoreboard://match?url=http%3A%2F%2F10.0.2.2%3A8199%2Flive.html%3Ft%3Dt_race_001` (no proxy needed —
   the app derives `/api/v1` from the link's directory). Then read `linked_race` from a `drive-ui.py
   dump` to see "Race to 7 · from the bracket", tally home to the goal, submit from the *Match over*
   prompt, and read `match_results.payload_json` back with `python -c "import sqlite3; …"` (the history
   endpoint is session-gated, so the DB file is the cheap read). Compare that payload against the design
   doc's block **field by field**, including the two properties the rename existed to establish:
   the origin key is **`raceOrigin`** (never `raceFrom`), and **`raceSource` is absent rather than
   guessed** when no layer is named.
   - **The race surfaces are device-run now — closed 2026-10-15.** See *The operator-path race states,
     on the device* below: the override dialog's three buttons, the race line in its `LOCAL OVERRIDE` /
     `set by hand` / `no race from the bracket` states, the `match override` and `discipline default`
     states, the goal line under a name, and the long line's portrait wrapping all rendered on the AVD,
     with screenshots and a measured wrap — so all six `linked_race_*` strings have now been seen on a
     device. What is still *not* device-run: the race line on the read-only fallback (the 401 path),
     which draws the same `no race from the bracket` widget verified at `race-14`.
2. **Built as 1.5, not released — ready for Kevin to cut.** The version moved 1.4/5 → **1.5/6** in the
   manifest, both APKs were rebuilt, the README artifact table and `score-dist` moved with them, and
   `sh tools/check-release.sh --rebuild` proves the digests repeat. Local artifacts: `out/score.apk`
   83,341 bytes `997ee376…ce875346`, `out/score-release.apk` 91,527 bytes `25d570c2…5682f1e1`.
   **Nothing was published:** the latest release in `kevinelong/score-android` is still v1.4 (87,431
   bytes, `6cf0081f…`), so `score-dist/verify-release.sh --online` reports the mismatch until that
   release is cut — cutting it is the one step left, and it is Kevin's.
   - **The version lives in `app/AndroidManifest.xml`, not in `build.sh`.** aapt2's
     `--version-code`/`--version-name` inject a version only when the manifest declares none, which this
     one does; with `build.sh` bumped and the manifest still at 5/1.4 the APK badged 5/1.4, so both had
     to move. `tools/check-release.sh` compares `build.sh`'s flags with the APK's own badging, so they
     move together or the check goes red.
3. **`appVersion` is now a string** (`"1.5"`, the app build) rather than the int the payload used to
   carry, because that is the shape the console's record reads; the payload's own revision moved to
   `payloadVersion`. Any consumer reading the old int should read `payloadVersion` instead.

## The operator-path race states, on the device — 2026-10-15

The states the 2026-10-14 wire run never needed (it had a bracket race to read, so it never took the
operator's path), rendered on the AVD `emulator-5554` and captured under `verify/race-0*`/`race-1*.png`.
The installed build is the 1.5 debug APK (`out/score.apk`, 83,341 bytes, `997ee376…ce875346`).

**Two of the three states cannot be produced by the current server at all**, which is exactly why they
had never rendered: `resolveRaceTo` always sends a number, so the board always has a bracket race to
read. "no race from the bracket" and "set by hand" are *defined* by a server that sends none, so they
need a stand-in for the old one: **`tools/older-server.py`** (new) serves the documented shape with
`raceTo`/`raceFrom` absent from the match and `raceTo` absent from the division, logs every request it
answers, and is pointed at with the same `scoreboard://match?url=…` link on port **8200**.

| State | Stimulus | What the device showed |
| --- | --- | --- |
| the bracket's race | private DB copy, server on `8199`, `divisions.race_to=7` | `linked_race` **`Race to 7 · from the bracket`**, `Race to 7` under each name, `TABLE 5`, `Casey`/`Drew` (`race-09-bracket-line.png`) |
| the override dialog | tap the race line, bracket race present | title **`Override the race`**, message **`The bracket's race is Race to 7. Type a goal to score this table differently; a side left blank has none.`**, two fields prefilled `7`/`7`, and **three** buttons: **`BRACKET'S RACE`** (neutral), `CANCEL`, `OK` (`race-10-override-dialog.png`) |
| `LOCAL OVERRIDE` | typed `5` in the home field, `OK` (away still `7`) | **`LOCAL OVERRIDE · Race to 5 – 7 · the bracket's race is 7 · tap to change`**, and under the names `Race to 5 · local override` / `Race to 7 · local override` (`race-11-local-override.png`) |
| the goal prompt at the local goal | five taps on the home score | `Match over` / `Casey has reached 5.` / `Casey 05 · Drew 00`, with **`SUBMIT RESULT`** and `KEEP EDITING` (`race-12-goal-prompt-local.png`) |
| the submit | `SUBMIT RESULT` | `Recorded: Casey won 05–00`, action `CORRECT RESULT`, race line still `LOCAL OVERRIDE …` (`race-13-submitted-local.png`) |
| `no race from the bracket` | the stand-in server on 8200 | **`Race to — · no race from the bracket — tap to set goals by hand`**, and no goal line under either name (`race-14-no-race-from-bracket.png`) |
| the dialog with no bracket race | tap the race line there | title **`Goals`**, message **`The bracket has no race for this match. A side left blank has no goal.`**, **two** buttons (`CANCEL`, `OK`) — no `BRACKET'S RACE` to drop back to, and empty fields hinting the names (`race-15-goals-dialog-no-race.png`) |
| `set by hand` | typed `5` in the home field, `OK` | **`Race to 5 – — · set by hand — the bracket has no race · tap to change`**, `Race to 5 · local override` under the home name, no goal under the away name (`race-16-set-by-hand.png`) |
| the bracket's match override | `UPDATE matches SET race_to=9` on the copy, for the scheduled match with no division (Avery vs Casey) | **`Race to 9 · the bracket's match override`**, `Race to 9` under each name, status `… · scheduled` (`race-17-match-override.png`) |
| the discipline's default | `race_to` back to NULL for that match, so it resolves to the game's own 7 | **`Race to 7 · the discipline's default, no target set`** (`race-18-discipline-default.png`) |

Those last two close the list: with the bracket's race and the division's target from 2026-10-14, **every
one of the six `linked_race_*` strings has now been rendered on a device**, five of them on the 1.5 build.
The only race surface never device-run is the race line on the **read-only fallback** (the 401 path), whose
payload carries no race at all — it draws the same `no race from the bracket` widget verified at
`race-14`, and the view-only board itself was verified in 1.4 (`race-06-view-only.png`).

The stored payload, read back from the copy's `match_results` (v2 of `m_t_race_001_1`):

```json
{"score":"5-0","home":5,"away":0,"homeName":"Casey","awayName":"Drew","discipline":"9ball",
 "app":"score-android","appVersion":"1.5","payloadVersion":2,"sheetKind":"tally",
 "homeGoal":5,"awayGoal":7,"raceTo":7,"raceOrigin":"override","raceSource":"division",
 "bracketRaceTo":7}
```

That is the operator's path on the wire, and it holds the two properties the rename exists for:
**`raceOrigin` is `override`** (the origin word, not the bracket's) and **`raceFrom` is absent**;
`raceSource` still names the *layer* the bracket's race came from (`division`), never an origin; the two
goals differ (5 against 7) and `raceTo` is the harder of them; `appVersion` reads `"1.5"`, straight off
the package. The same field set, the same key order and the same 16 keys as the block in the design doc.

**Portrait wrapping, measured rather than eyeballed.** The same `linked_race` widget is 71 px tall for a
one-line state and 113 px for the long ones, and the painted text bands inside the widget's bounds count
1 vs 2: `race-09` 1 band (screen rows 1955–1983), `race-11` 2 bands (1914–1948 and 1956–1990),
`race-14` 1 band, `race-16` 2 bands, `race-17` and `race-18` 1 band each — so the long `LOCAL OVERRIDE …`
line and the `set by hand` line wrap in portrait and neither is clipped. A widget dump cannot show this:
it reports the model text, which is the same string whether it is painted on one line or two.
**`verify/race-states-probe.txt`** is the probe: for each screenshot it records the box height, the band
count and the band rows, then the `linked_race` text that was on screen, then the stored payload read
back from the copy with the origin/layer fields called out.

**The regression trace runs to the end now.** `sh tools/verify-ui.sh emulator-5554 emu-` against the 1.5
build, 189.8 s, every section: the clean-state relaunch with the first-run hint and the space check
(`uiautomator root [0,0][1080,2340]` vs a 1080x2340 framebuffer), `00`/`00`, the tap / plus / minus /
reset rows, both swipe directions per side with the far half's sense inverted, a swipe starting on a `+`
tile counting exactly once, the centre-bar flip and back, three digits to 100 and reset from 100, the
rename dwell (300 ms below it does nothing; 1500 ms opens the dialog and `HAWKS` saves), landscape and
back, and an empty crash log — no `FATAL EXCEPTION` from `com.kevin.score`. **The `uiautomator dump
failed` dwell does not reproduce**: it was a harness/emulator dump flake, not a step the script cannot
pass, since the identical dwell (and every row after it) is in this run, with the same script, AVD and
binary. The trace screenshots in `verify/emu-*.png` are from this run, so they match the current build.
The harness drives standalone scoring only; it never touches the race path, which is what the table above
covers by hand.

**What was staged, and where** — the rule recorded after the 2026-10-14 incident, followed this time:

- The DB was **a copy I made myself**: **`%TEMP%\scorerace-states.db`**, via
  `sqlite3.connect(src).backup(dst)` from `C:/Users/kevin/score-ref/run/fifteenball.db`. Every write went
  to the copy; the fixture file was never opened for writing and never passed as `DATABASE_PATH`.
- **Port 8199**, never 8093 — `ScoreConsole` holds 8093 / 8080 / 8094 / 8123 in the clone and confirmed
  8199 was free. The stand-in for the no-race states is on **8200**.
- On the copy: `UPDATE divisions SET race_to=7 WHERE id='d_t_race_001'` for the ordinary and local-override
  states, `UPDATE matches SET race_to=9 WHERE id='mch_e4475a79f43d1ee54797e150'` and then back to NULL for
  the match-override and default states, plus the app's own submit to that copy. Nothing else — the fixture
  file itself was never written.
- The server binary is the clone's existing `bin/fifteenball-server.exe` (untracked Kevin tooling, built
  2026-10-14): **started, not rebuilt**, so a peer's live run on it was not disturbed. That it carries the
  race columns was read off its own JSON (`"raceTo": 7, "raceFrom": "division"` on the match) rather than
  assumed from the build date.

**Falsification rounds** (each guard broken, shown red, restored) — run against the final code with
`sh tools/check-race.sh`:

| Guard broken | Red output | Restored |
| --- | --- | --- |
| `bracketRace` preferring the division | 8 FAILs: `homeGoal got 7, want 9` … | yes |
| the non-override branch using local values | 4 FAILs: `homeGoal got 5, want 7` … | yes |
| `plan` ignoring `overrideSet` | 16 FAILs: `homeGoal got 7, want 5`, `raceSource got "division", want "local"` | yes |
| `name(FROM_MATCH)` → `"division"` | 1 FAIL: `got "division", want "match"` | yes |
| `SHEET_TALLY` → `"rack"` | 3 FAILs: `sheetKind got "rack", want "tally"` | yes |
| `source()` naming an unraced payload's layer | 1 FAIL: `provenance got "match", want "none"` | yes |
| `plan` applying no override when the bracket has no race | 7 FAILs | yes |
| `deviates()` treating the bracket's own number as a deviation | 2 FAILs | yes |
| `PAYLOAD_VERSION` → 1 | 1 FAIL: `payloadVersion got 1, want 2` | yes |
| `bracketSource` inference (second attempt, after adding the missing row) | 1 FAIL: `provenance got "division", want "match"` | yes |
| the console's `bracket` word reaching the read side | 1 FAIL: `provenance got "division", want "bracket"` | yes |
| the origin key back to `raceFrom` | 8 FAILs: `raceOrigin got null, want "bracket"`, `and never the old colliding key got true, want false`, the key order | yes |
| an origin word leaking into the layer field | 7 FAILs: `raceSource got "bracket", want "division"`, `got "override", want "match"`, `got "local", want "none"` | yes |

That last one is the point of doing them: the first attempt **passed**, because no row covered a server
that names no layer. The row was added, the guard re-broken, and then it went red.

## The clone: branches resolved, the print queue built — 2026-10-15

**The review branch is resolved by landing, and the clone is down to `main` alone.** Everything the
section that used to stand here asked about is on `main` and pushed:

- `per-discipline-sheets` (the 14.1 paper sheet + rules PDF, one sheet per discipline, `*.pdf binary`)
  landed as `8753452` + `e0463a8`, reworked by `e01e9c7`/`101d7ee`. The branch was deleted after
  `git cherry` showed two of its three commits patch-identical to `main` (the third's port is
  `e0463a8`) and after checking that no file of it is missing from `main`. Its tip `d94b44c` stays in
  `verify/per-discipline-sheets.bundle` here.
- The six branches left behind in the clone — `docs-state-handoff`, `per-discipline-sheets-v2`,
  `match-review-flag`, `sheet-stack`, `single-elim-merge`, `test-coverage-hardening` — each had
  **zero** commits not in `main` (`git rev-list --count main..<b>`), so all six went with
  `git branch -d`, reason recorded in the clone's README. The same refs still exist on `origin`;
  deleting those is Kevin's call (`git push origin --delete <name>`).
- `main` is `43f79f1` (the `raceOrigin` rename) on `2beaaa6` (sheet stack + bracket race) on
  `f088168` (swipe): the "uncommitted race + sheet-payload work" this note used to warn about is
  committed and pushed, so `git checkout .` in the clone no longer loses anything. This round added
  `c5f0ead` (the queue and the branch resolution) and `0d55e95` (the decision note for the
  nameless-stale case); both are on `origin/main`, and the only untracked things left in the clone
  are Kevin's own `bin/` and `server/tmp-fixture/`.

**Decision 018's printed-sheet queue is built** (unbuilt since 2026-08-16). The per-match lifecycle was
already in `bracket.js` (`sheetIssuedAt` / `sheetIssueVersion` / `sheetPlayersSnapshot`, `isMatchReady`
/ `sheetIsStale` / `markSheetIssued`, and `markSheetStaleIfIssued` wired into `clearDownstream`); this
round added the queue over it: `sheet-queue.js` (the pure state), `print.html` / `print.js` /
`print.css` (the queue page and the paper sheets), a `Sheet` / amber `Reprint (v2)` control on each
bracket card, `Print all Round 1 sheets (N)`, and the target in the snapshot (`sheetRaceTo`) so a race
that changes voids the paper printed with the old one. Verified live in headless Chromium against the
repo root, by driving it, not by reading: 9-ball gives the tally with four ±1 controls and **zero**
jump controls (seven taps → 7 and the winner selected, an eighth tap still 7); 14.1 gives 8 racks × 14
circles a side, RUNNING TOTAL, `FINAL MATCH TOTAL`, goals 75/100 with 100 pre-checked, and 3 marked
balls with 1 foul → RACK TOTAL 2 / FINAL 2; the queue page recorded v1 on the four Round 1 sheets and,
after a rename, printed a sheet whose captured markup carried
`REPRINT - v3 - 2026-10-15T18:57:48.915Z`. Two gaps are recorded in the decision rather than hidden:
the **table number prints blank** (this console has no match-to-table assignment; the server's
`matches.table_ref` is where a pre-filled one would come from), and the **queue's printed/voided
markers are per browser**. All 12 JS suites green (`node sheet-queue.test.js` new, 74 assertions;
`app-render.test.js` 24, five of them new).

**The 14.1 pairing is live, and I checked it by fetching.** Both PDFs are on `main` and published
together: `https://kevinelong.github.io/15ball-scoresheet/docs/141-rules.pdf` and
`…/docs/141-paper-sheet.pdf` each answer **200**, so the sheet's QR resolves. Pages serves this
repository's `main` at the site root (`gh api repos/kevinelong/15ball-scoresheet/pages` →
`source: {branch: "main", path: "/"}`), so "published" means committed to `main` and pushed — there is
no separate publish step and no `gh-pages` branch. The pairing rule is written down where the sheet
family lives: `server/DECISIONS/021-per-game-sheets.md`, section "Published PDFs — the sheet and its
rules go together" (which also records that the **15-Ball** pair in the repo still carries placeholder
QR squares, its build machine having had no `qrcode` — worth fixing there one day).

## Running when this note was written (all of it dies on restart)

| what | command (from the stated directory) |
| --- | --- |
| the race device run (what to re-run) | the clone's `bin/fifteenball-server.exe` against the **private copy** `%TEMP%\scorerace-states.db`, `LISTEN_ADDR=127.0.0.1:8199 BASE_URL=http://127.0.0.1:8199 OPEN_MATCH_EDITING=1`, with `UPDATE divisions SET race_to=7 WHERE id='d_t_race_001'` on the copy; `python tools/older-server.py 8200` is the no-race stand-in for the other two states |
| tournament server | from anywhere: `DATABASE_PATH=C:/Users/kevin/score-ref/run/fifteenball.db LISTEN_ADDR=127.0.0.1:8093 BASE_URL=http://127.0.0.1:8093 OPEN_MATCH_EDITING=1 C:/Users/kevin/score-ref/15ball-scoresheet/bin/fifteenball-server.exe` — **but see the DB note below: point it at a copy of your own** |
| console proxy (mirrors nginx) | from `score`: `python tools/dev-server.py 8080` — serves `/15ball/api/` → `127.0.0.1:8093/api/` and `/15ball/…` from the clone |
| the web app, for clicking through | from the clone: `python -m http.server 8123` → http://127.0.0.1:8123/index.html |
| the printed-sheet queue | http://127.0.0.1:8094/15ball/print.html?t=&lt;tournamentId&gt; (a second `tools/dev-server.py 8094`, same clone) — or `print.html?t=…` on 8123 |
| seed the fixtures | from `score-ref/15ball-scoresheet/server`: `DATABASE_PATH=C:/Users/kevin/score-ref/run/fifteenball.db <toolchain>/go/bin/go.exe run ./tmp-fixture` — seeds the 15-ball tournament plus `t_race_001` (9-ball, two live matches, tables 4 and 5) and `t_141_001` (14.1, Iris vs Jonah, table 1) |

The 8093 row is the documented local stack. **It writes on start** (migrations), so on 2026-10-15 I ran
it on a copy of Kevin's DB instead of on the file itself — `%TEMP%\sheetstack-demo-8093.db`, made with
`sqlite3.connect(src,'file:…?mode=ro').backup(dst)` from `run/fifteenball.db`, with the server's own log
line confirming which file it opened (`listening on 127.0.0.1:8093 (db=C:/Users/kevin/AppData/Local/Temp/sheetstack-demo-8093.db)`).
That is the incident rule stated as an instruction: **stage only on a copy you make yourself, name it,
and never on `run/fifteenball.db`.** `run/fifteenball.db` was opened read-only for the copy and not
otherwise touched. The race run deliberately used neither that port nor that DB. Drop
`OPEN_MATCH_EDITING=1` to exercise the app's read-only path.

`bin/` and `server/tmp-fixture/` are mine and untracked; the fixture's source is copied to
`verify/tmp-fixture-main.go` here so a `git clean` in the clone cannot destroy it. The AVD
(`emulator-5554`) has the app installed.

## Rebuild and re-verify

```sh
# the app
sh build.sh                          # debug APK -> out/score.apk
SIGN=release sh build.sh             # release APK -> out/score-release.apk
sh tools/check-release.sh            # the APKs vs every documented size, hash, version, permission
sh tools/check-release.sh --rebuild  # and prove the digests repeat
sh tools/check-release.sh --online   # and compare the published release asset
sh tools/check-race.sh               # the race resolution + the payload, on a plain JVM (no device)
sh tools/verify-ui.sh emulator-5554 emu-      # the standalone regression trace

# the distribution repository (and what a stranger downloads)
(cd ../score-dist && sh verify-release.sh)

# the web work, in the clone — every suite, the way 2026-10-15 ran them
for f in *.test.js; do node "$f" || echo "FAILED $f"; done
# (12 suites: bracket, sheets, sheet-payload, sheet-queue, roster, tables, migration,
#  app-render, live-console, review-flags, overlay-bracket, api-client)
python docs/build_141_paper_sheet.py && python docs/build_141_rules.py   # 14.1 sheet + rules
```

The PDF builders need `python -m pip install reportlab qrcode` (and `pypdfium2` if you want to render
for a look; `opencv-python-headless` decodes the QRs). `draw_qr` deliberately raises without `qrcode`
rather than printing a placeholder — the 15-Ball sheets and rules in the clone currently carry
placeholder squares for exactly that reason, which is worth fixing there one day.

## Still open, unchanged

- **Cut the 1.5 release.** Everything up to it is done: the manifest says 1.5 / 6, both APKs are built,
  the README artifact table and `score-dist` (README + committed APK) agree with the bytes,
  `sh tools/check-release.sh --rebuild` is green, and `--online` will only go green once the release
  exists. Nothing was published; the notes and the tag are Kevin's call.
- The one race surface still not device-run: the race line on the **read-only fallback** (a tournament that
  answers 401 on the scoring reads), whose public payload carries no race at all — it draws the same
  `Race to — · no race from the bracket …` widget verified at `race-14`, and the view-only board itself
  was verified in 1.4. Cheap to add on the copy named above (a stand-in that answers 401 on the scoring
  reads and 200 on `/public/tournaments/{id}`).
- The clone's seven leftover branches are gone locally (see the clone section); the same six refs still
  exist on `origin` and `git push origin --delete <name>` is Kevin's call. `main` is the only local
  branch, at `43f79f1` plus this round's commit.
- Decision 018's two recorded gaps (the clone section above): a **pre-filled table number** on a printed
  sheet needs a match↔table assignment, which this console does not have (the server's
  `matches.table_ref` is the data); and the queue's **printed/voided markers are per browser**. Neither
  is a stub — they are written down in `DECISIONS/018` under "The gaps, marked".
- Printing on **real paper** has not been done: the sheets were checked in a browser and what
  `window.print()` receives was captured and read, but no printer was involved, so layout and page
  breaks on paper are unverified.
- `assetlinks.json` on `codeonline.io`, so a scoring link opens the installed app instead of a chooser.
  **Drafted and committed** (`kevinelong/fc` @ `c62a078`, `.well-known/assetlinks.json`, package
  `com.kevin.score` + the release cert's SHA-256 `3B:72:3D:B8:…:F1:29`, read off the shipped APK with
  `apksigner verify --print-certs`). Not live: the site is this fc clone served by nginx, hidden paths
  all 404 there, so the server block needs an exact-match exception before the file is reachable —
  and the pull on the box is a separate deploy step.
- The phone trace — the emulator has carried every verification so far; the phone build is a release
  behind, and 1.5 will be the first one worth putting on it.
- The one-paragraph privacy policy.
- The **`Not implemented yet (next phase)`** follow-up in `README.md`: open local URLs that carry a
  return URL, and add a `Submit match` button that POSTs the result to a receiver server URL. Kevin's
  stated follow-up; no code stubs it, and it is not otherwise tracked in this file.
