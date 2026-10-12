package com.kevin.score;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the tournament server (kevinelong/15ball-scoresheet), speaking exactly the protocol
 * documented in docs/LINKED-MATCH-DESIGN.md.
 *
 * <p>Only what a scoring link needs: resolve the link, read the board, submit a result, correct one.
 * Every fact about the protocol here was verified against a running server rather than read from
 * their docs - including that {@code score} is free-form JSON the server stores verbatim, that
 * {@code result} and {@code reopen} require an {@code Idempotency-Key}, and that every non-GET needs
 * their {@code X-CO} header.
 */
final class TournamentApi {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** A failure worth showing a person: offline, not found, or the server's own error code. */
    static final class ApiError extends Exception {
        final int status;
        final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        boolean isOffline() {
            return status == 0;
        }

        /** 409: the match is no longer in a state that accepts a result. */
        boolean isConflict() {
            return status == 409;
        }

        String label() {
            if (isOffline()) {
                return code.equals("network") ? "No connection to the server" : "Bad response from the server";
            }
            return getMessage() == null || getMessage().isEmpty() ? "Server error " + status : getMessage();
        }
    }

    static final class Entrant {
        final String id;
        final String displayName;
        final String state;

        Entrant(String id, String displayName, String state) {
            this.id = id;
            this.displayName = displayName;
            this.state = state;
        }
    }

    static final class Match {
        final String id;
        final String state;
        final String entrantAId;
        final String entrantBId;
        final String winnerEntrantId;
        final String matchLabel;
        final String tableRef;

        Match(String id, String state, String entrantAId, String entrantBId,
              String winnerEntrantId, String matchLabel, String tableRef) {
            this.id = id;
            this.state = state;
            this.entrantAId = entrantAId;
            this.entrantBId = entrantBId;
            this.winnerEntrantId = winnerEntrantId;
            this.matchLabel = matchLabel;
            this.tableRef = tableRef;
        }

        boolean isScoreable() {
            return "in_progress".equals(state) || "reopened".equals(state);
        }

        boolean isComplete() {
            return "completed".equals(state);
        }

        boolean needsStart() {
            return "scheduled".equals(state) || "assigned".equals(state);
        }

        /** Short description for the footer, e.g. "W2M1 · table 4". */
        String describe() {
            StringBuilder sb = new StringBuilder();
            if (matchLabel != null && !matchLabel.isEmpty()) {
                sb.append(matchLabel);
            }
            if (tableRef != null && !tableRef.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append("table ").append(tableRef);
            }
            if (sb.length() == 0) {
                return state;
            }
            return sb.toString();
        }
    }

    static final class Tournament {
        final String id;
        final String name;
        final String state;
        final String visibility;
        /** The discipline the director chose, e.g. "15ball_rotation" — free-form, so never assumed. */
        final String game;

        Tournament(String id, String name, String state, String visibility, String game) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.visibility = visibility;
            this.game = game;
        }

