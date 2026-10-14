package com.kevin.score;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assertions for {@link Race} and {@link ResultPayload}, run by {@code tools/check-race.sh} on a
 * plain JVM.
 *
 * <p>This is the only check this repository can run without a device: there is no Gradle to host a
 * unit test and the UI harness needs an emulator, but the resolution and the payload are pure, so
 * they compile and run here. What it cannot check is anything a person looks at - the race line's
 * wording, the goal line under a name, the dialog - because those are resources rendered by the
 * platform. Those are listed as device-only in README.md.
 *
 * <p>Declared in the app's own package because {@link Race} and {@link ResultPayload} are
 * package-private, exactly as the app uses them.
 */
public final class RaceCheck {

    private static int assertions = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        plan("a match override wins over the division", "match", 9, 7, false, 0, 0, 9, 9, Race.FROM_MATCH, 9);
        plan("the division's own target, no override", "division", 7, 7, false, 0, 0, 7, 7, Race.FROM_DIVISION, 7);
        plan("a race with no division value to compare against", "", 7, 0, false, 0, 0, 7, 7, Race.FROM_BRACKET, 7);
        plan("a server that names no layer: the two values tell them apart",
                "", 9, 7, false, 0, 0, 9, 9, Race.FROM_MATCH, 9);
        plan("the match carries none, the division does", "", 0, 7, false, 0, 0, 7, 7, Race.FROM_DIVISION, 7);
        plan("nobody set one: the discipline's default", "default", 25, 25, false, 0, 0, 25, 25, Race.FROM_DEFAULT, 25);
        plan("the console's word for the same thing, in the server's place",
                "bracket", 25, 25, false, 0, 0, 25, 25, Race.FROM_BRACKET, 25);
        plan("a console word the server never sends does not corrupt the layer",
                "override", 9, 7, false, 0, 0, 9, 9, Race.FROM_MATCH, 9);

        plan("the bracket's race wins over a local goal that is not an override",
                "division", 7, 7, false, 5, 4, 7, 7, Race.FROM_DIVISION, 7);
        plan("the bracket's race wins over a stale local goal, match layer",
                "match", 9, 7, false, 5, 4, 9, 9, Race.FROM_MATCH, 9);

        plan("a claimed override is the goal in force", "division", 7, 7, true, 5, 4, 5, 4, Race.FROM_LOCAL, 7);
        plan("a claimed override beats a match override too", "match", 9, 7, true, 5, 5, 5, 5, Race.FROM_LOCAL, 9);
        plan("a claimed override to no goal at all, with a bracket race",
                "division", 7, 7, true, 0, 0, 0, 0, Race.FROM_LOCAL, 7);

        plan("nothing anywhere: the fallback", "", 0, 0, false, 0, 0, 0, 0, Race.FROM_NONE, 0);
        plan("a target typed by hand with no bracket race", "", 0, 0, true, 100, 100, 100, 100, Race.FROM_LOCAL, 0);
        plan("an override claimed with nothing typed and nothing on the bracket",
                "", 0, 0, true, 0, 0, 0, 0, Race.FROM_NONE, 0);
        plan("a layer named by a payload that carries no race at all",
                "match", 0, 0, false, 0, 0, 0, 0, Race.FROM_NONE, 0);

        section("the operator's typed pair is an override only when it deviates");
        same("typing the bracket's own number back", Race.deviates(7, 7, 7), false);
        same("a handicap off the bracket", Race.deviates(7, 5, 7), true);
        same("no goal, where the bracket has one", Race.deviates(7, 0, 0), true);
        same("a target with no bracket race", Race.deviates(0, 5, 0), true);
        same("nothing typed, nothing on the bracket", Race.deviates(0, 0, 0), false);

        section("the provenance names the payload carries");
        same("division", Race.name(Race.FROM_DIVISION), "division");
        same("match", Race.name(Race.FROM_MATCH), "match");
        same("default (nobody set one)", Race.name(Race.FROM_DEFAULT), "default");
        same("bracket (a layer we cannot name)", Race.name(Race.FROM_BRACKET), "bracket");
        same("local", Race.name(Race.FROM_LOCAL), "local");
        same("none", Race.name(Race.FROM_NONE), "none");

