package com.kevin.score;

/**
 * The race a match is played to, and where it came from.
 *
 * <p>The tournament server keeps the race itself: {@code divisions.race_to} is a division's target
 * and {@code matches.race_to} is an optional per-match override for a longer final. The match payload
 * resolves the three layers for us (match, then division, then the game's own default) and exposes the
 * result as {@code raceTo}, naming the layer that supplied it as {@code raceFrom}; the division
 * payload exposes its own. So the board no longer has to be told its target: it reads one, and says
 * which layer the value came from.
 *
 * <p>What an operator types is therefore an <em>override</em> - an uneven handicap, or a different
 * target for one table - and it is recorded as a deviation rather than as the default. It also never
 * applies by accident: the local values are only in force when the override has actually been claimed
 * (see {@link #deviates}), so a goal left over from the last match cannot quietly become this
 * match's race.
 *
 * <p>Pure on purpose: no Android and no {@code org.json}, so {@code tools/check-race.sh} can compile
 * this file and assert the resolution order and the payload on a plain JVM - the only check this
 * repository can run without a device.
 */
final class Race {

    /** Nothing in force: neither the bracket nor the operator named a target. */
    static final int FROM_NONE = 0;
    /** The division's own target - {@code divisions.race_to}. */
    static final int FROM_DIVISION = 1;
    /** A per-match override - {@code matches.race_to}, which is how a longer final is set. */
    static final int FROM_MATCH = 2;
    /** The bracket's value, with the layer it came from unreadable (a server that names none). */
    static final int FROM_BRACKET = 3;
    /** Typed on the phone: the operator's own target, deliberately off the bracket's race. */
    static final int FROM_LOCAL = 4;
    /** Nobody set one, so the discipline's own default applies - the server's third layer. */
    static final int FROM_DEFAULT = 5;

    private Race() {
    }

    /** The race in force, the bracket's own race, and where each of them came from. */
    static final class Plan {
        /** The goal for the home side, and for the away side: 0 means that side has no goal. */
        final int homeGoal;
        final int awayGoal;
        /** The bracket's race for this match: 0 when neither the match nor its division carries one. */
        final int bracketRaceTo;
        /** Where {@link #bracketRaceTo} came from: one of the {@code FROM_*} constants. */
        final int bracketSource;
        /** Where the goals in force came from: the bracket's level, or {@link #FROM_LOCAL}. */
        final int source;

        Plan(int homeGoal, int awayGoal, int bracketRaceTo, int bracketSource, int source) {
            this.homeGoal = homeGoal;
            this.awayGoal = awayGoal;
            this.bracketRaceTo = bracketRaceTo;
            this.bracketSource = bracketSource;
            this.source = source;
        }

        /** True when the bracket names a race at all. */
        boolean hasBracket() {
            return bracketRaceTo > 0;
        }

        /** True when either side has a goal, which is what the board displays and prompts on. */
        boolean hasGoal() {
            return homeGoal > 0 || awayGoal > 0;
        }

        /** The single number the payload reports: the harder of the two goals, as it always has. */
        int raceTo() {
            return Math.max(homeGoal, awayGoal);
        }
    }

    /**
     * Resolves the goals for a match.
     *
     * @param matchRaceFrom  the layer the match payload names for its own {@code raceTo}
     *                       ({@code "match"}, {@code "division"} or {@code "default"}); null or
     *                       anything else when the payload does not name one
     * @param matchRaceTo    the match payload's resolved {@code raceTo}; 0 when it carries none
     *                       (absent, null, or a server that predates the column)
     * @param divisionRaceTo the division's own {@code raceTo}; 0 when it carries none
     * @param overrideSet    true once the operator has claimed a deviation from the bracket's race
     * @param localHome      the typed home goal, used only when {@code overrideSet}
     * @param localAway      the typed away goal, used only when {@code overrideSet}
     */
    static Plan plan(String matchRaceFrom, int matchRaceTo, int divisionRaceTo, boolean overrideSet,
                     int localHome, int localAway) {
        int bracket = bracketRace(matchRaceTo, divisionRaceTo);
        int layer = source(matchRaceFrom, matchRaceTo, divisionRaceTo);
        if (overrideSet) {
            if (bracket == 0 && localHome == 0 && localAway == 0) {
                // Overridden to nothing, with nothing to override: no target anywhere.
                return new Plan(0, 0, 0, FROM_NONE, FROM_NONE);
            }
            return new Plan(localHome, localAway, bracket, layer, FROM_LOCAL);
        }
        // No claimed override: the bracket's race is the goal for both sides, whatever local values
        // may still be lying around are not in force.
        return new Plan(bracket, bracket, bracket, layer, layer);
    }

