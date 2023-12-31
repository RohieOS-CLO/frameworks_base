/*
 * Copyright (C) 2020 The AOSPA-Extended Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

/*
*
* Seeing how an Integer object in java requires at least 16 Bytes, it seemed awfully wasteful
* to only use it for a single boolean. 32-bits is plenty of room for what we need it to do.
*
*/
public class NetworkTraffic extends TextView {

    private static final int INTERVAL = 750; //ms
    private static final int KB = 1024;
    private static final int MB = KB * KB;
    private static final int GB = MB * KB;
    private static final String SYMBOL = "/s";

    protected boolean mIsEnabled;
    private boolean mAttached;
    protected boolean mShowDynamic;
    private long totalRxBytes;
    private long totalTxBytes;
    private long lastUpdateTime;
    private int mAutoHideThreshold;
    protected int mTintColor;

    private boolean mScreenOn = true;
    protected boolean mVisible = true;
    private ConnectivityManager mConnectivityManager;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < INTERVAL * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            if (shouldHide(rxData, txData, timeDelta)) {
                setText("");
                setVisibility(View.GONE);
                mVisible = false;
            } else if (mShowDynamic) {
                if (shouldShowUpload(rxData, txData, timeDelta)) {
                    // Show information for uplink if it's called for
                    CharSequence output = formatOutput(timeDelta, txData);

                    // Update view if there's anything new to show
                    if (output != getText()) {
                        setText(output);
                    }
                } else {
                    // Add information for downlink if it's called for
                    CharSequence output = formatOutput(timeDelta, rxData);

                    // Update view if there's anything new to show
                    if (output != getText()) {
                        setText(output);
                    }
                    makeVisible();
                }
            } else {
                CharSequence output = TextUtils.concat(formatOutput(timeDelta, txData), "\n", formatOutput(timeDelta, rxData));

                // Update view if there's anything new to show
                if (output != getText()) {
                    setText(output);
                }
                makeVisible();
            }

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, INTERVAL);
        }

        private CharSequence formatOutput(long timeDelta, long data) {
            long speed = (long)(data / (timeDelta / 1000F));

            return formatDecimal(speed);
        }

        private CharSequence formatDecimal(long speed) {
            DecimalFormat decimalFormat;
            String unit;
            String formatSpeed;
            SpannableString spanUnitString;
            SpannableString spanSpeedString;

            if (speed >= GB) {
                unit = "G";
                decimalFormat = new DecimalFormat("0.00");
                formatSpeed =  decimalFormat.format(speed / (float)GB);
            } else if (speed >= 100 * MB) {
                decimalFormat = new DecimalFormat("000");
                unit = "M";
                formatSpeed =  decimalFormat.format(speed / (float)MB);
            } else if (speed >= 10 * MB) {
                decimalFormat = new DecimalFormat("00.0");
                unit = "M";
                formatSpeed =  decimalFormat.format(speed / (float)MB);
            } else if (speed >= MB) {
                decimalFormat = new DecimalFormat("0.00");
                unit = "M";
                formatSpeed =  decimalFormat.format(speed / (float)MB);
            } else if (speed >= 100 * KB) {
                decimalFormat = new DecimalFormat("000");
                unit = "K";
                formatSpeed =  decimalFormat.format(speed / (float)KB);
            } else if (speed >= 10 * KB) {
                decimalFormat = new DecimalFormat("00.0");
                unit = "K";
                formatSpeed =  decimalFormat.format(speed / (float)MB);
            } else {
                decimalFormat = new DecimalFormat("0.00");
                unit = "K";
                formatSpeed = decimalFormat.format(speed / (float)KB);
            }

            if (mShowDynamic) {
                unit += "B";
            }
            
            spanSpeedString = new SpannableString(formatSpeed);
            spanSpeedString.setSpan(getSpeedRelativeSizeSpan(), 0, (formatSpeed).length(),
                mShowDynamic ? Spanned.SPAN_INCLUSIVE_INCLUSIVE : Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            spanUnitString = new SpannableString(unit + SYMBOL);
            spanUnitString.setSpan(getUnitRelativeSizeSpan(), 0, (unit + SYMBOL).length(),
                mShowDynamic ? Spanned.SPAN_INCLUSIVE_INCLUSIVE : Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return TextUtils.concat(spanSpeedString, mShowDynamic ? "\n" : " ", spanUnitString);
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KB;
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KB;
            return !getConnectAvailable() || (speedRxKB < mAutoHideThreshold &&
                    speedTxKB < mAutoHideThreshold);
        }

        private boolean shouldShowUpload(long rxData, long txData, long timeDelta) {
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KB;
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KB;

            return (speedTxKB > speedRxKB);
        }
    };

    protected boolean restoreViewQuickly() {
        return getConnectAvailable() && mAutoHideThreshold == 0;
    }

    protected void makeVisible() {
        setVisibility(View.VISIBLE);
        mVisible = true;
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mTintColor = resources.getColor(android.R.color.white);
        setMode();
        Handler mHandler = new Handler();
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    protected RelativeSizeSpan getSpeedRelativeSizeSpan() {
        return new RelativeSizeSpan(0.75f);
    }

    protected RelativeSizeSpan getUnitRelativeSizeSpan() {
        return new RelativeSizeSpan(0.7f);
    }

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_STATE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_DYNAMIC), false,
                    this, UserHandle.USER_ALL);
        }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            setMode();
            update();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) && mScreenOn) {
                update();
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mScreenOn = true;
                update();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                clearHandlerCallbacks();
            }
        }
    };

    private boolean getConnectAvailable() {
        NetworkInfo network = (mConnectivityManager != null) ? mConnectivityManager.getActiveNetworkInfo() : null;
        return network != null;
    }

    protected void update() {
        if (mIsEnabled) {
            if (mAttached) {
                totalRxBytes = TrafficStats.getTotalRxBytes();
                lastUpdateTime = SystemClock.elapsedRealtime();
                mTrafficHandler.sendEmptyMessage(1);
            }
            return;
        } else {
            clearHandlerCallbacks();
        }
        setVisibility(View.GONE);
        mVisible = false;
    }

    protected void setMode() {
        ContentResolver resolver = mContext.getContentResolver();
        mIsEnabled = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_STATE, 0,
                UserHandle.USER_CURRENT) == 1;
        mAutoHideThreshold = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 1,
                UserHandle.USER_CURRENT);
        mShowDynamic = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_DYNAMIC, 1,
                UserHandle.USER_CURRENT) == 1;
        setGravity(Gravity.CENTER);
        setMaxLines(2);
        if (mShowDynamic) {
            setTextSize(14f);
        } else {
            setTextSize(9f);
        }
        setSpacingAndFonts();
        updateTrafficDrawable();
        setVisibility(View.GONE);
        mVisible = false;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    protected void updateTrafficDrawable() {
        setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        setTextColor(mTintColor);
    }

    protected void setSpacingAndFonts() {
        setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        setLineSpacing(0.80f, 0.80f);
    }

    public void onDensityOrFontScaleChanged() {
        setSpacingAndFonts();
        update();
    }
}