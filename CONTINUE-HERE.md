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
