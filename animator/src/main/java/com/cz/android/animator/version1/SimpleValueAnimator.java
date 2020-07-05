package com.cz.android.animator.version1;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;

/**
 * @author Created by cz
 * @date 2020/5/28 7:48 PM
 * @email bingo110@126.com
 */
public class SimpleValueAnimator implements Cloneable {
    private static final String TAG="SimpleValueAnimator";
    private static final long DEFAULT_FRAME_DELAY = 10;
    /**
     * Messages sent to timing handler: START is sent when an animation first begins, FRAME is sent
     * by the handler to itself to process the next animation frame
     */
    static final int ANIMATION_START = 0;
    static final int ANIMATION_FRAME = 1;

    static final int STOPPED    = 0; // Not yet playing
    static final int RUNNING    = 1; // Playing normally
    static final int SEEKED     = 2; // Seeked to some time value

    // The number of milliseconds between animation frames
    private static long sFrameDelay = DEFAULT_FRAME_DELAY;

    private static ThreadLocal<AnimationHandler> sAnimationHandler =
            new ThreadLocal<AnimationHandler>();

    // The per-thread list of all active animations
    private static final ThreadLocal<ArrayList<SimpleValueAnimator>> sAnimations =
            new ThreadLocal<ArrayList<SimpleValueAnimator>>() {
                @Override
                protected ArrayList<SimpleValueAnimator> initialValue() {
                    return new ArrayList<SimpleValueAnimator>();
                }
            };

    // The per-thread set of animations to be started on the next animation frame
    private static final ThreadLocal<ArrayList<SimpleValueAnimator>> sPendingAnimations =
            new ThreadLocal<ArrayList<SimpleValueAnimator>>() {
                @Override
                protected ArrayList<SimpleValueAnimator> initialValue() {
                    return new ArrayList<SimpleValueAnimator>();
                }
            };

    /**
     * Internal per-thread collections used to avoid set collisions as animations start and end
     * while being processed.
     */
    private static final ThreadLocal<ArrayList<SimpleValueAnimator>> sDelayedAnims =
            new ThreadLocal<ArrayList<SimpleValueAnimator>>() {
                @Override
                protected ArrayList<SimpleValueAnimator> initialValue() {
                    return new ArrayList<SimpleValueAnimator>();
                }
            };

    private static final ThreadLocal<ArrayList<SimpleValueAnimator>> sEndingAnims =
            new ThreadLocal<ArrayList<SimpleValueAnimator>>() {
                @Override
                protected ArrayList<SimpleValueAnimator> initialValue() {
                    return new ArrayList<SimpleValueAnimator>();
                }
            };

    private static final ThreadLocal<ArrayList<SimpleValueAnimator>> sReadyAnims =
            new ThreadLocal<ArrayList<SimpleValueAnimator>>() {
                @Override
                protected ArrayList<SimpleValueAnimator> initialValue() {
                    return new ArrayList<SimpleValueAnimator>();
                }
            };
    /**
     * Flag that represents the current state of the animation. Used to figure out when to start
     * an animation (if state == STOPPED). Also used to end an animation that
     * has been cancel()'d or end()'d since the last animation frame. Possible values are
     * STOPPED, RUNNING, SEEKED.
     */
    int mPlayingState = STOPPED;
    // How long the animation should last in ms
    private long mDuration = 300;

    // The amount of time in ms to delay starting the animation after start() is called
    private long mStartDelay = 0;
    /**
     * Additional playing state to indicate whether an animator has been start()'d. There is
     * some lag between a call to start() and the first animation frame. We should still note
     * that the animation has been started, even if it's first animation frame has not yet
     * happened, and reflect that state in isRunning().
     * Note that delayed animations are different: they are not started until their first
     * animation frame, which occurs after their delay elapses.
     */
    private boolean mRunning = false;
    /**
     * Additional playing state to indicate whether an animator has been start()'d, whether or
     * not there is a nonzero startDelay.
     */
    private boolean mStarted = false;
    /**
     * Set when setCurrentPlayTime() is called. If negative, animation is not currently seeked
     * to a value.
     */
    long mSeekTime = -1;
    /**
     * Tracks whether a startDelay'd animation has begun playing through the startDelay.
     */
    private boolean mStartedDelay = false;
    /**
     * Tracks the time at which the animation began playing through its startDelay. This is
     * different from the mStartTime variable, which is used to track when the animation became
     * active (which is when the startDelay expired and the animation was added to the active
     * animations list).
     */
    private long mDelayStartTime;
    // The first time that the animation's animateFrame() method is called. This time is used to
    // determine elapsed time (and therefore the elapsed fraction) in subsequent calls
    // to animateFrame()
    long mStartTime;

