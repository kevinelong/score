package com.kevin.score;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * Root layout that owns the swipe gesture.
 *
 * <p>A mostly-vertical flick anywhere on a side is taken from the children - they receive
 * {@link MotionEvent#ACTION_CANCEL} - and reported once per gesture, so a flick that starts on a
 * button cannot also register as a button press. Up adds a point, down subtracts.
 */
public class SwipePanel extends LinearLayout {

    public interface OnSwipeListener {
        /** @param homeSide true when the gesture started on the left half. @param delta +1 or -1. */
        void onSwipe(boolean homeSide, int delta);
    }

    private final int minTravel;
    private OnSwipeListener listener;
    private float startX;
    private float startY;
    private boolean taken;

    public SwipePanel(Context context) {
        this(context, null);
    }

    public SwipePanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipePanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        minTravel = (int) (48 * getResources().getDisplayMetrics().density);
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // A child owns this gesture, so take it once it qualifies.
        return track(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Nobody consumed the down (bare panel): ViewGroup has stopped consulting
        // onInterceptTouchEvent for this gesture, so the same detection has to run here.
        track(event);
        return true;
    }

    /** @return true when this event turned the gesture into a swipe that the parent must take. */
    private boolean track(MotionEvent event) {
        boolean fired = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                taken = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!taken) {
                    float dy = event.getY() - startY;
                    float dx = Math.abs(event.getX() - startX);
                    if (Math.abs(dy) >= minTravel && Math.abs(dy) > dx) {
                        taken = true;
                        fired = true;
                        if (listener != null) {
                            listener.onSwipe(event.getX() < getWidth() / 2f, dy < 0 ? 1 : -1);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                taken = false;
                break;
            default:
                break;
        }
        return fired;
    }
}
