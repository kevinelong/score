package com.kevin.score;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Single-screen score keeper.
 *
 * HOME occupies the left half, AWAY the right half. Each side owns a three-digit score,
 * a decrement button, an increment button and a reset button. Resting a finger on a
 * team name for {@link #DWELL_MS} milliseconds opens the rename dialog.
 *
 * <p>The far half is drawn rotated so the player sitting opposite reads it upright, and every
 * discipline the server supports fits: races of games won, and the point games (14.1, 15-ball
 * rotation) whose targets run past 99.
 */
public class MainActivity extends Activity {

    private static final int HOME = 0;
    private static final int AWAY = 1;

    private static final int MIN_SCORE = 0;
    /** Three digits: 14.1 is played to 100+ and 15-ball rotation's targets run to 150. */
    private static final int MAX_SCORE = 999;

    private static final int MAX_NAME_LENGTH = 16;

    /** Finger dwell on a team name that opens the rename dialog. */
    private static final long DWELL_MS = 900L;

    private static final String[] DEFAULT_NAME = {"HOME", "AWAY"};

    private static final int[] ID_PLUS = {R.id.home_plus, R.id.away_plus};
    private static final int[] ID_MINUS = {R.id.home_minus, R.id.away_minus};
    private static final int[] ID_RESET = {R.id.home_reset, R.id.away_reset};
    private static final int[] ID_SCORE = {R.id.home_score, R.id.away_score};
    private static final int[] ID_NAME = {R.id.home_name, R.id.away_name};
    private static final int[] ID_GOAL = {R.id.home_goal, R.id.away_goal};

    private static final String PREFS = "score";
    /** Set once the first-run gesture hint has been shown and dismissed. */
    private static final String PREF_HINT = "hint_seen";
    /** Whether the far half is drawn rotated for the player sitting opposite. */
    private static final String PREF_MIRROR = "mirror_away";
    /** Per-tournament memory of the match last scored, so an operator's table is the default. */
    private static final String PREF_LAST_MATCH = "last_match_";

    private static final String STATE_SCORES = "scores";
    private static final String STATE_NAMES = "names";
    private static final String STATE_LINK = "link";
    private static final String STATE_RACE = "race";
    private static final String STATE_RACE_FROM = "race_from";
    private static final String STATE_RACE_OVERRIDE = "race_override";
    private static final String STATE_RACE_OVERRIDE_SET = "race_override_set";

    private final int[] score = new int[2];
    private final String[] name = {DEFAULT_NAME[HOME], DEFAULT_NAME[AWAY]};
    private final TextView[] scoreView = new TextView[2];
    private final TextView[] nameView = new TextView[2];
    /** The "race to N" line under each name; hidden when that side has no goal. */
    private final TextView[] goalView = new TextView[2];
    private final View[] plusButton = new View[2];
    private final View[] minusButton = new View[2];
    private final View[] resetButton = new View[2];

    // ---- linked match mode: set only when the app is opened from a tournament scoring link

    private View linkedBar;
    private TextView linkedStatus;
    private TextView linkedNote;
    private TextView linkedAction;
    private Uri linkUri;
    private TournamentApi.Link link;
    private TournamentApi api;
    private TournamentApi.Tournament tournament;
    private TournamentApi.Match match;
    private String homeEntrantId;
    private String awayEntrantId;
    /** False until the link has resolved into a match; the action then retries the load. */
    private boolean loaded;
    /** True once the result is on the server; the action then offers a correction instead. */
    private boolean submitted;
    /** Reused across retries, so a submit that actually landed is never recorded twice. */
    private String submitKey;
    private boolean busy;
    /**
     * The race in force, and where it came from. 0 means "no goal", and uneven goals are a handicap:
     * the bracket's race is the default for both sides, and the operator's typed goals are an
     * explicit deviation from it (see {@link Race}).
     */
    private final int[] goal = {0, 0};
    /** Set once a reached goal has been acknowledged, so the prompt does not nag on every point. */
    private final boolean[] goalPrompted = {false, false};
    /**
     * The bracket's race for the match on the board, as the two payloads report it: the match's
     * resolved {@code raceTo} and the layer that supplied it ({@code raceFrom}), plus the division's
     * own. 0 in either value means that payload carried none.
     */
    private int matchRaceTo;
    private String matchRaceFrom = "";
    private int divisionRaceTo;
    /** The tournament's divisions, so a match's division can lend it its own race. */
    private List<TournamentApi.Division> divisions = new java.util.ArrayList<TournamentApi.Division>();
    /** True once the operator has claimed a deviation from the bracket's race. */
    private boolean raceOverridden;
    /** The typed deviation, per side: 0 means that side has no goal. */
    private final int[] goalOverride = {0, 0};
    /** The resolution: the goals in force, the bracket's race, and each one's provenance. */
    private Race.Plan race = Race.plan("", 0, 0, false, 0, 0);
    private TextView linkedRace;
    /** The "TABLE 4" header: venues call matches by table, so the number leads the bar. */
    private TextView linkedTable;
    /** True for a tournament that is not open for anonymous scoring: the board scores, nothing submits. */
    private boolean viewOnly;

    // ---- plain-scoreboard extras: a hint, a mirror, and remembered preferences

    private SharedPreferences prefs;
    /** First-run gesture hint, hidden for good once the operator has scored something. */
    private TextView hint;
    /** The far half, rotated 180 degrees so the player sitting opposite reads it upright. */
    private View awayPanel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A pool room is dim and the phone lies flat on a table, so hold full brightness for as long
        // as the app is in front. It is a window attribute, so leaving the app restores the device's.
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = 1f;
        getWindow().setAttributes(attrs);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        restore(state);

        for (int side = HOME; side <= AWAY; side++) {
            scoreView[side] = (TextView) findViewById(ID_SCORE[side]);
            nameView[side] = (TextView) findViewById(ID_NAME[side]);
            goalView[side] = (TextView) findViewById(ID_GOAL[side]);
            plusButton[side] = findViewById(ID_PLUS[side]);
            minusButton[side] = findViewById(ID_MINUS[side]);
            resetButton[side] = findViewById(ID_RESET[side]);

            attachScoreSizing(scoreView[side]);
            attachDwell(nameView[side], side);
            bindStep(plusButton[side], side, 1);
            bindStep(minusButton[side], side, -1);
            bindStep(scoreView[side], side, 1);   // tapping the big score adds a point
            bindReset(resetButton[side], side);
            refresh(side);
        }

        ((SwipePanel) findViewById(R.id.root)).setOnSwipeListener(new SwipePanel.OnSwipeListener() {
            @Override
            public void onSwipe(boolean homeSide, int delta) {
                int side = homeSide ? HOME : AWAY;
                if (!homeSide && mirrored()) {
                    // The far half is drawn rotated, so that player's "up" is the screen's down.
                    // Flip it back: up always means +1 in the frame of whoever reads that half.
                    delta = -delta;
                }
                scoreView[side].performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                step(side, delta);
            }
        });

        linkedBar = findViewById(R.id.linked_bar);
        linkedStatus = (TextView) findViewById(R.id.linked_status);
        linkedNote = (TextView) findViewById(R.id.linked_note);
        linkedAction = (TextView) findViewById(R.id.linked_action);
        findViewById(R.id.linked_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveLinkedMode();
            }
        });
        linkedAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                if (!loaded) {
                    if (linkUri != null) {
                        openLink(linkUri);
                    }
                    return;
                }
                if (submitted) {
                    askForCorrectionReason();
                } else {
                    askWhoWon();
                }
            }
        });

        linkedRace = (TextView) findViewById(R.id.linked_race);
        linkedRace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGoalsDialog();
            }
        });
        linkedTable = (TextView) findViewById(R.id.linked_table);

        // The far half reads upright for the player sitting opposite. A lone scorekeeper flips it
        // back with a long press on the centre bar, and the choice sticks across launches.
        awayPanel = findViewById(R.id.away_panel);
        applyMirror(prefs.getBoolean(PREF_MIRROR, true));
        View divider = findViewById(R.id.divider);
        divider.setContentDescription(getString(R.string.cd_flip));
        divider.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean flip = !mirrored();
                applyMirror(flip);
                prefs.edit().putBoolean(PREF_MIRROR, flip).apply();
                Toast.makeText(MainActivity.this,
                        flip ? R.string.mirror_on : R.string.mirror_off, Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        hint = (TextView) findViewById(R.id.hint);
        if (prefs.getBoolean(PREF_HINT, false)) {
            hint.setVisibility(View.GONE);
        } else {
            hint.setVisibility(View.VISIBLE);
            hint.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissHint();
                }
            });
        }

        handleLink(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back on a linked board: re-read the match, so a director's correction or a change made on
        // another phone shows up. The operator's own tallies are left exactly as they are.
        if (loaded && match != null && tournament != null && !busy) {
            inBackground(new Runnable() {
                @Override
                public void run() {
                    refreshMatch();
                }
            });
        }
    }

    /** Rotates the far half 180 degrees. Its swipe direction is mirrored with it. */
    private void applyMirror(boolean mirrored) {
        awayPanel.setRotation(mirrored ? 180f : 0f);
    }

    private boolean mirrored() {
        return awayPanel != null && awayPanel.getRotation() == 180f;
    }

    /** The hint has done its job the moment the operator scores something. */
    private void dismissHint() {
        if (hint == null || hint.getVisibility() != View.VISIBLE) {
            return;
        }
        hint.setVisibility(View.GONE);
        prefs.edit().putBoolean(PREF_HINT, true).apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Only a real link re-opens the board: tapping the launcher icon on a live instance
        // carries no data, and re-opening would silently throw away the tallies in progress.
        if (intent != null && intent.getData() != null) {
            handleLink(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (linkUri != null) {
            leaveLinkedMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putIntArray(STATE_SCORES, new int[] {score[HOME], score[AWAY]});
        out.putStringArray(STATE_NAMES, new String[] {name[HOME], name[AWAY]});
        if (linkUri != null) {
            out.putString(STATE_LINK, linkUri.toString());
        }
        // The bracket's race and the operator's deviation are inputs, not the resolved goals: the
        // goals are derived (Race.plan checks the resolution order), so restoring the inputs is what
        // keeps a rotation or a process death from inventing a race of its own.
        out.putIntArray(STATE_RACE, new int[] {matchRaceTo, divisionRaceTo});
        out.putString(STATE_RACE_FROM, matchRaceFrom);
        out.putIntArray(STATE_RACE_OVERRIDE, new int[] {goalOverride[HOME], goalOverride[AWAY]});
        out.putBoolean(STATE_RACE_OVERRIDE_SET, raceOverridden);
    }

    // ---------------------------------------------------------------- state

    private void restore(Bundle state) {
        if (state == null) {
            return;
        }
        int[] savedScores = state.getIntArray(STATE_SCORES);
        if (savedScores != null && savedScores.length == 2) {
            score[HOME] = clamp(savedScores[HOME]);
            score[AWAY] = clamp(savedScores[AWAY]);
        }
        String[] savedNames = state.getStringArray(STATE_NAMES);
        if (savedNames != null && savedNames.length == 2) {
            name[HOME] = sanitize(savedNames[HOME], HOME);
            name[AWAY] = sanitize(savedNames[AWAY], AWAY);
        }
        String savedLink = state.getString(STATE_LINK);
        if (savedLink != null) {
            linkUri = Uri.parse(savedLink);
        }
        int[] savedRace = state.getIntArray(STATE_RACE);
        if (savedRace != null && savedRace.length == 2) {
            matchRaceTo = Math.max(0, savedRace[HOME]);
            divisionRaceTo = Math.max(0, savedRace[AWAY]);
        }
        String savedFrom = state.getString(STATE_RACE_FROM);
        if (savedFrom != null) {
            matchRaceFrom = savedFrom;
        }
        int[] savedOverride = state.getIntArray(STATE_RACE_OVERRIDE);
        if (savedOverride != null && savedOverride.length == 2) {
            goalOverride[HOME] = clamp(savedOverride[HOME]);
            goalOverride[AWAY] = clamp(savedOverride[AWAY]);
        }
        raceOverridden = state.getBoolean(STATE_RACE_OVERRIDE_SET, false);
        // The views do not exist yet, so only the resolution runs here; the binding loop paints it.
        recomputeRace();
    }

    /** Clamps a score into the two-digit range the display can show. */
    private static int clamp(int value) {
        if (value < MIN_SCORE) {
            return MIN_SCORE;
        }
        return value > MAX_SCORE ? MAX_SCORE : value;
    }

    /** Falls back to the default team name when a name is blank. */
    private static String sanitize(String raw, int side) {
        if (raw == null) {
            return DEFAULT_NAME[side];
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? DEFAULT_NAME[side] : trimmed;
    }

    private static String format(int value) {
        return String.format(Locale.US, "%02d", value);
    }

    // --------------------------------------------------------------- scoring

    private void bindStep(final View button, final int side, final int delta) {
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                step(side, delta);
            }
        });
    }

    /** Moves one side by {@code delta}, clamped to the digits the display can show. */
    private void step(int side, int delta) {
        int next = clamp(score[side] + delta);
        if (next == score[side]) {
            return;
        }
        score[side] = next;
        dismissHint();
        refresh(side);
        checkGoal(side);
    }

    private void bindReset(final View button, final int side) {
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (score[side] == MIN_SCORE) {
                    return;
                }
                if (linkedBar != null && linkedBar.getVisibility() == View.VISIBLE && loaded) {
                    // In a linked match this discards a tally that is on its way to the server.
                    confirmReset(side);
                    return;
                }
                reset(side);
            }
        });
    }

    private void confirmReset(final int side) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_title, name[side]))
                .setMessage(getString(R.string.reset_message, name[side]))
                .setPositiveButton(R.string.reset_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        reset(side);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Back to zero. Dropping below a goal re-arms that side's prompt, which is deliberate. */
    private void reset(int side) {
        score[side] = MIN_SCORE;
        dismissHint();
        refresh(side);
        checkGoal(side);
    }

    private void refresh(int side) {
        scoreView[side].setText(format(score[side]));
        nameView[side].setText(name[side]);

        if (goalView[side] != null) {
            if (goal[side] > 0) {
                boolean matchPoint = score[side] == goal[side] - 1;
                // A goal the operator typed says so on the line under the name too, so the board
                // never looks like it is playing to the bracket's race when it is not.
                boolean local = race.source == Race.FROM_LOCAL;
                int text = matchPoint
                        ? (local ? R.string.linked_goal_match_point_local
                                 : R.string.linked_goal_match_point)
                        : (local ? R.string.linked_goal_short_local
                                 : R.string.linked_goal_short);
                goalView[side].setText(getString(text, goalUnit(), goal[side]));
                goalView[side].setVisibility(View.VISIBLE);
            } else {
                goalView[side].setVisibility(View.GONE);
            }
        }

        String label = name[side];
        nameView[side].setContentDescription(getString(R.string.cd_name, label));
        scoreView[side].setContentDescription(goal[side] > 0
                ? getString(R.string.cd_score_goal, label, format(score[side]), goalUnit(), goal[side])
                : getString(R.string.cd_score, label, format(score[side])));
        plusButton[side].setContentDescription(getString(R.string.cd_plus, label));
        minusButton[side].setContentDescription(getString(R.string.cd_minus, label));
        resetButton[side].setContentDescription(getString(R.string.cd_reset, label));
        if (linkedBar != null && linkedBar.getVisibility() == View.VISIBLE) {
            nameView[side].setContentDescription(label);
        }
    }

    // ---------------------------------------------------------- score sizing

    private void attachScoreSizing(final TextView view) {
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                resizeScore(view);
            }
        });
    }

    /** Keeps the score as large as the box allows, in any orientation and at any digit count. */
    private void resizeScore(TextView view) {
        int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int height = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        CharSequence shown = view.getText();
        // At least two digits, so a fresh board keeps its 00 look; a point game grows to three.
        int digits = Math.max(2, shown == null ? 0 : shown.length());
        float byHeight = height * 0.75f;
        float byWidth = width * 0.95f / (digits * 0.6f); // a monospace digit is about 0.6 em wide
        float size = Math.min(byHeight, byWidth);
        if (Math.abs(size - view.getTextSize()) < 1f) {
            return;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    // ------------------------------------------------------- linked match mode

    /** Opens whatever link the intent carries, or the one restored after process death. */
    private void handleLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            data = linkUri;
        }
        if (data != null) {
            openLink(data);
        }
    }

    private void openLink(Uri uri) {
        final TournamentApi.Link parsed;
        try {
            parsed = TournamentApi.parseLink(uri);
        } catch (TournamentApi.ApiError e) {
            Toast.makeText(this, e.label(), Toast.LENGTH_LONG).show();
            return;
        }
        linkUri = uri;
        link = parsed;
        api = TournamentApi.forLink(parsed);
        tournament = null;
        match = null;
        homeEntrantId = null;
        awayEntrantId = null;
        loaded = false;
        submitted = false;
        submitKey = null;
        busy = false;
        viewOnly = false;
        matchRaceTo = 0;
        matchRaceFrom = "";
        divisionRaceTo = 0;
        divisions = new java.util.ArrayList<TournamentApi.Division>();
        resetOverride();
        recomputeRace();
        linkedTable.setVisibility(View.GONE);

        linkedBar.setVisibility(View.VISIBLE);
        linkedNote.setVisibility(View.GONE);
        updateRaceLine();
        setStatus(getString(R.string.linked_loading));
        setAction(getString(R.string.linked_submit), false);

        inBackground(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /** Reads the tournament, its divisions, entrants and matches, then decides which match to score. */
    private void load() {
        try {
            final TournamentApi.TournamentRead found = api.readTournament(link.tournament);
            if (found.tournament == null) {
                throw new TournamentApi.ApiError(0, "bad_response", "No tournament in the response");
            }
            final TournamentApi.Tournament found1 = found.tournament;
            final List<TournamentApi.Entrant> entrants = api.entrants(found1.id);
            final List<TournamentApi.Match> matches = api.matches(found1.id);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tournament = found1;
                    viewOnly = false;
                    // The divisions carry their own race targets, which is how a division's target is
                    // told from a per-match override.
                    divisions = found.divisions;
                    chooseMatch(entrants, matches);
                }
            });
        } catch (final TournamentApi.ApiError e) {
            // A tournament that is not open for anonymous scoring rejects the scoring endpoints. The
            // anonymous read still answers, so fall back to it: names, table and state, minus the
            // ability to submit, with the reason on screen. Without this the app showed an error
            // and a blank board.
            try {
                loadPublic();
                return;
            } catch (TournamentApi.ApiError noPublicRead) {
                // Keep the scoring error: it is the more specific one.
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    loaded = false;
                    setStatus(e.label());
                    setAction(getString(R.string.linked_retry), true);
                }
            });
        }
    }

    /** The anonymous read of a tournament that is not open for scoring. */
    private void loadPublic() throws TournamentApi.ApiError {
        final TournamentApi.PublicBoard board = api.publicBoard(link.tournament);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tournament = board.tournament;
                viewOnly = true;
                chooseMatch(board.entrants, board.matches);
            }
        });
    }

    /**
     * Prefers a match actually being played, then one waiting to start, then one already recorded -
     * the link names the tournament but not the match, which is how the director's QR works too.
     */
    private void chooseMatch(List<TournamentApi.Entrant> entrants, List<TournamentApi.Match> matches) {
        List<TournamentApi.Match> candidates = withState(matches, "in_progress", "reopened");
        if (candidates.isEmpty()) {
            candidates = withState(matches, "scheduled", "assigned");
        }
        if (candidates.isEmpty()) {
            candidates = withState(matches, "completed");
        }
        if (candidates.isEmpty()) {
            setStatus(tournament.isScorable()
                    ? getString(R.string.linked_no_matches)
                    : getString(R.string.linked_not_started));
            return;
        }
        // A bracket that has advanced leaves downstream matches with one side still empty ("TBD").
        // Prefer a match whose two entrants are known, so the app never offers an unscoreable one
        // while a real match is waiting.
        List<TournamentApi.Match> ready = withBothSides(candidates);
        if (!ready.isEmpty()) {
            candidates = ready;
        }
        // An operator works one table all night: the match last scored for this tournament leads the
        // picker. It is re-validated by this very fetch, so a stale id simply misses.
        String remembered = prefs.getString(PREF_LAST_MATCH + tournament.id, null);
        if (remembered != null && candidates.size() > 1) {
            candidates = withFirst(candidates, remembered);
        }
        if (candidates.size() == 1) {
            applyMatch(entrants, candidates.get(0));
            return;
        }
        final List<TournamentApi.Match> options = candidates;
        final CharSequence[] labels = new CharSequence[options.size()];
        for (int i = 0; i < options.size(); i++) {
            TournamentApi.Match m = options.get(i);
            labels[i] = nameOf(entrants, m.entrantAId) + " vs " + nameOf(entrants, m.entrantBId)
                    + " \u2014 " + m.describe();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.linked_pick_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyMatch(entrants, options.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyMatch(List<TournamentApi.Entrant> entrants, TournamentApi.Match chosen) {
        match = chosen;
        homeEntrantId = emptyToNull(chosen.entrantAId);
        awayEntrantId = emptyToNull(chosen.entrantBId);
        name[HOME] = nameOf(entrants, homeEntrantId);
        name[AWAY] = nameOf(entrants, awayEntrantId);
        score[HOME] = MIN_SCORE;
        score[AWAY] = MIN_SCORE;
        loaded = true;
        // The race comes from the bracket: the match payload's resolved value and the layer that
        // supplied it, plus its division's own so the layers can be told apart even when the payload
        // does not name one. A goal typed for the last match is not in force here - the bracket's
        // race is the default for every ordinary match.
        matchRaceTo = chosen.raceTo;
        matchRaceFrom = chosen.raceFrom;
        divisionRaceTo = divisionRaceOf(chosen);
        resetOverride();
        recomputeRace();
        goalPrompted[HOME] = false;
        goalPrompted[AWAY] = false;
        updateRaceLine();
        submitted = chosen.isComplete();
        submitKey = null;
        refresh(HOME);
        refresh(AWAY);
        prefs.edit().putString(PREF_LAST_MATCH + tournament.id, chosen.id).apply();

        showTable(chosen);
        setStatus(tournament.name + " \u00b7 " + chosen.describe());
        if (viewOnly) {
            // Read-only: the board still tallies, but nothing here can be sent to the server.
            linkedNote.setText(R.string.linked_view_only);
            linkedNote.setVisibility(View.VISIBLE);
            setAction(getString(R.string.linked_submit), false);
        } else if (submitted) {
            String winner = winnerNameFor(chosen.winnerEntrantId);
            linkedNote.setText(winner == null
                    ? getString(R.string.linked_recorded_anonymous)
                    : getString(R.string.linked_recorded_name, winner));
            linkedNote.setVisibility(View.VISIBLE);
            setAction(getString(R.string.linked_correct), true);
        } else {
            linkedNote.setVisibility(View.GONE);
            setAction(getString(R.string.linked_submit), homeEntrantId != null && awayEntrantId != null);
        }
    }

    /** The table number, when the match has one: it is the thing the players are listening for. */
    private void showTable(TournamentApi.Match m) {
        String ref = emptyToNull(m.tableRef);
        if (ref == null) {
            linkedTable.setVisibility(View.GONE);
            return;
        }
        // A bare number gets the word in front; anything already labelled ("Table 4") is left alone.
        linkedTable.setText(ref.matches("\\d+") ? getString(R.string.linked_table_num, ref) : ref);
        linkedTable.setVisibility(View.VISIBLE);
    }

    /** Point games are played to a points target; the rest to a race of games. */
    private String goalUnit() {
        String game = tournament == null ? "" : tournament.game;
        return Race.isPointGame(game) ? getString(R.string.unit_points) : getString(R.string.unit_race);
    }

    private static List<TournamentApi.Match> withFirst(List<TournamentApi.Match> matches, String id) {
        List<TournamentApi.Match> out = new java.util.ArrayList<TournamentApi.Match>();
        for (TournamentApi.Match m : matches) {
            if (m.id.equals(id)) {
                out.add(m);
            }
        }
        for (TournamentApi.Match m : matches) {
            if (!m.id.equals(id)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Re-reads the match the board is on and reflects a state change - a director's correction, or
     * another phone submitting it - without touching the operator's tallies or their match choice.
     */
    private void refreshMatch() {
        final String matchId = match.id;
        final String tournamentId = tournament.id;
        final String tournamentName = tournament.name;
        try {
            final TournamentApi.Match fresh = find(api.matches(tournamentId), matchId);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (busy) {
                        return;
                    }
                    if (fresh == null) {
                        setStatus(tournamentName + " \u00b7 " + getString(R.string.linked_match_gone));
                        return;
                    }
                    match = fresh;
                    showTable(fresh);
                    setStatus(tournamentName + " \u00b7 " + fresh.describe());
                    if (fresh.raceTo != matchRaceTo || !fresh.raceFrom.equals(matchRaceFrom)) {
                        // A director changed the race, or set a longer target for this final: follow
                        // it, and keep whatever the operator typed on top of it.
                        matchRaceTo = fresh.raceTo;
                        matchRaceFrom = fresh.raceFrom;
                        divisionRaceTo = divisionRaceOf(fresh);
                        applyRace();
                    }
                    if (viewOnly) {
                        return;
                    }
                    if (fresh.isComplete() && !submitted) {
                        submitted = true;
                        String winner = winnerNameFor(fresh.winnerEntrantId);
                        linkedNote.setText(winner == null
                                ? getString(R.string.linked_recorded_anonymous)
                                : getString(R.string.linked_recorded_name, winner));
                        linkedNote.setVisibility(View.VISIBLE);
                        setAction(getString(R.string.linked_correct), true);
                    } else if (!fresh.isComplete() && submitted) {
                        submitted = false;
                        linkedNote.setVisibility(View.GONE);
                        setAction(getString(R.string.linked_submit),
                                homeEntrantId != null && awayEntrantId != null);
                    }
                }
            });
        } catch (TournamentApi.ApiError ignored) {
            // Offline or asleep: the board keeps working, it just has no fresh state to show.
        }
    }

    /** The server wants a winner: the tallies ride along, the tap decides who won. */
    private void askWhoWon() {
        String body = getString(R.string.linked_tallies, name[HOME], format(score[HOME]),
                name[AWAY], format(score[AWAY]))
                + "\n\n" + getString(R.string.linked_winner_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.linked_winner_title)
                .setMessage(body)
                .setPositiveButton(name[HOME], new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        submit(HOME);
                    }
                })
                .setNegativeButton(name[AWAY], new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        submit(AWAY);
                    }
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void submit(int winnerSide) {
        final String winnerId = winnerSide == HOME ? homeEntrantId : awayEntrantId;
        final String loserId = winnerSide == HOME ? awayEntrantId : homeEntrantId;
        if (winnerId == null || loserId == null) {
            setStatus(getString(R.string.linked_no_matches));
            return;
        }
        if (submitKey == null) {
            submitKey = UUID.randomUUID().toString();
        }
        final String key = submitKey;
        final JSONObject scores = buildScores();
        final TournamentApi.Match target = match;
        final TournamentApi.Tournament t = tournament;
        setBusy(getString(R.string.linked_recording));

        inBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    if (target.needsStart()) {
                        api.assign(t.id, target.id);
                        api.start(t.id, target.id);
                    }
                    api.submitResult(t.id, target.id, winnerId, loserId, scores, key);
                    // Re-read: the match object in the submit response does not carry the winner.
                    final TournamentApi.Match updated = find(api.matches(t.id), target.id);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onRecorded(updated, winnerId);
                        }
                    });
                } catch (final TournamentApi.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onRecordFailed(e, t, target);
                        }
                    });
                }
            }
        });
    }

    private void onRecorded(TournamentApi.Match updated, String winnerId) {
        if (updated != null) {
            match = updated;
        }
        submitted = true;
        submitKey = null;
        setBusy(null);
        setStatus(getString(R.string.linked_recorded, winnerNameFor(winnerId),
                format(score[HOME]), format(score[AWAY])));
        linkedNote.setText(R.string.linked_recorded_anonymous);
        linkedNote.setVisibility(View.VISIBLE);
        setAction(getString(R.string.linked_correct), true);
    }

    private void onRecordFailed(final TournamentApi.ApiError e, final TournamentApi.Tournament t,
                               final TournamentApi.Match target) {
        setBusy(null);
        if (e.isConflict()) {
            // Someone else finished this match: show what the server now holds rather than an error.
            inBackground(new Runnable() {
                @Override
                public void run() {
                    TournamentApi.Match fresh = null;
                    try {
                        fresh = find(api.matches(t.id), target.id);
                    } catch (TournamentApi.ApiError ignored) {
                        // fall through to the error path
                    }
                    final TournamentApi.Match updated = fresh;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (updated != null && updated.isComplete()) {
                                onRecorded(updated, updated.winnerEntrantId);
                            } else {
                                setStatus(e.label());
                                setAction(getString(R.string.linked_retry), true);
                            }
                        }
                    });
                }
            });
            return;
        }
        setStatus(e.label());
        if (e.isOffline()) {
            linkedNote.setText(R.string.linked_offline_retry);
            linkedNote.setVisibility(View.VISIBLE);
        }
        // The same idempotency key is kept, so a retry cannot record the result twice.
        setAction(getString(R.string.linked_retry), true);
    }

    private void askForCorrectionReason() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(R.string.linked_reopen_default);
        input.setSelectAllOnFocus(true);
        int pad = dp(24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.linked_reopen_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String reason = input.getText().toString().trim();
                        reopen(reason.isEmpty() ? getString(R.string.linked_reopen_default) : reason);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A reason is mandatory server-side; the result is superseded, not deleted. */
    private void reopen(final String reason) {
        final TournamentApi.Tournament t = tournament;
        final TournamentApi.Match target = match;
        setBusy(getString(R.string.linked_recording));
        inBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    api.reopen(t.id, target.id, reason, UUID.randomUUID().toString());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            submitted = false;
                            submitKey = null;
                            setBusy(null);
                            setStatus(getString(R.string.linked_reopened));
                            linkedNote.setVisibility(View.GONE);
                            setAction(getString(R.string.linked_submit), true);
                        }
                    });
                } catch (final TournamentApi.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(null);
                            setStatus(e.label());
                            setAction(getString(R.string.linked_correct), true);
                        }
                    });
                }
            }
        });
    }

    /** Back to a plain scoreboard: nothing of the match survives, and nothing is submitted. */
    private void leaveLinkedMode() {
        linkedBar.setVisibility(View.GONE);
        linkUri = null;
        link = null;
        api = null;
        tournament = null;
        match = null;
        homeEntrantId = null;
        awayEntrantId = null;
        loaded = false;
        submitted = false;
        submitKey = null;
        busy = false;
        score[HOME] = MIN_SCORE;
        score[AWAY] = MIN_SCORE;
        matchRaceTo = 0;
        matchRaceFrom = "";
        divisionRaceTo = 0;
        divisions = new java.util.ArrayList<TournamentApi.Division>();
        resetOverride();
        recomputeRace();
        goalPrompted[HOME] = false;
        goalPrompted[AWAY] = false;
        updateRaceLine();
        name[HOME] = DEFAULT_NAME[HOME];
        name[AWAY] = DEFAULT_NAME[AWAY];
        refresh(HOME);
        refresh(AWAY);
    }

    // ------------------------------------------------------------- race goals

    /**
     * Resolves what the board plays to, from the inputs only: the two bracket values the server
     * reports, and the operator's own goal when they have claimed one. No views, so it also runs
     * while the state is being restored. The order is the whole point, and it lives in {@link Race}
     * where tools/check-race.sh can assert it.
     */
    private void recomputeRace() {
        race = Race.plan(matchRaceFrom, matchRaceTo, divisionRaceTo, raceOverridden,
                goalOverride[HOME], goalOverride[AWAY]);
        goal[HOME] = race.homeGoal;
        goal[AWAY] = race.awayGoal;
    }

    /** Re-resolves and repaints. Deliberately does not run the match-over prompt: a race that
     *  arrives from the server is not a point the operator just scored. */
    private void applyRace() {
        recomputeRace();
        updateRaceLine();
        refresh(HOME);
        refresh(AWAY);
    }

    /** Drops the operator's deviation, so the bracket's race applies again. Inputs only. */
    private void resetOverride() {
        raceOverridden = false;
        goalOverride[HOME] = 0;
        goalOverride[AWAY] = 0;
    }

    /** The match's own division's race, or 0 when the read did not give us one. */
    private int divisionRaceOf(TournamentApi.Match m) {
        if (m == null || m.divisionId == null) {
            return 0;
        }
        for (TournamentApi.Division division : divisions) {
            if (m.divisionId.equals(division.id)) {
                return division.raceTo;
            }
        }
        return 0;
    }

    /**
     * Each side has its own goal, so uneven goals are a handicap. Reaching one ends the match, but the
     * app asks rather than assumes: a mistyped tally is common, so the prompt offers to submit or to
     * carry on editing. Dropping back below the goal re-arms the prompt for that side.
     */
    private void checkGoal(int side) {
        for (int s = HOME; s <= AWAY; s++) {
            if (goal[s] == 0 || score[s] < goal[s]) {
                goalPrompted[s] = false;
            }
        }
        if (goal[side] > 0 && score[side] >= goal[side] && !goalPrompted[side]) {
            goalPrompted[side] = true;
            askToSubmitReachedGoal(side);
        }
    }

    private void askToSubmitReachedGoal(final int side) {
        if (!loaded || submitted || busy || match == null || viewOnly) {
            return;
        }
        String message = getString(R.string.linked_goal_reached, name[side], goal[side])
                + "\n\n" + getString(R.string.linked_tallies, name[HOME], format(score[HOME]),
                name[AWAY], format(score[AWAY]))
                + "\n\n" + getString(R.string.linked_goal_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.linked_goal_title)
                .setMessage(message)
                .setPositiveButton(R.string.linked_goal_submit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        submit(side);
                    }
                })
                .setNegativeButton(R.string.linked_goal_keep, null)
                .show();
    }

    /**
     * The race line, which now says where the race came from. Three states, and the operator must be
     * able to tell them apart at a glance:
     *
     * <ul>
     *   <li><b>the bracket's race</b> - the ordinary case: "Race to 7 \u00b7 from the bracket", or
     *       "the bracket's match override" when the target was set on this match rather than its
     *       division;</li>
     *   <li><b>a local override</b> - named as one, with the bracket's own number beside it, so a
     *       handicap cannot be mistaken for what the bracket asked for;</li>
     *   <li><b>no race from the bracket</b> - an older server, an offline read, or a tournament with
     *       no race; today's behaviour, and it says as much.</li>
     * </ul>
     */
    private void updateRaceLine() {
        if (linkedRace == null) {
            return;
        }
        if (race.source == Race.FROM_LOCAL) {
            String pair = getString(R.string.linked_race_pair, goalText(HOME), goalText(AWAY));
            linkedRace.setText(race.hasBracket()
                    ? getString(R.string.linked_race_local, goalUnit(), pair, race.bracketRaceTo)
                    : getString(R.string.linked_race_local_none, goalUnit(), pair));
            return;
        }
        if (race.hasBracket()) {
            int line = R.string.linked_race_bracket;
            if (race.source == Race.FROM_MATCH) {
                line = R.string.linked_race_bracket_match;
            } else if (race.source == Race.FROM_DEFAULT) {
                line = R.string.linked_race_bracket_default;
            }
            linkedRace.setText(getString(line, goalUnit(), race.bracketRaceTo));
            return;
        }
        linkedRace.setText(getString(R.string.linked_race_byhand, goalUnit()));
    }

    /** A side's goal as the race line writes it: a dash where that side has none. */
    private String goalText(int side) {
        return goal[side] == 0 ? "\u2014" : String.valueOf(goal[side]);
    }

    /**
     * The operator's goals, by hand. This is now the deviation rather than the normal path: the
     * dialog names the bracket's race, and what is typed here overrides it for this table only (an
     * uneven handicap, a different target, or no goal at all). Typing the bracket's own number back
     * in is not a deviation, and the neutral button drops the override entirely - which is the only
     * way back to the bracket's race, so it cannot be left on by accident.
     */
    private void openGoalsDialog() {
        if (!loaded) {
            return;
        }
        final boolean bracket = race.hasBracket();
        final EditText homeInput = goalField(name[HOME], goal[HOME]);
        final EditText awayInput = goalField(name[AWAY], goal[AWAY]);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(homeInput);
        box.addView(awayInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(bracket ? R.string.linked_goals_title : R.string.linked_goals_title_none)
                .setMessage(bracket
                        ? getString(R.string.linked_goals_hint, goalUnit(), race.bracketRaceTo)
                        : getString(R.string.linked_goals_hint_none))
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        applyGoals(parseGoal(homeInput.getText().toString()),
                                parseGoal(awayInput.getText().toString()));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (bracket) {
            builder.setNeutralButton(R.string.linked_goals_clear, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    resetOverride();
                    applyRace();
                    // A neutral button does not close the dialog on its own.
                    d.dismiss();
                }
            });
        }
        builder.show();
    }

    /**
     * Records what the operator typed. A pair that deviates from the bracket's race becomes the
     * override; a pair that is the bracket's own race drops the override instead, so nothing on
     * screen can claim a deviation that is not one.
     */
    private void applyGoals(int home, int away) {
        if (Race.deviates(race.bracketRaceTo, home, away)) {
            raceOverridden = true;
            goalOverride[HOME] = home;
            goalOverride[AWAY] = away;
        } else {
            resetOverride();
        }
        goalPrompted[HOME] = false;
        goalPrompted[AWAY] = false;
        applyRace();
        checkGoal(HOME);
        checkGoal(AWAY);
    }

    private EditText goalField(String hint, int value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setContentDescription(hint);   // labels it for TalkBack and for the UI harness
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
        if (value > 0) {
            input.setText(String.valueOf(value));
            input.setSelectAllOnFocus(true);   // typing replaces the old goal
        }
        return input;
    }

    private static int parseGoal(String raw) {
        if (raw == null) {
            return 0;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return clamp(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The payload the server stores verbatim: their {"score":"a-b"} convention plus our detail.
     * The fields, their order, the race's provenance and the tally marker come from
     * {@link ResultPayload}, which is pure so tools/check-race.sh can assert the shape without a
     * device.
     */
    private JSONObject buildScores() {
        JSONObject scores = new JSONObject();
        for (ResultPayload.Field field : ResultPayload.fields(name[HOME], name[AWAY],
                score[HOME], score[AWAY], tournament == null ? null : tournament.game,
                appVersion(), race)) {
            try {
                scores.put(field.key, field.value);
            } catch (JSONException ignored) {
                // JSONObject.put only throws on NaN/infinite numbers, which these values cannot be
            }
        }
        return scores;
    }

    /** This build's own version name, so a stored sheet says which app produced it. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void setStatus(String text) {
        linkedStatus.setText(text);
    }

    private void setAction(String label, boolean enabled) {
        linkedAction.setText(label);
        linkedAction.setEnabled(enabled);
        linkedAction.setAlpha(enabled ? 1f : 0.5f);
    }

    private void setBusy(String label) {
        busy = label != null;
        if (label != null) {
            setAction(label, false);
        }
    }

    private void inBackground(final Runnable work) {
        new Thread(work).start();
    }

    private String winnerNameFor(String entrantId) {
        if (entrantId == null) {
            return null;
        }
        if (entrantId.equals(homeEntrantId)) {
            return name[HOME];
        }
        if (entrantId.equals(awayEntrantId)) {
            return name[AWAY];
        }
        return null;
    }

    private static List<TournamentApi.Match> withState(List<TournamentApi.Match> matches, String... states) {
        List<TournamentApi.Match> out = new java.util.ArrayList<TournamentApi.Match>();
        for (TournamentApi.Match m : matches) {
            for (String state : states) {
                if (state.equals(m.state)) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    private static List<TournamentApi.Match> withBothSides(List<TournamentApi.Match> matches) {
        List<TournamentApi.Match> out = new java.util.ArrayList<TournamentApi.Match>();
        for (TournamentApi.Match m : matches) {
            if (emptyToNull(m.entrantAId) != null && emptyToNull(m.entrantBId) != null) {
                out.add(m);
            }
        }
        return out;
    }

    private static TournamentApi.Match find(List<TournamentApi.Match> matches, String id) {
        for (TournamentApi.Match m : matches) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    private static String nameOf(List<TournamentApi.Entrant> entrants, String entrantId) {
        if (entrantId == null) {
            return "TBD";
        }
        for (TournamentApi.Entrant e : entrants) {
            if (entrantId.equals(e.id)) {
                return e.displayName;
            }
        }
        return "TBD";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // -------------------------------------------------------------- renaming

    /**
     * Rename by dwell: the name dims while the finger rests on it, then the dialog opens.
     * Lifting the finger before the dwell elapses cancels, so a name is never renamed by accident.
     */
    private void attachDwell(final TextView view, final int side) {
        final Runnable fire = new Runnable() {
            @Override
            public void run() {
                openRename(side);
            }
        };
        final ValueAnimator fade = ValueAnimator.ofFloat(1f, 0.45f);
        fade.setDuration(DWELL_MS);
        fade.setInterpolator(new LinearInterpolator());
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                view.setAlpha((Float) animation.getAnimatedValue());
            }
        });

        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (linkedBar != null && linkedBar.getVisibility() == View.VISIBLE) {
                            return true;   // names come from the tournament while a link is open
                        }
                        v.removeCallbacks(fire);
                        v.postDelayed(fire, DWELL_MS);
                        fade.cancel();
                        fade.start();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(fire);
                        fade.cancel();
                        v.setAlpha(1f);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void openRename(final int side) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MAX_NAME_LENGTH)});
        input.setHint(DEFAULT_NAME[side]);
        input.setText(name[side]);
        input.setSelection(input.getText().length());
        input.setSelectAllOnFocus(true);

        int pad = dp(24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(box)
                .setPositiveButton(R.string.rename_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        name[side] = sanitize(input.getText().toString(), side);
                        refresh(side);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
