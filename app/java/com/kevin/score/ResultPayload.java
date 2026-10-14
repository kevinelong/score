package com.kevin.score;

import java.util.ArrayList;
import java.util.List;

/**
 * The payload the server stores verbatim: their own {@code {"score":"a-b"}} convention plus this
 * app's detail.
 *
 * <p>Three things about the race travel with a result. The value actually in force (the goals, and
 * {@code raceTo}, which has always been here), the bracket's own number when the operator deviated
 * from it ({@code bracketRaceTo}), and where the value came from, in two vocabularies on purpose:
 *
 * <ul>
 *   <li>{@code raceSource} is this app's finer one - {@code match}, {@code division}, {@code default},
 *       {@code bracket}, {@code local} or {@code none} - so a director's division target can be told
 *       from a per-match override for a longer final, and from a game's own default;</li>
 *   <li>{@code raceFrom} is the console's own word from its sheet record ({@code sheet-payload.js}:
 *       {@code bracket} | {@code local} | {@code override} | {@code unknown}), which is what its
 *       reader believes when a payload names its own origin. An operator's typed goal is
 *       {@code override} when it disagrees with the bracket's race and {@code local} when the
 *       bracket named no race to disagree with.</li>
 * </ul>
 *
 * <p>{@code sheetKind} is the marker that keeps a phone tally from being read as a full rack sheet,
 * which the same record defines: a reader believes the instrument a payload names, and treats a
 * missing one as a rack grid for a rack discipline. This app only ever produces a tally, so it says
 * so - including for 14.1 and 15-ball rotation, whose sheets are rack grids and whose tallies here
 * are only the points a side has run. {@code payloadVersion} is that record's revision of this
 * payload, bumped when a reader has to change to keep reading it.
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
    /** An operator's typed goal, where the bracket's race disagrees with it. */
    static final String FROM_OVERRIDE = "override";
    /** A hand-set goal where the bracket named no race at all - nothing to override. */
    static final String FROM_LOCAL = "local";
    /** Read from the match payload: division target, per-match override or the game's default. */
    static final String FROM_BRACKET = "bracket";
    /** No target anywhere, so there is no origin to name. */
    static final String FROM_UNKNOWN = "unknown";

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
        out.add(new Field("raceFrom", origin(race)));
        out.add(new Field("raceSource", Race.name(race.source)));
        if (race.hasBracket()) {
            out.add(new Field("bracketRaceTo", Integer.valueOf(race.bracketRaceTo)));
        }
        return out;
    }

    /** Where the race came from, in the console's own vocabulary, as its reader expects it. */
    static String origin(Race.Plan race) {
        if (race.source == Race.FROM_LOCAL) {
            return race.hasBracket() ? FROM_OVERRIDE : FROM_LOCAL;
        }
        if (race.source == Race.FROM_NONE) {
            return FROM_UNKNOWN;
        }
        return FROM_BRACKET;
    }
}
