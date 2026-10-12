package com.kevin.score;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.widget.TextView;

import java.util.Locale;

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

    private final int[] score = new int[2];
    private final String[] name = {DEFAULT_NAME[HOME], DEFAULT_NAME[AWAY]};
    private final TextView[] scoreView = new TextView[2];
    private final TextView[] nameView = new TextView[2];
    private final View[] plusButton = new View[2];
    private final View[] minusButton = new View[2];
    private final View[] resetButton = new View[2];

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
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putIntArray(STATE_SCORES, new int[] {score[HOME], score[AWAY]});
        out.putStringArray(STATE_NAMES, new String[] {name[HOME], name[AWAY]});
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