        section("the console's own word for the origin (sheet-payload.js: raceFrom)");
        same("the bracket's race", ResultPayload.origin(Race.plan("division", 7, 7, false, 0, 0)), "bracket");
        same("the bracket's race, match layer", ResultPayload.origin(Race.plan("match", 9, 7, false, 0, 0)), "bracket");
        same("the discipline's default", ResultPayload.origin(Race.plan("default", 25, 25, false, 0, 0)), "bracket");
        same("a typed goal that disagrees", ResultPayload.origin(Race.plan("division", 7, 7, true, 5, 4)), "override");
        same("a typed goal where the bracket named none", ResultPayload.origin(Race.plan("", 0, 0, true, 5, 4)), "local");
        same("no race anywhere", ResultPayload.origin(Race.plan("", 0, 0, false, 0, 0)), "unknown");

        section("the label the discipline implies");
        same("straight_14_1 is scored in points", Race.isPointGame("straight_14_1"), true);
        same("15ball_rotation is scored in points", Race.isPointGame("15ball_rotation"), true);
        same("9ball is a race of games", Race.isPointGame("9ball"), false);
        same("8ball is a race of games", Race.isPointGame("8ball"), false);
        same("10ball is a race of games", Race.isPointGame("10ball"), false);
        same("one_pocket is a race of games", Race.isPointGame("one_pocket"), false);
        same("bank_pool is a race of games", Race.isPointGame("bank_pool"), false);
        same("no game at all", Race.isPointGame(""), false);

        section("the payload, bracket race");
        Map<String, Object> bracket = payload(11, 7, "9ball", Race.plan("division", 7, 7, false, 0, 0));
        same("score", bracket.get("score"), "11-7");
        same("home", bracket.get("home"), 11);
        same("away", bracket.get("away"), 7);
        same("homeName", bracket.get("homeName"), "Avery");
        same("awayName", bracket.get("awayName"), "Blake");
        same("discipline", bracket.get("discipline"), "9ball");
        same("app", bracket.get("app"), "score-android");
        same("appVersion (the build that wrote it)", bracket.get("appVersion"), "1.4");
        same("payloadVersion (this record's revision)", bracket.get("payloadVersion"), 2);
        same("sheetKind (a tally, not a rack sheet)", bracket.get("sheetKind"), "tally");
        same("homeGoal", bracket.get("homeGoal"), 7);
        same("awayGoal", bracket.get("awayGoal"), 7);
        same("raceTo", bracket.get("raceTo"), 7);
        same("raceFrom (the console's word)", bracket.get("raceFrom"), "bracket");
        same("raceSource (the finer one)", bracket.get("raceSource"), "division");
        same("bracketRaceTo", bracket.get("bracketRaceTo"), 7);
        same("key order", keys(bracket),
                "score,home,away,homeName,awayName,discipline,app,appVersion,payloadVersion,sheetKind,"
                        + "homeGoal,awayGoal,raceTo,raceFrom,raceSource,bracketRaceTo");
        same("no rack sheet field", bracket.containsKey("racks"), false);

        section("the payload, a per-match override");
        Map<String, Object> matchLevel = payload(7, 0, "9ball", Race.plan("match", 9, 7, false, 0, 0));
        same("raceTo", matchLevel.get("raceTo"), 9);
        same("raceFrom", matchLevel.get("raceFrom"), "bracket");
        same("raceSource", matchLevel.get("raceSource"), "match");
        same("bracketRaceTo", matchLevel.get("bracketRaceTo"), 9);

        section("the payload, a hand-set race with no bracket race to disagree with");
        Map<String, Object> handSet = payload(5, 4, "9ball", Race.plan("", 0, 0, true, 5, 4));
        same("raceTo", handSet.get("raceTo"), 5);
        same("raceFrom", handSet.get("raceFrom"), "local");
        same("raceSource", handSet.get("raceSource"), "local");
        same("no bracketRaceTo", handSet.containsKey("bracketRaceTo"), false);

        section("the payload, a local override off a match override");
        Map<String, Object> local = payload(5, 4, "9ball", Race.plan("match", 9, 7, true, 5, 4));
        same("homeGoal", local.get("homeGoal"), 5);
        same("awayGoal", local.get("awayGoal"), 4);
        same("raceTo (the value in force)", local.get("raceTo"), 5);
        same("raceFrom", local.get("raceFrom"), "override");
        same("raceSource", local.get("raceSource"), "local");
        same("bracketRaceTo (what it deviated from)", local.get("bracketRaceTo"), 9);

