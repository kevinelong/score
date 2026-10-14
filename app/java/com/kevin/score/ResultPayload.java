package com.kevin.score;

import java.util.ArrayList;
import java.util.List;

/**
 * The payload the server stores verbatim: their own {@code {"score":"a-b"}} convention plus this
 * app's detail.
 *
 * <p>Three things about the race travel with a result: the value actually in force ({@code homeGoal},
 * {@code awayGoal} and {@code raceTo}, which has always been here), the bracket's own number when the
 * operator deviated from it ({@code bracketRaceTo}), and where it came from — in <b>two</b> fields,
 * because two different vocabularies exist and each names one of them:
 *
 * <ul>
 *   <li>{@code raceOrigin} — where the value in force came from, in the console's saved-sheet
 *       vocabulary: {@code bracket} | {@code local} | {@code override} | {@code unknown}. This is the
 *       field the console's record reads, so a sheet saved by the phone and one saved by the console
 *       read the same way. {@code override} is a typed goal that disagrees with the bracket's race,
 *       {@code local} is one typed where the bracket named no race to disagree with, {@code unknown}
 *       is no race anywhere.</li>
 *   <li>{@code raceSource} — which <b>layer</b> of the bracket supplied the race:
 *       {@code match} | {@code division} | {@code default}, or {@code none} when the bracket names no
 *       race at all. It describes the bracket's race ({@code bracketRaceTo}, and the goals when no
 *       override is in force), never the operator's own value; an override is an origin, not a layer.
 *       Omitted when a race exists but the server did not name its layer.</li>
 * </ul>
 *
 * <p>Those two vocabularies collide in one place and it is worth knowing why: the <b>match payload's
 * own</b> {@code raceFrom} — the thing this app reads — speaks {@code match}/{@code division}/
 * {@code default} (their {@code resolveRaceTo}), while the sheet record's field is an origin. The
 * record's field is therefore called {@code raceOrigin}, so {@code raceFrom} means the server's field
 * and nothing else.
 *
 * <p>{@code sheetKind} is the marker that keeps a phone tally from being read as a full rack sheet: a
 * reader believes the instrument a payload names, and treats a missing one as a rack grid for a rack
 * discipline. This app only ever produces a tally, so it says so — including for 14.1 and 15-ball
 * rotation, whose sheets are rack grids and whose tallies here are only the points a side has run.
 * {@code payloadVersion} is that record's revision of this payload, bumped when a reader has to change
 * to keep reading it: **version 2**.
 *
 * <p>Pure on purpose - no Android and no {@code org.json} - so {@code tools/check-race.sh} can assert
 * the shape and the provenance on a plain JVM. The caller copies these rows into its JSON object in
 * this order, which is the order a stored payload reads in.
 */
final class ResultPayload {

    /** Their fixtures read {@code app} to tell the clients apart; this app has always used this. */
    static final String APP = "score-android";
    /** This payload's own revision, not the APK's version: bumped when its shape changes. */
    static final int PAYLOAD_VERSION = 2;
    /** The instrument that produced the number: a tally stepped against the race target. */
    static final String SHEET_TALLY = "tally";
    /** The operator's typed goal, where the bracket's race disagrees with it. */
    static final String ORIGIN_OVERRIDE = "override";
    /** A hand-set goal where the bracket named no race at all - nothing to override. */
    static final String ORIGIN_LOCAL = "local";
    /** Read from the match payload: the bracket supplied the race. */
    static final String ORIGIN_BRACKET = "bracket";
    /** No target anywhere, so there is no origin to name. */
    static final String ORIGIN_UNKNOWN = "unknown";

    /** One key and its value: an {@link Integer} for a number, a {@link String} for text. */
    static final class Field {
        final String key;
        final Object value;

        Field(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    private ResultPayload() {
    }

    /**
     * The payload's fields, in order.
     *
     * @param discipline the tournament's own game string, or null/empty when it has none
     * @param appVersion the app build that produced this result, or null/empty when unknown
     * @param race       the race in force, as {@link Race#plan} resolved it
     */
    static List<Field> fields(String homeName, String awayName, int home, int away,
                              String discipline, String appVersion, Race.Plan race) {
        List<Field> out = new ArrayList<Field>();
        out.add(new Field("score", home + "-" + away));
        out.add(new Field("home", Integer.valueOf(home)));
        out.add(new Field("away", Integer.valueOf(away)));
        out.add(new Field("homeName", homeName));
        out.add(new Field("awayName", awayName));
        if (discipline != null && !discipline.isEmpty()) {
            out.add(new Field("discipline", discipline));   // whatever the director chose
        }
        out.add(new Field("app", APP));
        if (appVersion != null && !appVersion.isEmpty()) {
            out.add(new Field("appVersion", appVersion));
        }
        out.add(new Field("payloadVersion", Integer.valueOf(PAYLOAD_VERSION)));
        out.add(new Field("sheetKind", SHEET_TALLY));
        if (race.hasGoal()) {
            // Uneven goals are a handicap, so both are recorded, and raceTo stays the single number
            // their fixtures and the console's own reader expect.
            out.add(new Field("homeGoal", Integer.valueOf(race.homeGoal)));
            out.add(new Field("awayGoal", Integer.valueOf(race.awayGoal)));
            out.add(new Field("raceTo", Integer.valueOf(race.raceTo())));
        }
        out.add(new Field("raceOrigin", origin(race)));
        String layer = Race.name(race.bracketSource);
        if (!layer.isEmpty()) {
            // Which layer of the bracket supplied the race. Empty when a race exists but the server
            // named no layer: absent beats inventing a word for it.
            out.add(new Field("raceSource", layer));
        }
        if (race.hasBracket()) {
            out.add(new Field("bracketRaceTo", Integer.valueOf(race.bracketRaceTo)));
        }
        return out;
    }

    /** Where the value in force came from, in the console's saved-sheet vocabulary. */
    static String origin(Race.Plan race) {
        if (race.source == Race.FROM_LOCAL) {
            return race.hasBracket() ? ORIGIN_OVERRIDE : ORIGIN_LOCAL;
        }
        if (race.source == Race.FROM_NONE) {
            return ORIGIN_UNKNOWN;
        }
        return ORIGIN_BRACKET;
    }
}