        boolean isScorable() {
            return "in_progress".equals(state) || "completed".equals(state);
        }
    }

    /** A scoring link, resolved into "which server" and "which tournament". */
    static final class Link {
        final String apiBase;
        final String tournament;

        Link(String apiBase, String tournament) {
            this.apiBase = apiBase;
            this.tournament = tournament;
        }
    }

    private final String apiBase;

    private TournamentApi(String apiBase) {
        this.apiBase = apiBase;
    }

    /**
     * Accepts the director's scoring link in either form:
     *
     * <ul>
     *   <li>{@code https://<host>/15ball/live.html?t=<slug-or-id>} - what the QR code encodes</li>
     *   <li>{@code scoreboard://match?url=<that link>} - the development form, because an https
     *       link can only open the app unattended when the host serves
     *       {@code /.well-known/assetlinks.json}, which the LAN test server cannot</li>
     * </ul>
     *
     * The API base is the link's origin plus its directory, so {@code /15ball/live.html} on
     * {@code codeonline.io} becomes {@code https://codeonline.io/15ball/api/v1} - the same mapping
     * nginx performs in production.
     */
    static Link parseLink(Uri uri) throws ApiError {
        if (uri == null) {
            throw new ApiError(0, "bad_link", "No link");
        }
        Uri target = uri;
        if ("scoreboard".equals(uri.getScheme())) {
            String nested = uri.getQueryParameter("url");
            if (nested == null) {
                throw new ApiError(0, "bad_link", "That link does not carry a scoring URL");
            }
            target = Uri.parse(nested);
        }
        String scheme = target.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new ApiError(0, "bad_link", "That link is not a tournament scoring link");
        }
        String tournament = target.getQueryParameter("t");
        if (tournament == null || tournament.isEmpty()) {
            throw new ApiError(0, "bad_link", "That link has no tournament in it");
        }
        String path = target.getPath() == null ? "" : target.getPath();
        if (!path.endsWith("live.html")) {
            throw new ApiError(0, "bad_link", "That link is not a tournament scoring link");
        }
        String dir = path.substring(0, path.length() - "live.html".length());
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        return new Link(scheme + "://" + target.getAuthority() + dir + "api/v1", tournament);
    }

    // ------------------------------------------------------------------ reading

    /**
     * One anonymous read of {@code /public/tournaments/{id}}.
     *
     * <p>It answers with the tournament, its entrants and its matches in a single response, which is
     * how the app can still show a board for a tournament that is not open for anonymous scoring.
     * It carries no scores: results live behind the session-gated history endpoint.
     */
    static final class PublicBoard {
        final Tournament tournament;
        final List<Entrant> entrants;
        final List<Match> matches;

        PublicBoard(Tournament tournament, List<Entrant> entrants, List<Match> matches) {
            this.tournament = tournament;
            this.entrants = entrants;
            this.matches = matches;
        }
    }

    PublicBoard publicBoard(String slugOrId) throws ApiError {
        JSONObject body = request("GET", "/public/tournaments/" + encode(slugOrId), null, null);
        JSONObject t = body.optJSONObject("tournament");
        if (t == null) {
            throw new ApiError(0, "bad_response", "No tournament in the response");
        }
        // This payload names its arrays entrants/matches rather than items, so the fields are parsed
        // by the same helpers the scoring endpoints use.
        return new PublicBoard(
                new Tournament(t.optString("id"), t.optString("name"), t.optString("state"),
                        t.optString("visibility"), t.optString("game")),
                parseEntrants(body.optJSONArray("entrants")),
                parseMatches(body.optJSONArray("matches")));
    }

    Tournament[] tournament(String slugOrId) throws ApiError {
        JSONObject body = request("GET", "/tournaments/" + encode(slugOrId), null, null);
        JSONArray divisions = body.optJSONArray("divisions");
        List<Tournament> out = new ArrayList<Tournament>();
        JSONObject t = body.optJSONObject("tournament");
        if (t != null) {
            out.add(new Tournament(t.optString("id"), t.optString("name"), t.optString("state"),
                    t.optString("visibility"), t.optString("game")));
        } else if (divisions == null) {
            throw new ApiError(0, "bad_response", "No tournament in the response");
        }
        return out.toArray(new Tournament[0]);
    }

    List<Entrant> entrants(String tournamentId) throws ApiError {
        JSONObject body = request("GET", "/tournaments/" + encode(tournamentId) + "/entrants?archived=true", null, null);
        return parseEntrants(body.optJSONArray("items"));
    }

    List<Match> matches(String tournamentId) throws ApiError {
        JSONObject body = request("GET", "/tournaments/" + encode(tournamentId) + "/matches", null, null);
        return parseMatches(body.optJSONArray("items"));
    }

    private static List<Entrant> parseEntrants(JSONArray items) {
        List<Entrant> out = new ArrayList<Entrant>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            out.add(new Entrant(item.optString("id"), item.optString("displayName"),
                    item.optString("state")));
        }
        return out;
    }

    private static List<Match> parseMatches(JSONArray items) {
        List<Match> out = new ArrayList<Match>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            out.add(new Match(item.optString("id"), item.optString("state"),
                    item.optString("entrantAId"), item.optString("entrantBId"),
                    item.isNull("winnerEntrantId") ? null : item.optString("winnerEntrantId"),
                    item.isNull("matchLabel") ? null : item.optString("matchLabel"),
                    item.isNull("tableRef") ? null : item.optString("tableRef")));
        }
        return out;
    }

    // ------------------------------------------------------------------ writing

    /** Makes a scheduled match scoreable. Tolerates 409: someone else may have started it. */
    void assign(String tournamentId, String matchId) throws ApiError {
        tolerant("POST", matchPath(tournamentId, matchId) + "/assign", "{}", null);
    }

    void start(String tournamentId, String matchId) throws ApiError {
        tolerant("POST", matchPath(tournamentId, matchId) + "/start", "{}", null);
    }

    /**
     * Records the result. The winner and loser must be the match's own two entrants, the idempotency
     * key must be reused on a retry, and the body carries this app's tallies as free-form JSON that
     * the server stores verbatim (their own fixtures use {"score":"75-40"}).
     */
    JSONObject submitResult(String tournamentId, String matchId, String winnerId, String loserId,
                            JSONObject scores, String idempotencyKey) throws ApiError {
        JSONObject body = new JSONObject();
        try {
            body.put("winnerEntrantId", winnerId);
            body.put("loserEntrantId", loserId);
            body.put("score", scores);
        } catch (JSONException e) {
            throw new ApiError(0, "bad_request", "Could not build the result");
        }
        return request("POST", matchPath(tournamentId, matchId) + "/result", body.toString(), idempotencyKey);
    }

    /** Reopens a completed match so the result can be corrected. A reason is required. */
    JSONObject reopen(String tournamentId, String matchId, String reason, String idempotencyKey)
            throws ApiError {
        JSONObject body = new JSONObject();
        try {
            body.put("reason", reason);
        } catch (JSONException e) {
            throw new ApiError(0, "bad_request", "Could not build the request");
        }
        return request("POST", matchPath(tournamentId, matchId) + "/reopen", body.toString(), idempotencyKey);
    }

    // ------------------------------------------------------------------ plumbing

    private static String matchPath(String tournamentId, String matchId) {
        return "/tournaments/" + encode(tournamentId) + "/matches/" + encode(matchId);
    }

    /** Sends a mutation whose 409 only means "already in that state" and is not worth surfacing. */
    private void tolerant(String method, String path, String body, String idempotencyKey) throws ApiError {
        try {
            request(method, path, body, idempotencyKey);
        } catch (ApiError e) {
            if (!e.isConflict()) {
                throw e;
            }
        }
    }

    private JSONObject request(String method, String path, String body, String idempotencyKey)
            throws ApiError {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiBase + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (!"GET".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-CO", "1");
                if (idempotencyKey != null) {
                    conn.setRequestProperty("Idempotency-Key", idempotencyKey);
                }
                byte[] payload = (body == null ? "{}" : body).getBytes(UTF8);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int status = conn.getResponseCode();
            String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status >= 400) {
                throw toError(status, text);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (IOException e) {
            throw new ApiError(0, "network", e.getMessage() == null ? "" : e.getMessage());
        } catch (JSONException e) {
            throw new ApiError(0, "bad_response", "Unexpected response from the server");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Turns the server's {"error":{"code","message"}} envelope into something showable. */
    private static ApiError toError(int status, String text) {
        try {
            JSONObject error = new JSONObject(text).optJSONObject("error");
            if (error != null) {
                return new ApiError(status, error.optString("code"), error.optString("message"));
            }
        } catch (JSONException ignored) {
            // not the JSON envelope (Go's own 400 Bad Request is text/plain) - fall through
        }
        String message = text == null ? "" : text.trim();
        if (message.length() > 120) {
            message = message.substring(0, 120);
        }
        return new ApiError(status, "http_" + status, message);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    /** Percent-encodes the few characters that can appear in a slug or id. */
    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return value;
        }
    }

    static TournamentApi forLink(Link link) {
        return new TournamentApi(link.apiBase);
    }

    /** Exposed for tests and diagnostics: the API base a link resolves to. */
    String apiBase() {
        return apiBase;
    }
}
