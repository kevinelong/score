# Score — state for a restart

Written 2026-10-12, last updated 2026-10-14, so nothing is lost across a restart. Two repositories:

- **`C:/Users/kevin/score`** — the Android scoreboard app (public: `kevinelong/score`,
  release repo `kevinelong/score-android`).
- **`C:/Users/kevin/score-ref/15ball-scoresheet`** — a working clone of the tournament server and web
  console (`kevinelong/15ball-scoresheet`). Contains uncommitted-until-now work; see below.

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

## The race comes from the bracket — 2026-10-14 (not shipped, not device-run)

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
- **`tools/check-race.sh` + `tools/race-check/RaceCheck.java`** (new): 143 assertions on a plain JVM.

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
   - Still device-only, and now the only ones left: the **local override** dialog's three buttons and the
     race line in the `LOCAL OVERRIDE` / `set by hand` / `no race from the bracket` states (this run had a
     bracket race to read, so it never needed the operator's path), and the long `LOCAL OVERRIDE …` line
     wrapping in portrait, plus the partial `verify-ui.sh` trace (`uiautomator dump failed` at the rename
     dwell).
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
   - **No full device run of the race surfaces.** `tools/verify-ui.sh emulator-5554 emu2-` **was runnable**
   and traced this build: every row up to the rename section matched the documented 1.4 trace, then it
   stopped with `uiautomator dump failed` at the rename dwell (a harness/emulator dump failure, not an app
   assertion), so that trace is partial — and it drives standalone scoring only, never the race. What only
   a device can confirm: the race line's wording in each of the five states, the override dialog's three
   buttons, the goal line under a name, and the long `LOCAL OVERRIDE …` line wrapping in portrait.
2. **Not shipped.** The version is still 1.4 / versionCode 5 and only the debug APK was rebuilt
   (`out/score.apk` 83,341 bytes, `7b3798cc…80564301`); `out/score-release.apk` is left as the shipped
   1.4 bytes so `tools/check-release.sh` still agrees with `score-dist`. Shipping this needs the bump
   (1.5 / 6), both APKs rebuilt, the README table and `score-dist` updated together, and a release cut.
3. **`appVersion` is now a string** (`"1.4"`, the app build) rather than the int the payload used to
   carry, because that is the shape the console's record reads; the payload's own revision moved to
   `payloadVersion`. Any consumer reading the old int should read `payloadVersion` instead.

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

## Waiting for review — a local branch in the clone (nothing pushed)

Branch **`per-discipline-sheets`** in `score-ref/15ball-scoresheet`, three commits:

1. **14.1 paper sheet + rules PDF** — `docs/_scoresheet_141_draw.py`,
   `docs/build_141_paper_sheet.py`, `docs/build_141_rules.py`, `docs/141-paper-sheet.pdf`,
   `docs/141-rules.pdf`.
2. **One score sheet per discipline** — `sheets.js`, `sheets.test.js`, changes to `app.js`,
   `index.html`, `styles.css`, and `server/DECISIONS/021-per-game-sheets.md`.
3. **Mark `*.pdf` binary** — a `.gitattributes` entry, so a text-classified PDF cannot gain CRLF on
   checkout under `core.autocrlf` and break its xref offsets.

Why it matters: clicking a bracket match used to open the paper-style sheet only for 15-Ball
Rotation; every other discipline fell through to a generic games/points counter. Now the sheet is
chosen by the tournament's game — 14.1 gets 14 balls × 8 racks with a RUNNING TOTAL and 75/100 goals,
the games-won disciplines get a tally inside the same sheet chrome.

The decision from here is one command either way, in the clone:
publish — `git push -u origin per-discipline-sheets` (then the sheet and the rules PDF must go to
GitHub Pages together, per the note below); discard — `git branch -D per-discipline-sheets`. `git
checkout main` leaves the branch alone. Nothing was pushed, so nothing is live. The branch tip is
`d94b44c` on top of `main` at `e3fa51f`.

That clone is the only copy of those three commits, so they are also saved as a bundle inside this
repository: **`verify/per-discipline-sheets.bundle`** (`git bundle verify` lists `main` at `e3fa51f`
and `per-discipline-sheets` at `d94b44c`). Recover with
`git fetch <this-repo>/verify/per-discipline-sheets.bundle per-discipline-sheets`.

As of this note the clone's working tree also holds **uncommitted race + sheet-payload work**:
`server/internal/api/divisions.go` and `matches.go` (the `race_to` columns, `raceTo`/`raceFrom`),
a new `server/internal/api/games.go` and `race_to_test.go`, new `sheet-payload.js` +
`sheet-payload.test.js` (the stored-sheet record this app's payload follows), `server/DECISIONS/023-`
and edits across `app.js`/`live.js`/`index.html`/`styles.css` and the implementation docs. None of it is
committed or pushed there, so `git checkout .` in that clone would lose it.

**If the 14.1 work is published, the sheet and the rules PDF go together**: the sheet's QR points at
`docs/141-rules.pdf`, which 404s until that file is on GitHub Pages.

Two things I did not touch: the printed-sheet queue in decision 018 is still unbuilt, and
`index.html`'s header links still point only at the 15-Ball sheets.

## Running when this note was written (all of it dies on restart)

| what | command (from the stated directory) |
| --- | --- |
| tournament server | from anywhere: `DATABASE_PATH=C:/Users/kevin/score-ref/run/fifteenball.db LISTEN_ADDR=127.0.0.1:8093 BASE_URL=http://127.0.0.1:8093 OPEN_MATCH_EDITING=1 C:/Users/kevin/score-ref/15ball-scoresheet/bin/fifteenball-server.exe` |
| console proxy (mirrors nginx) | from `score`: `python tools/dev-server.py 8080` — serves `/15ball/api/` → `127.0.0.1:8093/api/` |
| the web app, for clicking through | from the clone: `python -m http.server 8123` → http://127.0.0.1:8123/index.html |
| seed the fixtures | from `score-ref/15ball-scoresheet/server`: `DATABASE_PATH=C:/Users/kevin/score-ref/run/fifteenball.db <toolchain>/go/bin/go.exe run ./tmp-fixture` — seeds the 15-ball tournament plus `t_race_001` (9-ball, two live matches, tables 4 and 5) and `t_141_001` (14.1, Iris vs Jonah, table 1) |

Drop `OPEN_MATCH_EDITING=1` to exercise the app's read-only path.

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

# the web work, in the clone
node sheets.test.js && node bracket.test.js && node roster.test.js \
  && node tables.test.js && node migration.test.js
python docs/build_141_paper_sheet.py && python docs/build_141_rules.py   # 14.1 sheet + rules
```

The PDF builders need `python -m pip install reportlab qrcode` (and `pypdfium2` if you want to render
for a look; `opencv-python-headless` decodes the QRs). `draw_qr` deliberately raises without `qrcode`
rather than printing a placeholder — the 15-Ball sheets and rules in the clone currently carry
placeholder squares for exactly that reason, which is worth fixing there one day.

## Still open, unchanged

- Ship the race change: bump to 1.5 / versionCode 6, rebuild both APKs, update the README table and
  `score-dist` together, cut the release. See the section above for what is deliberately not shipped.
- Run the race on a device (or the emulator) against a server that has `race_to` — the only way to see
  the race line, the override dialog and a stored payload with its provenance.
- Publish or discard the branch above.
- `assetlinks.json` on `codeonline.io`, so a scoring link opens the installed app instead of a chooser.
  **Drafted and committed** (`kevinelong/fc` @ `c62a078`, `.well-known/assetlinks.json`, package
  `com.kevin.score` + the release cert's SHA-256 `3B:72:3D:B8:…:F1:29`, read off the shipped APK with
  `apksigner verify --print-certs`). Not live: the site is this fc clone served by nginx, hidden paths
  all 404 there, so the server block needs an exact-match exception before the file is reachable —
  and the pull on the box is a separate deploy step.
- The phone trace for 1.4 — the emulator has carried every verification so far; the phone build is a
  release behind.
- The one-paragraph privacy policy.
- The **`Not implemented yet (next phase)`** follow-up in `README.md`: open local URLs that carry a
  return URL, and add a `Submit match` button that POSTs the result to a receiver server URL. Kevin's
  stated follow-up; no code stubs it, and it is not otherwise tracked in this file.
