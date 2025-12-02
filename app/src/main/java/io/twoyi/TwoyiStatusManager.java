/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.LogEvents;

/**
 * @author weishu
 * @date 2021/10/27.
 */

public class TwoyiStatusManager {

    private static final String TAG = "TwoyiStatusManager";
    private static final TwoyiStatusManager INSTANCE = new TwoyiStatusManager();
    private TwoyiStatusManager() {
    }

    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final AtomicBoolean mShown = new AtomicBoolean(false);

    private final CyclicBarrier mBootLatch = new CyclicBarrier(2);

    public static TwoyiStatusManager getInstance() {
        return INSTANCE;
    }

    public void updateVisibility(boolean visible) {
        Log.i(TAG, "updateVisibility: " + visible);
        mShown.set(visible);
    }

    public void markStarted() {
        Log.i(TAG, "markStarted: called, current mStarted=" + mStarted.get());
        if (mStarted.compareAndSet(false, true)) {
            Log.i(TAG, "markStarted: set to true, waiting on boot latch");
            try {
                mBootLatch.await();
                Log.i(TAG, "markStarted: boot latch passed");
            } catch (BrokenBarrierException | InterruptedException e) {
                Log.e(TAG, "markStarted: exception waiting on boot latch", e);
                LogEvents.trackError(e);
            }
        } else {
            Log.i(TAG, "markStarted: already started, skipping");
        }
    }

    public boolean isStarted() {
        boolean started = mStarted.get();
        Log.i(TAG, "isStarted: " + started);
        return started;
    }

    public void reset() {
        Log.i(TAG, "reset: resetting state");
        mStarted.set(false);
        mBootLatch.reset();
    }

    public boolean waitBoot(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException {
        Log.i(TAG, "waitBoot: waiting for " + timeout + " " + unit);
        try {
            mBootLatch.await(timeout, unit);
            Log.i(TAG, "waitBoot: boot completed successfully");
            return true;
        } catch (TimeoutException e) {
            Log.e(TAG, "waitBoot: timeout waiting for boot");
            return false;
        }
    }

    public void switchOs(Context context) {
        Log.i(TAG, "switchOs: mStarted=" + mStarted.get() + ", mShown=" + mShown.get());
        if (!mStarted.get()) {
            Log.i(TAG, "switchOs: not started, returning");
            return;
        }

        Intent intent;
        if (mShown.get()) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
        } else {
            intent = new Intent(context, Render2Activity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        context.startActivity(intent);
        mShown.set(!mShown.get());
    }
}
