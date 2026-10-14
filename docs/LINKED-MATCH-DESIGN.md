# Linked match mode — design

The app gains a second way to be used. Today it is a standalone scoreboard: you open it, it knows
nothing, you type nothing, it scores two teams. In **linked match mode** you open a *link* — the same
scoring link the tournament director already shares as a QR code — and the app loads that match from
the live tournament server, scores it, and submits the result. If the app is not installed, the same
link opens the tournament's web console in the browser instead.

The server is **kevinelong/15ball-scoresheet** (Go + SQLite, nginx in front). Linked-match mode itself
required no change to it. The **race** is the one exception, and it is a change their side made: a
division's target and a per-match override are columns now (`divisions.race_to`, `matches.race_to`,
migration 0018), resolved into every match payload as `raceTo`/`raceFrom`. The app reads that instead of
asking the operator to type a race, which is what this document describes as of the version that landed
it.

Status: **implemented in the app and verified end to end** against a running server — open a link,
score the match, submit, correct — for the version *before* the race came from the bracket. The race
change is asserted on a plain JVM (`sh tools/check-race.sh`) and its APK is built and signature-verified;
reading a real bracket race on a device needs a server that has the new columns. See "Verified" below
for the split.

## A scoring link in action

```sh
# 1. the real server, with their seed fixtures and open scoring on
DATABASE_PATH=<dir>/fifteenball.db OPEN_MATCH_EDITING=1 toolchain/go/bin/go.exe \
  run ./cmd/fifteenball-server            # from the 15ball-scoresheet clone
# 2. the local mirror of their nginx mapping (web console + /15ball/api -> :8093)
python tools/dev-server.py 8080
# 3. the link, in the development form that needs no assetlinks.json
adb shell am start -a android.intent.action.VIEW \
  -d "scoreboard://match?url=http%3A%2F%2F10.0.2.2%3A8080%2F15ball%2Flive.html%3Ft%3Dt_live_001"
```

## The link

```
https://codeonline.io/15ball/live.html?t=<tournament-slug-or-id>
```

- `?t=` carries the tournament **slug or id** — `live.js:312-314`. It is *not* a token: there is no
  per-link credential anywhere in the system. What the link can do is decided by server config.
- With `OPEN_MATCH_EDITING=1` (their switch, off by default) the match board reads and the score
  actions are un-gated, so anyone holding the link can record results; tournament and entrant *setup*
  stays director-only. Their own runbook calls this "trusted-room convenience" and notes the tradeoff:
  anonymous results are stored with `submitted_by = NULL`, so there is no actor on them.
- Production host and prefix come from the server's `BASE_URL` (`https://codeonline.io/15ball`).
  Local development replicates it: a small proxy serves the web console at `/15ball/` and proxies
  `/15ball/api/` → `127.0.0.1:8093/api/`, mirroring the nginx mapping in their `DEPLOY.md`.

## The protocol the app speaks

All paths below are relative to the URL's origin and prefix (`https://codeonline.io/15ball`).
Everything here was executed against the real server during design work — see "Verified" below.

| # | Call | Notes |
| --- | --- | --- |
| 1 | `GET /api/v1/tournaments/{slugOrId}` | resolves the link; returns `{tournament, divisions}`. **Capture `tournament.id`** — everything after this uses the canonical id, because the link may carry a slug |
| 2 | `GET /api/v1/tournaments/{id}/entrants?archived=true` | `{items:[{id, displayName, state, ...}]}` — the two team names |
| 3 | `GET /api/v1/tournaments/{id}/matches` | `{items:[...]}` — only meaningful once the tournament is `in_progress`/`completed`; each match has `id, slot, state, entrantAId, entrantBId, winnerEntrantId, bracket, matchLabel, tableRef, version`, plus **`raceTo`** (the race resolved `matches.race_to → divisions.race_to → the game's default`, always a number) and **`raceFrom`** (`"match"` \| `"division"` \| `"default"`, the layer that supplied it) |
| 4 | `POST /api/v1/tournaments/{id}/matches/{mid}/assign` | body `{}`; needed only from `scheduled`. `409 invalid_transition` is tolerated (someone else started it) |
| 5 | `POST /api/v1/tournaments/{id}/matches/{mid}/start` | no body; from `assigned`/`reopened`. `409` tolerated for the same reason |
| 6 | `POST /api/v1/tournaments/{id}/matches/{mid}/result` | body `{winnerEntrantId, loserEntrantId, score}` — the one call that matters |
| 7 | `GET /api/v1/tournaments/{id}/matches` | re-read after submitting: the `match` object in the submit *response* does not carry `winnerEntrantId`, the list does |
| 8 | `POST .../matches/{mid}/reopen` | body `{"reason":"..."}` — **reason is required** (`400 reason_required`); only from `completed`; then 5 and 6 again to record a correction (`resultVersion` becomes 2) |