    public void start() {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException("Animators may only be run on Looper threads");
        }
        mPlayingState = STOPPED;
        mStarted = true;
        mStartedDelay = false;
        sPendingAnimations.get().add(this);
        if (mStartDelay == 0) {
            // This sets the initial value of the animation, prior to actually starting it running
            setCurrentPlayTime(getCurrentPlayTime());
            mPlayingState = STOPPED;
            mRunning = true;
        }
        AnimationHandler animationHandler = sAnimationHandler.get();
        if (animationHandler == null) {
            animationHandler = new AnimationHandler();
            sAnimationHandler.set(animationHandler);
        }
        animationHandler.sendEmptyMessage(ANIMATION_START);
    }

    private void startAnimation(){
        sAnimations.get().add(this);
    }

    public void setCurrentPlayTime(long playTime) {
        long currentTime = AnimationUtils.currentAnimationTimeMillis();
        if (mPlayingState != RUNNING) {
            mSeekTime = playTime;
            mPlayingState = SEEKED;
        }
        mStartTime = currentTime - playTime;
        animationFrame(currentTime);
    }

    public long getCurrentPlayTime() {
        if (mPlayingState == STOPPED) {
            return 0;
        }
        return AnimationUtils.currentAnimationTimeMillis() - mStartTime;
    }

    private boolean delayedAnimationFrame(long currentTime) {
        if (!mStartedDelay) {
            mStartedDelay = true;
            mDelayStartTime = currentTime;
        } else {
            long deltaTime = currentTime - mDelayStartTime;
            Log.i(TAG,"delayedAnimationFrame:"+currentTime+" "+deltaTime);
            if (deltaTime > mStartDelay) {
                // startDelay ended - start the anim and record the
                // mStartTime appropriately
                mStartTime = currentTime - (deltaTime - mStartDelay);
                mPlayingState = RUNNING;
                return true;
            }
        }
        return false;
    }

    public SimpleValueAnimator setDuration(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("Animators cannot have negative duration: " +
                    duration);
        }
        mDuration = duration;
        return this;
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called.
     * @param startDelay The amount of the delay, in milliseconds
     */
    public void setStartDelay(long startDelay) {
        this.mStartDelay = startDelay;
    }

    public boolean isRunning() {
        return (mPlayingState == RUNNING || mRunning);
    }

    /**
     * Called internally to end an animation by removing it from the animations list. Must be
     * called on the UI thread.
     */
    private void endAnimation() {
        sAnimations.get().remove(this);
        sPendingAnimations.get().remove(this);
        sDelayedAnims.get().remove(this);
        mPlayingState = STOPPED;
        mRunning = false;
        mStarted = false;
    }

    /**
     * This internal function processes a single animation frame for a given animation. The
     * currentTime parameter is the timing pulse sent by the handler, used to calculate the
     * elapsed duration, and therefore
     * the elapsed fraction, of the animation. The return value indicates whether the animation
     * should be ended (which happens when the elapsed time of the animation exceeds the
     * animation's duration, including the repeatCount).
     *
     * @param currentTime The current time, as tracked by the static timing handler
     * @return true if the animation's duration, including any repetitions due to
     * <code>repeatCount</code> has been exceeded and the animation should be ended.
     */
    boolean animationFrame(long currentTime) {
        boolean done = false;
        if (mPlayingState == STOPPED) {
            mPlayingState = RUNNING;
            if (mSeekTime < 0) {
                mStartTime = currentTime;
            } else {
                mStartTime = currentTime - mSeekTime;
                // Now that we're playing, reset the seek time
                mSeekTime = -1;
            }
        }
        switch (mPlayingState) {
            case RUNNING:
            case SEEKED:
                float fraction = mDuration > 0 ? (float)(currentTime - mStartTime) / mDuration : 1f;
                if (fraction >= 1f) {
                    done = true;
                    fraction = Math.min(fraction, 1.0f);
                }
                Log.i(TAG,"animationFrame:"+currentTime+" fraction:"+fraction);
                break;
        }

        return done;
    }

    private static class AnimationHandler extends Handler {
        /**
         * There are only two messages that we care about: ANIMATION_START and
         * ANIMATION_FRAME. The START message is sent when an animation's start()
         * method is called. It cannot start synchronously when start() is called
         * because the call may be on the wrong thread, and it would also not be
         * synchronized with other animations because it would not start on a common
         * timing pulse. So each animation sends a START message to the handler, which
         * causes the handler to place the animation on the active animations queue and
         * start processing frames for that animation.
         * The FRAME message is the one that is sent over and over while there are any
         * active animations to process.
         */
        @Override
        public void handleMessage(Message msg) {
            boolean callAgain = true;
            ArrayList<SimpleValueAnimator> animations = sAnimations.get();
            ArrayList<SimpleValueAnimator> delayedAnims = sDelayedAnims.get();
            switch (msg.what) {
                // TODO: should we avoid sending frame message when starting if we
                // were already running?
                case ANIMATION_START:
                    ArrayList<SimpleValueAnimator> pendingAnimations = sPendingAnimations.get();
                    if (animations.size() > 0 || delayedAnims.size() > 0) {
                        callAgain = false;
                    }
                    // pendingAnims holds any animations that have requested to be started
                    // We're going to clear sPendingAnimations, but starting animation may
                    // cause more to be added to the pending list (for example, if one animation
                    // starting triggers another starting). So we loop until sPendingAnimations
                    // is empty.
                    while (pendingAnimations.size() > 0) {
                        ArrayList<SimpleValueAnimator> pendingCopy =
                                (ArrayList<SimpleValueAnimator>) pendingAnimations.clone();
                        pendingAnimations.clear();
                        int count = pendingCopy.size();
                        for (int i = 0; i < count; ++i) {
                            SimpleValueAnimator anim = pendingCopy.get(i);
                            // If the animation has a startDelay, place it on the delayed list
                            if (anim.mStartDelay == 0) {
                                anim.startAnimation();
                            } else {
                                delayedAnims.add(anim);
                            }
                        }
                    }
                    // fall through to process first frame of new animations
                case ANIMATION_FRAME:
                    // currentTime holds the common time for all animations processed
                    // during this frame
                    long currentTime = AnimationUtils.currentAnimationTimeMillis();
                    ArrayList<SimpleValueAnimator> readyAnims = sReadyAnims.get();
                    ArrayList<SimpleValueAnimator> endingAnims = sEndingAnims.get();

                    // First, process animations currently sitting on the delayed queue, adding
                    // them to the active animations if they are ready
                    int numDelayedAnims = delayedAnims.size();
                    for (int i = 0; i < numDelayedAnims; ++i) {
                        SimpleValueAnimator anim = delayedAnims.get(i);
                        if (anim.delayedAnimationFrame(currentTime)) {
                            readyAnims.add(anim);
                        }
                    }
                    int numReadyAnims = readyAnims.size();
                    if (numReadyAnims > 0) {
                        for (int i = 0; i < numReadyAnims; ++i) {
                            SimpleValueAnimator anim = readyAnims.get(i);
                            anim.startAnimation();
                            anim.mRunning = true;
                            delayedAnims.remove(anim);
                        }
                        readyAnims.clear();
                    }

                    // Now process all active animations. The return value from animationFrame()
                    // tells the handler whether it should now be ended
                    int numAnims = animations.size();
                    int i = 0;
                    while (i < numAnims) {
                        SimpleValueAnimator anim = animations.get(i);
                        if (anim.animationFrame(currentTime)) {
                            endingAnims.add(anim);
                        }
                        if (animations.size() == numAnims) {
                            ++i;
                        } else {
                            // An animation might be canceled or ended by client code
                            // during the animation frame. Check to see if this happened by
                            // seeing whether the current index is the same as it was before
                            // calling animationFrame(). Another approach would be to copy
                            // animations to a temporary list and process that list instead,
                            // but that entails garbage and processing overhead that would
                            // be nice to avoid.
                            --numAnims;
                            endingAnims.remove(anim);
                        }
                    }
                    if (endingAnims.size() > 0) {
                        for (i = 0; i < endingAnims.size(); ++i) {
                            endingAnims.get(i).endAnimation();
                        }
                        endingAnims.clear();
                    }
                    // If there are still active or delayed animations, call the handler again
                    // after the frameDelay
                    if (callAgain && (!animations.isEmpty() || !delayedAnims.isEmpty())) {
                        long delayed = Math.max(0, sFrameDelay - (AnimationUtils.currentAnimationTimeMillis() - currentTime));
                        Log.i(TAG,"handleMessage:"+currentTime+" fraction:"+delayed);
                        sendEmptyMessageDelayed(ANIMATION_FRAME, delayed);
                    }
                    break;
            }
        }
    }

    @Override
    public SimpleValueAnimator clone() {
        SimpleValueAnimator anim;
        try {
            anim = (SimpleValueAnimator) super.clone();
            anim.mSeekTime = -1;
            anim.mPlayingState = STOPPED;
            anim.mStartedDelay = false;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
        return anim;
    }
}