    /** The bracket's race: the per-match override when the payload carries one, else the division's. */
    static int bracketRace(int matchRaceTo, int divisionRaceTo) {
        return matchRaceTo > 0 ? matchRaceTo : Math.max(0, divisionRaceTo);
    }

    /**
     * Which layer of the bracket set the race.
     *
     * <p>Two different {@code raceFrom} fields exist in this system, on different objects, and this
     * reads the server's one: the <b>match payload's</b> {@code raceFrom} names the layer that
     * supplied its {@code raceTo} - {@code "match"}, {@code "division"} or {@code "default"}
     * ({@code resolveRaceTo} in their {@code internal/api/games.go}, verified on branch
     * {@code sheet-stack}). The console's <b>saved sheet record</b> uses the same field name with a
     * different vocabulary ({@code bracket}/{@code local}/{@code override}); {@code "bracket"} is
     * accepted here too, since it means the same thing this app means by it - the bracket supplied
     * the race and the layer is not named.
     *
     * <p>Anything else is inferred from the two values, which reaches the same answer except for a
     * game default - and a race the payloads do not carry at all is no race.
     */
    static int source(String matchRaceFrom, int matchRaceTo, int divisionRaceTo) {
        if (bracketRace(matchRaceTo, divisionRaceTo) == 0) {
            return FROM_NONE;
        }
        if ("match".equals(matchRaceFrom)) {
            return FROM_MATCH;
        }
        if ("division".equals(matchRaceFrom)) {
            return FROM_DIVISION;
        }
        if ("default".equals(matchRaceFrom)) {
            return FROM_DEFAULT;
        }
        if ("bracket".equals(matchRaceFrom)) {
            return FROM_BRACKET;
        }
        return bracketSource(matchRaceTo, divisionRaceTo);
    }

    /**
     * The layer inferred from the two values, for a server that names no layer: the match payload's
     * value is already resolved, so a value the division does not have is the match's own override.
     */
    static int bracketSource(int matchRaceTo, int divisionRaceTo) {
        if (matchRaceTo <= 0) {
            return divisionRaceTo > 0 ? FROM_DIVISION : FROM_NONE;
        }
        if (divisionRaceTo <= 0) {
            // A race with no division value to compare against: the bracket set it, layer unknown.
            return FROM_BRACKET;
        }
        return matchRaceTo == divisionRaceTo ? FROM_DIVISION : FROM_MATCH;
    }

    /**
     * True when a typed pair actually deviates from the bracket's race, which is what makes it an
     * override. Typing the bracket's own number back in is not a deviation - it is the default - and
     * with no bracket race only a real target deviates from nothing.
     */
    static boolean deviates(int bracketRaceTo, int home, int away) {
        if (bracketRaceTo > 0) {
            return !(home == bracketRaceTo && away == bracketRaceTo);
        }
        return home > 0 || away > 0;
    }

    /**
     * The <b>layer</b> as it travels in the payload's {@code raceSource}: {@code "match"},
     * {@code "division"}, {@code "default"}, or {@code "none"} when the bracket names no race at all.
     * Empty when there is no layer to name - {@link #FROM_BRACKET} (the bracket supplied the race and
     * the server did not say which layer) and {@link #FROM_LOCAL} (the operator's own value, which is
     * an <em>origin</em>, not a layer, and travels in {@code raceOrigin}). A caller writes the field
     * only when this is non-empty, so an unnameable layer is absent rather than mislabelled.
     */
    static String name(int source) {
        switch (source) {
            case FROM_DIVISION:
                return "division";
            case FROM_MATCH:
                return "match";
            case FROM_DEFAULT:
                return "default";
            case FROM_NONE:
                return "none";
            default:
                return "";
        }
    }

    /**
     * 14.1 and 15-ball rotation are scored in balls, so their target reads "Points to 100"; every
     * other discipline is a race of games won, so it reads "Race to 7". This is the same split as
     * the web console's {@code Sheets.measure} ("balls" against "wins"), which is what decides
     * whether its sheet is a rack grid or a tally.
     */
    static boolean isPointGame(String game) {
        return "straight_14_1".equals(game) || "15ball_rotation".equals(game);
    }
}