Headers and rules:

- **`X-CO: 1` is required on every non-GET** — their CSRF guard. Cheap to always send.
- **`Idempotency-Key` is required on `result` and `reopen`**, and responses are cached against
  `(key, scope)` for **7 days**. Replaying the same key returns the original response — that is the
  retry mechanism. A *new* key against an already-completed match is `409 invalid_transition`.
- No cookies or session are needed in open mode; the native client is not subject to their CORS
  posture (no `Origin`), so nothing has to change on the server for the app to talk to it.
- Error contract: `404 not_found`, `409 invalid_transition`, `400 invalid_result` (winner/loser must
  be that match's two entrants), `403 forbidden` if the server is *not* in open mode.
- Live updates come from SSE at `/api/v1/tournaments/{id}/events`, but note the server emits **named
  events** (`hello`, `snapshot_required`, `match.result_submitted`, …), so `EventSource.onmessage`
  never fires — their own web client has that bug. For the first version the app re-reads on demand
  and after each submit; SSE is a follow-up.

## The score payload

`score` is `json.RawMessage` on the server — stored verbatim in `match_results.payload_json`, and only
read back by the session-gated match-history endpoint. Their own fixtures use `{"score":"75-40"}` and
corrections add `"corrected":true`, so the app follows that convention and adds its own detail
alongside it. A result from a tournament whose division carries a race of 7, scored 4–7:

```json
{
  "score": "4-7",
  "home": 4, "away": 7,
  "homeName": "Ada", "awayName": "Bo",
  "discipline": "9ball",
  "app": "score-android", "appVersion": "1.4",
  "payloadVersion": 2, "sheetKind": "tally",
  "homeGoal": 7, "awayGoal": 7, "raceTo": 7,
  "raceFrom": "bracket", "raceSource": "division", "bracketRaceTo": 7
}
```

A consumer that reads only `.score` still sees the familiar string. Verified: the server stored this
payload byte-for-byte (before the race fields were added — see "Verified" below for what has been run
since).

The race block, field by field:

| Field | Meaning |
| --- | --- |
| `homeGoal` / `awayGoal` | the goal each side was playing to, so an uneven handicap is visible. Sent only when a goal is in force (`0` would mean none) |
| `raceTo` | the single number their fixtures and their console's reader use: the harder of the two goals. Absent when nothing was played to |
| `bracketRaceTo` | the bracket's own race when the operator deviated from it, so a handicap cannot be mistaken for what the tournament asked for. Absent when the bracket names no race |
| `raceFrom` | the **console's** word for where the race came from, which is what its reader believes: `bracket` \| `local` \| `override` \| `unknown` |
| `raceSource` | this app's finer one: `match` \| `division` \| `default` \| `bracket` \| `local` \| `none` |

Two provenance fields on purpose. `raceFrom` is the convention in the console's own sheet record
(`sheet-payload.js`, `raceFrom = bracket \| local \| override \| unknown`): `override` is a typed goal
that disagrees with the bracket's race, `local` is one typed where the bracket named no race at all, and
`bracket` is the normal path. `raceSource` is finer, because the brief for this change asks which
*layer* the bracket's number came from — a division's target, a per-match override for a longer final,
or the game's own default — and the console's three words cannot express that. A reader that knows only
one of the two still learns the truth.

`sheetKind` is the other half of that record's convention, and the one marker a phone result must not
omit: a sheet is produced by a **rack grid** (marked ball by ball — 15-Ball Rotation, Straight Pool
14.1) or by a **tally** (stepped games against the race target), and the console believes the
instrument a payload names, treating a missing one as a rack grid for a rack discipline. This app only
ever produces a tally, so it says `"tally"` — for 14.1 too, where the number is only the points a side
has run and never a full rack sheet. `payloadVersion` is that record's revision of this payload,
bumped when a reader has to change to keep reading it (2 since the race and its provenance arrived);
`appVersion` is the app build that wrote it, read from the package rather than hardcoded.

## How it behaves in the app

One activity, three states:

- **standalone** — exactly today's behaviour, no link, no network, no permission prompt beyond the
  install itself.
- **linked** — the two entrants become HOME and AWAY; taps and swipes tally them as they do now; a
  footer shows the match (`tableRef`/`matchLabel` when present), the race line, and a **Submit result**
  action. The link names the tournament but not the match, so the app prefers one actually being
  played, then one waiting to start, then one already recorded — and, among equals, one whose **two
  entrants are known**: a bracket that has advanced leaves downstream matches with an empty side, and
  offering a "TBD" match while a real one waits would be wrong.
- **submitted** — the winner is shown, the action becomes **Correct result** (reopen with a reason),
  and the tallies stay editable until submitted.
- **read-only** — a tournament whose scoring is not open to anonymous callers still answers the
  public read (`GET /api/v1/public/tournaments/{id}` — verified to return tournament, entrants and
  matches together in one anonymous response, and *no* scores, since results live behind the
  session-gated history endpoint). The board loads from that instead of showing an error over a blank
  screen: entrants, table and match state, the tallies still work as a plain scoreboard, and the
  submit action is disabled with the reason on screen.
- **on resume** the app re-reads the match it is on, so a director's correction, or a result recorded
  from another phone, appears at the table without the operator doing anything. The tallies and the
  match choice are left alone; only the state, the table number and the action follow the server.

**Winner selection: tap the side that won.** The server's contract wants a winner, and their own
console scores by "tapping a winner"; the tallies ride along in `score`. A tie therefore cannot be
submitted by inference — the tap decides. (Chosen as the conservative option; easy to change to
highest-score-wins if you prefer.)

Offline and failures: a failed submit keeps the tallies, shows why, and offers retry **with the same
`Idempotency-Key`**, so a submit that actually landed cannot be duplicated. `404`/`409` are terminal
and explain themselves ("this match is already final").

### Races, driven through the app

**Historical trace, from app 1.4** — the build before the race came from the bracket, when every goal was
typed and the race line had no provenance. It is kept because it is what was actually recorded, and
because it is the behaviour the fallback still gives when the bracket names no race. The payload below
is the old shape (no `raceFrom`/`raceSource`, `sheetKind` or `payloadVersion`).

Against `t_race_001` — a 9-ball tournament (`game: "9ball"`), in-progress match Avery vs Blake:

| Step | Evidence |
| --- | --- |
| Open the link | board shows **Avery / Blake**, status "Thursday 9-Ball (race to 11) · in_progress", race line "Race to — · tap to set goals" |
| Set a handicap | race line becomes **"Race to 3 – 2 · tap to change"** |
| Tally Avery to 3 | prompt: "Race over · Avery has reached 3 · Avery 03 · Blake 00 · Submit the result, or keep editing if that was a mistake" |
| Keep editing | nothing submitted, tallies and action unchanged |
| Tap + again | **no prompt** (already acknowledged) |
| Drop to 02 and back to 03 | prompt returns (re-armed) |
| Submit from the prompt | app: "Recorded: Avery won 03–00"; server: `match_results` v1, winner `e_t_race_001_1` (the side that reached its goal), match completed |

The recorded payload, verbatim:

```json
{"score":"3-0","home":3,"away":0,"homeName":"Avery","awayName":"Blake","discipline":"9ball",
 "app":"score-android","appVersion":1,"homeGoal":3,"awayGoal":2,"raceTo":3}
```

Note `discipline` is the tournament's own `game`, not a hardcoded assumption — the first version of the
app had written `15ball_rotation` unconditionally, which a 9-ball tournament would have recorded as a
lie.

## Races to a goal

A tournament can be a simple race: first to eleven games, say. **The race now comes from the bracket.**
The server keeps it in three layers — `matches.race_to`, then `divisions.race_to`, then the game's own
default — and resolves them into the match payload's `raceTo`, naming the layer in `raceFrom`. The app
reads that when it opens a match and uses it as the goal for **both sides**, so an ordinary match needs
no typing at all. The line in the linked bar says where the race came from, and the label follows the
tournament's discipline: `straight_14_1` and `15ball_rotation` are scored in balls, so theirs read
"Points to 100", and the games-won disciplines read "Race to 7". A side one point short of its goal says
"· match point".

| On the board | The bar reads |
| --- | --- |
| the division's target, or the game's default | `Race to 7 · from the bracket` / `Race to 25 · the discipline's default, no target set` |
| a per-match override (a longer final, set by a director) | `Race to 9 · the bracket's match override` |
| the operator typed a different target or a handicap | `LOCAL OVERRIDE · Race to 5 – 4 · the bracket's race is 7 · tap to change` |
| the operator typed a target and the bracket has none | `Race to 5 – 4 · set by hand — the bracket has no race · tap to change` |
| no race from the bracket (an older server, an offline read, no race set) | `Race to — · no race from the bracket — tap to set goals by hand` |

**The operator's goals survive as an explicit override, not as the normal path.** Tapping the race line
opens a dialog that names the bracket's race and says what typing here means; each side still carries
its own goal, so an uneven handicap is still possible, and an operator's line is marked as theirs under
the name as well as in the bar (`Race to 5 · local override`). Typing the bracket's own number back in
is *not* an override — it drops back to the bracket's race — and the dialog's neutral button
(`Bracket's race`) is the way back, so an override cannot be left on by accident. A goal typed for the
last match never carries into the next one: opening a match resolves from the bracket first.

Reaching a goal ends the match but the app asks rather than assumes: **Submit result**, or **keep
editing** if the tally was a mistake. Carry on past the goal and it does not nag; drop back below and
reach it again and it asks again. With no race anywhere — an older server, or a tournament nobody set a
target for — the app behaves exactly as it did before: blank means no goal, set by hand.

Point games are why the app carries **three digits**. Their own defaults run 25/50/100/150 for rotation
and to 100 or more for 14.1, and a two-digit score could not express them at all; the score font is
sized for the digit count, so three digits fill the box without clipping it.

**Where the race resolution lives:** `Race.java`, pure (no Android, no `org.json`), so
`sh tools/check-race.sh` asserts the resolution order on a plain JVM — that a match override beats the
division, that the bracket's race beats a local goal that was never claimed as an override, that a
claimed override wins, and what the payload records. The payload's shape is next door in
`ResultPayload.java`, pure for the same reason; the app copies its rows into a `JSONObject` in order.

The bracket itself still shows **win/loss only**: a bracket card never carries a score, and the race is
carried in the result payload, not drawn on the card.

## Opening the link in the app, or the browser

Android **App Links**: the app declares an intent filter for
`https://codeonline.io/15ball/live.html` with `autoVerify="true"`, so Android opens the app when it is
installed and the browser when it is not — no change to the web page, and no `intent://` handoff.

That verification needs the host to serve `https://codeonline.io/.well-known/assetlinks.json`
containing our package `com.kevin.score` and the signing certificate's SHA-256
(`3b723db8b604a7126811bcf0df539c177b35207b5ceee57f73ad4513e6daf129` for the release key, plus the debug
key's digest for development). **Without that file the link still works** — Android shows a chooser
instead of opening the app directly. Adding the file is a change to *their* deployment, so it is
listed below as a decision rather than assumed.

## Consequences the app must own

- **The app gains `INTERNET`.** The public README currently promises "no permissions at all, no
  network access, nothing collected". That is true of standalone mode and must stay true of it — the
  network is used only when a link is opened. Both READMEs and the privacy section have to be
  rewritten to say exactly that, and the distribution repo's feature list gains a caveat. This is a
  user-visible change to published claims, not a footnote.
- **Dev-only cleartext HTTP** via a `network_security_config` scoped to the development host, never on
  in a release build (`usesCleartextTraffic` stays off globally).
- **Anonymous results have no actor** on the server side. The app should say so on the submitted
  screen rather than imply attribution.
- The standalone scoreboard keeps working with no network permission *granted at runtime* — nothing to
  prompt for either way, since network access is install-time.

## What is out of scope for this version

- Director/session features (creating tournaments and entrants stays in their web console).
- Reading result history or the stored payload back (session-gated anyway).
- SSE live updates, and the OBS overlay.
- Any change to the server repo, with the one exception already made on their side: the race columns
  (`divisions.race_to`, `matches.race_to`, migration 0018) and the `raceTo`/`raceFrom` the match payload
  resolves from them. The app reads those; it sets neither.

## Verified so far

- Their server **builds and its whole test suite passes** here (`go build`, `go vet`, `go test ./...`
  green, including the acceptance suite) using a portable Go in `toolchain/`.
- The server **runs locally** (`127.0.0.1:8093`, SQLite file, `OPEN_MATCH_EDITING=1`), and their own
  `internal/seed` fixtures were loaded through a throwaway helper: `t_live_001` is `in_progress`,
  public, with matches in six different states.
- **Anonymous reads** — tournament, entrants, matches — all `200` with no session.
- **Anonymous scoring** — `result` `200`, replay with the same key `200` (idempotent), a new key
  against the finished match `409 invalid_transition`, and a result carrying our scoreboard payload
  stored verbatim in `match_results.payload_json`.
- Two harness bugs found and fixed along the way, both mine: a trailing CR from `$(python -c print(…))`
  in a header value makes Go reject the whole request as `400 Bad Request` (the fix is to strip it),
  and `reopen` returns `400 reason_required` rather than a CSRF-style failure when the reason is
  missing.

### The race coming from the bracket (this change)

Everything above was run against a server that predates `race_to`. The columns and the payload fields
this change reads were **not** exercised against a live bracket here; a device run needs a server that
has them. What was exercised:

- **`sh tools/check-race.sh`** — 135 assertions, all passing, on a plain JVM: the resolution order (a
  match override over the division; the bracket's race over a local goal that was never claimed as an
  override; a claimed override over both; an unnamed layer inferred from the two values; nothing
  anywhere), the two provenance vocabularies, the discipline's label, and the payload's exact shape,
  key order and marker. Each guard was broken deliberately, shown red, and restored — the rounds are
  listed in `CONTINUE-HERE.md`.
- **`sh build.sh`** — `out/score.apk` builds (81 KiB), `apksigner verify -v` reports v1, v2 and v3 all
  verifying, badging reads `com.kevin.score` versionCode 5 / versionName 1.4 / minSdk 21 / targetSdk 34 /
  one permission, and the new strings and classes are in the artifact (`aapt2 dump resources`,
  `dexdump`).
- **Not** exercised: the race line's wording, the override dialog's three buttons, the goal line under a
  name, the payload arriving in `match_results` from a real submit — all of that renders on the
  platform and needs the emulator against a server with the columns. `tools/verify-ui.sh` covers
  standalone scoring only, so it would not exercise any of it either.

### In the app, on the emulator

- Opening the scoring link loads the tournament and offers the scoreable matches with their real
  labels ("Player 5 vs Player 6 — in_progress", "Player 1 vs Player 2 — reopened"); with exactly one
  candidate it goes straight to the board instead of asking.
- The entrants become HOME and AWAY, taps and swipes tally them, and **Submit result** asks who won
  and records it. The app showed `Recorded: Player 1 won 05–03`, and the server held `match_results`
  v1 with winner `e_t_live_001_1` and the payload

  ```json
  {"score":"5-3","home":5,"away":3,"homeName":"Player 1","awayName":"Player 2",
   "discipline":"15ball_rotation","app":"score-android","appVersion":1}
  ```

- **Correct result** reopened the match with a reason; the second submit flipped the winner, and the
  server held v2 with `reopened_from_result_id` set and the audit trail
  `result_submitted → reopened → result_submitted` (actors NULL, which is what open editing means).
- Two bugs found here and fixed. Mine: the success path touched views from the worker thread
  (`CalledFromWrongThreadException` raised out of `onRecorded`), which killed the app *after* the server
  had accepted the result — only the error path had been marshalled; the fix is to post the whole
  callback to the UI thread. And environmental: the emulator still held a release-signed build, so
  installing the debug APK failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` until it was uninstalled,
  because the new intent filter only exists in the new build.
- An observation about their code, not a change made: submitting after a reopen does not set
  `superseded_by` on the earlier result, because `SubmitResult` inserts the new row without touching
  the old one. The app is unaffected (the board queries the highest `result_version`), but their
  session-gated match-history endpoint filters on `superseded_by IS NULL`, so a corrected match reads
  as two live versions there. The design's rule is no server changes, so it is recorded rather than
  fixed.

## Deployment note

The web console is served from a path prefix while the API is served by the Go process on loopback, so
the host needs both a root that lands people on the console and an API proxy:

```nginx
location = /          { return 302 /15ball/; }
location = /15ball    { return 301 /15ball/; }          # trailing slash matters, see below
location /15ball/api/ { proxy_pass http://127.0.0.1:8093/api/; }
location /15ball/     { root /srv/15ball; }             # the static console
```

The second line is not cosmetic: `…/15ball` without the trailing slash serves the console, but its
relative script and link paths then resolve against `/` and the page silently loads no JavaScript. A
scoring link always carries `/15ball/live.html`, so this only bites people who type the tournament URL
by hand — which is exactly what a person does when they want to watch a board. `tools/dev-server.py`
mirrors all of it locally, redirects included.

## Open decisions

1. **Can we add `/.well-known/assetlinks.json`** to the deployment (a file on their host, no server
   code change) so the link opens the app directly instead of a chooser? If not, the chooser path is
   the fallback and everything still works.
2. **Winner selection** — implemented as designed: **tap the side that won**, with the tallies sent as
   the payload. Inferring from the tallies (asking only on a tie) remains an option if you prefer it.
3. **Which host to verify against** — the local server built here, or a deployed instance.
