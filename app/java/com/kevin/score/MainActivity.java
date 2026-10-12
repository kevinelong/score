package com.kevin.score;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
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
 * HOME occupies the left half, AWAY the right half. Each side owns a two-digit score,
 * a decrement button, an increment button and a reset button. Resting a finger on a
 * team name for {@link #DWELL_MS} milliseconds opens the rename dialog.
 */
public class MainActivity extends Activity {

    private static final int HOME = 0;
    private static final int AWAY = 1;

    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 99;

    private static final int MAX_NAME_LENGTH = 16;

    /** Finger dwell on a team name that opens the rename dialog. */
    private static final long DWELL_MS = 900L;

    private static final String[] DEFAULT_NAME = {"HOME", "AWAY"};

    private static final int[] ID_PLUS = {R.id.home_plus, R.id.away_plus};
    private static final int[] ID_MINUS = {R.id.home_minus, R.id.away_minus};
    private static final int[] ID_RESET = {R.id.home_reset, R.id.away_reset};
    private static final int[] ID_SCORE = {R.id.home_score, R.id.away_score};
    private static final int[] ID_NAME = {R.id.home_name, R.id.away_name};

    private static final String STATE_SCORES = "scores";
    private static final String STATE_NAMES = "names";
    private static final String STATE_LINK = "link";
    private static final String STATE_GOALS = "goals";

    private final int[] score = new int[2];
    private final String[] name = {DEFAULT_NAME[HOME], DEFAULT_NAME[AWAY]};
    private final TextView[] scoreView = new TextView[2];
    private final TextView[] nameView = new TextView[2];
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
    /** Per-side race goals: 0 means no goal, and uneven goals are a handicap. */
    private final int[] goal = {0, 0};
    /** Set once a reached goal has been acknowledged, so the prompt does not nag on every point. */
    private final boolean[] goalPrompted = {false, false};
    private TextView linkedRace;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        restore(state);

        for (int side = HOME; side <= AWAY; side++) {
            scoreView[side] = (TextView) findViewById(ID_SCORE[side]);
            nameView[side] = (TextView) findViewById(ID_NAME[side]);
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

        handleLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
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
        out.putIntArray(STATE_GOALS, new int[] {goal[HOME], goal[AWAY]});
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
        int[] savedGoals = state.getIntArray(STATE_GOALS);
        if (savedGoals != null && savedGoals.length == 2) {
            goal[HOME] = clamp(savedGoals[HOME]);
            goal[AWAY] = clamp(savedGoals[AWAY]);
        }
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

    /** Moves one side by {@code delta}, clamped to the two digits the display can show. */
    private void step(int side, int delta) {
        int next = clamp(score[side] + delta);
        if (next == score[side]) {
            return;
        }
        score[side] = next;
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
                score[side] = MIN_SCORE;
                refresh(side);
            }
        });
    }

    private void refresh(int side) {
        scoreView[side].setText(format(score[side]));
        nameView[side].setText(name[side]);

        String label = name[side];
        nameView[side].setContentDescription(getString(R.string.cd_name, label));
        scoreView[side].setContentDescription(getString(R.string.cd_score, label, format(score[side])));
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

    /** Keeps the two digits as large as the score box allows, in any orientation. */
    private void resizeScore(TextView view) {
        int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int height = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        float byHeight = height * 0.75f;
        float byWidth = width / 1.20f * 0.95f; // "00" in monospace is about 1.2 em wide
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

        linkedBar.setVisibility(View.VISIBLE);
        linkedNote.setVisibility(View.GONE);
        setStatus(getString(R.string.linked_loading));
        setAction(getString(R.string.linked_submit), false);

        inBackground(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /** Reads the tournament, its entrants and its matches, then decides which match to score. */
    private void load() {
        try {
            TournamentApi.Tournament[] found = api.tournament(link.tournament);
            if (found.length == 0) {
                throw new TournamentApi.ApiError(0, "bad_response", "No tournament in the response");
            }
            final TournamentApi.Tournament found1 = found[0];
            final List<TournamentApi.Entrant> entrants = api.entrants(found1.id);
            final List<TournamentApi.Match> matches = api.matches(found1.id);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tournament = found1;
                    chooseMatch(entrants, matches);
                }
            });
        } catch (final TournamentApi.ApiError e) {
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
        goal[HOME] = 0;
        goal[AWAY] = 0;
        goalPrompted[HOME] = false;
        goalPrompted[AWAY] = false;
        updateRaceLine();
        submitted = chosen.isComplete();
        submitKey = null;
        refresh(HOME);
        refresh(AWAY);

        setStatus(tournament.name + " \u00b7 " + chosen.describe());
        if (submitted) {
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
        goal[HOME] = 0;
        goal[AWAY] = 0;
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
        if (!loaded || submitted || busy || match == null) {
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

    private void updateRaceLine() {
        if (linkedRace == null) {
            return;
        }
        if (goal[HOME] == 0 && goal[AWAY] == 0) {
            linkedRace.setText(R.string.linked_race_none);
            return;
        }
        linkedRace.setText(getString(R.string.linked_race_fmt,
                goal[HOME] == 0 ? "\u2014" : String.valueOf(goal[HOME]),
                goal[AWAY] == 0 ? "\u2014" : String.valueOf(goal[AWAY])));
    }

    private void openGoalsDialog() {
        if (!loaded) {
            return;
        }
        final EditText homeInput = goalField(name[HOME], goal[HOME]);
        final EditText awayInput = goalField(name[AWAY], goal[AWAY]);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(homeInput);
        box.addView(awayInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.linked_goals_title)
                .setMessage(R.string.linked_goals_hint)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        goal[HOME] = parseGoal(homeInput.getText().toString());
                        goal[AWAY] = parseGoal(awayInput.getText().toString());
                        goalPrompted[HOME] = false;
                        goalPrompted[AWAY] = false;
                        updateRaceLine();
                        checkGoal(HOME);
                        checkGoal(AWAY);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText goalField(String hint, int value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setContentDescription(hint);   // labels it for TalkBack and for the UI harness
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2)});
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

    /** The payload the server stores verbatim: their {"score":"a-b"} convention plus our detail. */
    private JSONObject buildScores() {
        JSONObject scores = new JSONObject();
        try {
            scores.put("score", score[HOME] + "-" + score[AWAY]);
            scores.put("home", score[HOME]);
            scores.put("away", score[AWAY]);
            scores.put("homeName", name[HOME]);
            scores.put("awayName", name[AWAY]);
            if (tournament != null && tournament.game != null && !tournament.game.isEmpty()) {
                scores.put("discipline", tournament.game);   // whatever the director chose
            }
            scores.put("app", "score-android");
            scores.put("appVersion", 1);
            if (goal[HOME] > 0 || goal[AWAY] > 0) {
                // The server has nowhere to keep a race, so the goal travels with the result - the
                // only place it can persist. Uneven goals are a handicap, so both are recorded.
                scores.put("homeGoal", goal[HOME]);
                scores.put("awayGoal", goal[AWAY]);
                scores.put("raceTo", Math.max(goal[HOME], goal[AWAY]));
            }
        } catch (JSONException ignored) {
            // JSONObject.put only throws on NaN/infinite numbers, which ints cannot be
        }
        return scores;
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
