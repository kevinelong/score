"""A stand-in for a tournament server that predates the race columns.

    python tools/older-server.py [port]        # default 8200

Two of the race line's states cannot be produced against the current server at all: it always
resolves a race (matches.race_to, then divisions.race_to, then the game's own default) and always
sends raceTo as a number, so the board always has a bracket race to read. The states that need a
server which sends *none* -- "no race from the bracket" and "set by hand" -- therefore need a server
that answers the way the old one did: the documented shape, with raceTo and raceFrom absent from the
match payload and raceTo absent from the division.

It serves only what a scoring link reads (the tournament read, its entrants, its matches). Point the
app at it with

    adb shell am start -a android.intent.action.VIEW \
      -d "scoreboard://match?url=http%3A%2F%2F10.0.2.2%3A8200%2Flive.html%3Ft%3Dt_old_001"

Two tournaments, because there are two things a server without the race needs to show:

  t_old_001   the scoring reads answer, with no race anywhere        -> "no race from the bracket"
  t_vo_001    the scoring reads answer 401, as a tournament that is not open for anonymous scoring does,
              and the anonymous read answers instead                 -> the read-only fallback's board

Every request is logged to stdout, which is the probe trail for what the app actually asked for.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOURNAMENT = {
    "id": "t_old_001",
    "slug": "t_old_001",
    "name": "Thursday 9-Ball (older server)",
    "game": "9ball",
    "venue": None,
    "club": None,
    "state": "in_progress",
    "visibility": "public",
    "createdBy": "u_director",
    "createdAt": 1767225600,
    "updatedAt": 1767225600,
    "version": 1,
}

# The same, for the tournament whose scoring reads are closed: only the anonymous read answers.
TOURNAMENT_VO = {
    "id": "t_vo_001",
    "slug": "t_vo_001",
    "name": "Thursday 9-Ball (closed to scoring)",
    "game": "9ball",
    "state": "in_progress",
    "visibility": "public",
    "version": 1,
}

# No raceTo: the pre-race division payload.
DIVISION = {
    "id": "d_old_001",
    "tournamentId": "t_old_001",
    "name": "Open",
    "format": "single_elimination",
    "state": "active",
    "createdAt": 1767225600,
    "updatedAt": 1767225600,
}

ENTRANTS = [
    {"id": "e_old_1", "tournamentId": "t_old_001", "divisionId": "d_old_001",
     "displayName": "Casey", "state": "checked_in", "version": 1},
    {"id": "e_old_2", "tournamentId": "t_old_001", "divisionId": "d_old_001",
     "displayName": "Drew", "state": "checked_in", "version": 1},
]

# No raceTo and no raceFrom: the pre-race match payload, in_progress so the app scores it directly.
MATCH = {
    "id": "m_old_001_1",
    "tournamentId": "t_old_001",
    "divisionId": "d_old_001",
    "bracketRound": 1,
    "slot": 0,
    "entrantAId": "e_old_1",
    "entrantBId": "e_old_2",
    "state": "in_progress",
    "tableRef": "5",
    "bracket": None,
    "matchLabel": None,
    "winnerEntrantId": None,
    "needsReview": False,
    "version": 2,
    "startedAt": 1767225600,
    "createdAt": 1767225600,
    "updatedAt": 1767225600,
}

# The same shape for the closed tournament: also no race, which is what the public read carries.
MATCH_VO = dict(MATCH, id="m_vo_001_1", tournamentId="t_vo_001", divisionId=None)
ENTRANTS_VO = [dict(e, tournamentId="t_vo_001", divisionId=None) for e in ENTRANTS]



class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("older-server: %s\n" % (fmt % args))
        sys.stdout.flush()

    def send_json(self, body, status=200):
        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        path = self.path.split("?")[0]
        if "/public/tournaments/" in path:
            # The anonymous read answers for both: tournament, entrants and matches in one body.
            vo = "t_vo_001" in path
            self.send_json({
                "tournament": TOURNAMENT_VO if vo else TOURNAMENT,
                "entrants": ENTRANTS_VO if vo else ENTRANTS,
                "matches": [MATCH_VO if vo else MATCH],
            })
            return
        if "t_vo_001" in path:
            # The scoring reads are closed on this tournament, which is what the app's fallback is for.
            self.send_json({"error": {
                "code": "forbidden",
                "message": "This tournament is not open for anonymous scoring",
            }}, 401)
            return
        if path.endswith("/matches"):
            self.send_json({"items": [MATCH]})
        elif path.endswith("/entrants"):
            self.send_json({"items": ENTRANTS})
        elif "/tournaments/" in path and path.endswith("t_old_001"):
            self.send_json({"tournament": TOURNAMENT, "divisions": [DIVISION]})
        else:
            self.send_json({"error": "not_found", "message": path}, 404)

    def do_POST(self):
        # Nothing here is submitted during the no-race states, but answer so a stray write is
        # visible in the log rather than a connection error.
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else ""
        sys.stdout.write("older-server: POST %s body=%s\n" % (self.path, body))
        sys.stdout.flush()
        self.send_json({"ok": True})


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8200
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    sys.stdout.write("older-server listening on http://127.0.0.1:%d (no race fields)\n" % port)
    sys.stdout.flush()
    server.serve_forever()


if __name__ == "__main__":
    main()
