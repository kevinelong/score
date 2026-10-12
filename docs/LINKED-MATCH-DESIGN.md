# Linked match mode — design

The app gains a second way to be used. Today it is a standalone scoreboard: you open it, it knows
nothing, you type nothing, it scores two teams. In **linked match mode** you open a *link* — the same
scoring link the tournament director already shares as a QR code — and the app loads that match from
the live tournament server, scores it, and submits the result. If the app is not installed, the same
link opens the tournament's web console in the browser instead.

The server is **kevinelong/15ball-scoresheet** (Go + SQLite, nginx in front). Nothing in this design
requires a change to it.

Status: design + protocol verified against a running server. The app side is not implemented yet.

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
| 3 | `GET /api/v1/tournaments/{id}/matches` | `{items:[...]}` — only meaningful once the tournament is `in_progress`/`completed`; each match has `id, slot, state, entrantAId, entrantBId, winnerEntrantId, bracket, matchLabel, tableRef, version` |
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
alongside it:

```json
{
  "score": "11-7",
  "home": 11, "away": 7,
  "homeName": "Player 1", "awayName": "Player 2",
  "discipline": "15ball_rotation",
  "app": "score-android", "appVersion": 2
}
```

A consumer that reads only `.score` still sees the familiar string. Verified: the server stored this
payload byte-for-byte.

## How it behaves in the app

One activity, three states:

- **standalone** — exactly today's behaviour, no link, no network, no permission prompt beyond the
  install itself.
- **linked** — the two entrants become HOME and AWAY; taps and swipes tally them as they do now; a
  footer shows the match (`tableRef`/`matchLabel` when present) and a **Submit result** action.
- **submitted** — the winner is shown, the action becomes **Correct result** (reopen with a reason),
  and the tallies stay editable until submitted.

**Winner selection: tap the side that won.** The server's contract wants a winner, and their own
console scores by "tapping a winner"; the tallies ride along in `score`. A tie therefore cannot be
submitted by inference — the tap decides. (Chosen as the conservative option; easy to change to
highest-score-wins if you prefer.)

Offline and failures: a failed submit keeps the tallies, shows why, and offers retry **with the same
`Idempotency-Key`**, so a submit that actually landed cannot be duplicated. `404`/`409` are terminal
and explain themselves ("this match is already final").

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
- Any change to the server repo.

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

## Open decisions

1. **Can we add `/.well-known/assetlinks.json`** to the deployment (a file on their host, no server
   code change) so the link opens the app directly instead of a chooser? If not, the chooser path is
   the fallback and everything still works.
2. **Winner selection** — the design says "tap the side that won". Alternative: infer from the
   tallies and only ask on a tie.
3. **Which host to verify against** — the local server built here, or a deployed instance.