        section("the payload, nothing from the bracket");
        Map<String, Object> none = payload(0, 0, "9ball", Race.plan("", 0, 0, false, 0, 0));
        same("no homeGoal", none.containsKey("homeGoal"), false);
        same("no awayGoal", none.containsKey("awayGoal"), false);
        same("no raceTo", none.containsKey("raceTo"), false);
        same("raceFrom", none.get("raceFrom"), "unknown");
        same("raceSource", none.get("raceSource"), "none");
        same("no bracketRaceTo", none.containsKey("bracketRaceTo"), false);
        same("the score is still there", none.get("score"), "0-0");
        same("and the marker still says tally", none.get("sheetKind"), "tally");

        section("the payload, a 14.1 tally (points, and no rack grid)");
        Map<String, Object> fourteen = payload(75, 40, "straight_14_1",
                Race.plan("default", 100, 100, false, 0, 0));
        same("discipline", fourteen.get("discipline"), "straight_14_1");
        same("raceTo runs past two digits", fourteen.get("raceTo"), 100);
        same("raceSource", fourteen.get("raceSource"), "default");
        same("sheetKind says tally, so the grid is never assumed", fourteen.get("sheetKind"), "tally");
        same("still no rack sheet field", fourteen.containsKey("racks"), false);
        same("nor a rack count", fourteen.containsKey("racksPlayed"), false);

        section("the payload, a tournament with no game set");
        Map<String, Object> unknown = payload(3, 0, null, Race.plan("", 0, 0, false, 0, 0));
        same("no discipline", unknown.containsKey("discipline"), false);
        same("no empty discipline", unknown.containsKey("discipline"), false);
        same("app still named", unknown.get("app"), "score-android");
        same("no appVersion when the build is unknown",
                payloadWith(null, 3, 0).containsKey("appVersion"), false);

        System.out.println();
        if (failures == 0) {
            System.out.println("race check: all " + assertions + " assertions pass");
            return;
        }
        System.out.println("race check: " + failures + " of " + assertions + " assertions FAILED");
        System.exit(1);
    }

    // ------------------------------------------------------------------ the assertions

    private static void plan(String label, String matchRaceFrom, int matchRaceTo, int divisionRaceTo,
                             boolean overrideSet, int localHome, int localAway, int wantHome,
                             int wantAway, int wantSource, int wantBracket) {
        Race.Plan p = Race.plan(matchRaceFrom, matchRaceTo, divisionRaceTo, overrideSet,
                localHome, localAway);
        String setup = "match " + matchRaceTo + (matchRaceFrom.isEmpty() ? "" : "/" + matchRaceFrom)
                + ", division " + divisionRaceTo
                + (overrideSet ? ", typed " + localHome + "/" + localAway : ", nothing typed");
        same(label + " [" + setup + "] - homeGoal", p.homeGoal, wantHome);
        same(label + " [" + setup + "] - awayGoal", p.awayGoal, wantAway);
        same(label + " [" + setup + "] - provenance", Race.name(p.source), Race.name(wantSource));
        same(label + " [" + setup + "] - bracketRaceTo", p.bracketRaceTo, wantBracket);
    }

    private static Map<String, Object> payload(int home, int away, String discipline, Race.Plan race) {
        return payloadWith(discipline, home, away, "1.4", race);
    }

    /** A payload from a build whose version name we could not read. */
    private static Map<String, Object> payloadWith(String discipline, int home, int away) {
        return payloadWith(discipline, home, away, null, Race.plan("", 0, 0, false, 0, 0));
    }

    private static Map<String, Object> payloadWith(String discipline, int home, int away,
                                                   String appVersion, Race.Plan race) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        List<ResultPayload.Field> fields =
                ResultPayload.fields("Avery", "Blake", home, away, discipline, appVersion, race);
        for (ResultPayload.Field field : fields) {
            map.put(field.key, field.value);
        }
        return map;
    }

    private static String keys(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        for (String key : map.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        return sb.toString();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void same(String label, Object got, Object want) {
        assertions++;
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s %-72s %s%n", ok ? "ok" : "FAIL", label,
                ok ? String.valueOf(describe(got)) : "got " + describe(got) + ", want " + describe(want));
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return String.valueOf(value);
    }
}
