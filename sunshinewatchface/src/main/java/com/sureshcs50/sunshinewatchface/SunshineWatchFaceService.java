/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.sureshcs50.sunshinewatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = SunshineWatchFaceService.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long NORMAL_UPDATE_RATE_MS = 500;
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";

        private static final String KEY_HIGH = "KEY_MAX_TEMP";
        private static final String KEY_LOW = "KEY_MIN_TEMP";
        private static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextTimePaint;
        Paint mTextTimeSecondsPaint;
        Paint mTextDatePaint;
        Paint mTextDateAmbientPaint;
        Paint mTextTempHighPaint;
        Paint mTextTempLowPaint;
        Paint mTextTempLowAmbientPaint;

        Bitmap mWeatherIcon;
        String mWeatherHigh;
        String mWeatherLow;

        boolean mAmbient;

        private Calendar mCalendar;

        float mTimeYOffset;
        float mDateYOffset;
        float mDividerYOffset;
        float mWeatherYOffset;

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mTextTimePaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextTimeSecondsPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextDatePaint = createTextPaint(resources.getColor(R.color.primary_light), NORMAL_TYPEFACE);
            mTextDateAmbientPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextTempHighPaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);
            mTextTempLowPaint = createTextPaint(resources.getColor(R.color.primary_light), NORMAL_TYPEFACE);
            mTextTempLowAmbientPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mDateYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_date_y_offset_round : R.dimen.digital_date_y_offset);
            mDividerYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_divider_y_offset_round : R.dimen.digital_divider_y_offset);
            mWeatherYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_weather_y_offset_round : R.dimen.digital_weather_y_offset);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            mTextTimePaint.setTextSize(timeTextSize);
            mTextTimeSecondsPaint.setTextSize((float) (tempTextSize * 0.80));
            mTextDatePaint.setTextSize(dateTextSize);
            mTextDateAmbientPaint.setTextSize(dateTextSize);
            mTextTempHighPaint.setTextSize(tempTextSize);
            mTextTempLowAmbientPaint.setTextSize(tempTextSize);
            mTextTempLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextDateAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (mAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);
            int minute = mCalendar.get(Calendar.MINUTE);
            int second = mCalendar.get(Calendar.SECOND);
            int am_pm  = mCalendar.get(Calendar.AM_PM);

            String timeText;
            if (is24Hour) {
                int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
                timeText = String.format("%02d:%02d", hour, minute);
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                timeText = String.format("%d:%02d", hour, minute);
            }

            String secondsText = String.format("%02d", second);
            String amPmText = Utils.getAmPmString(getResources(), am_pm);
            float timeTextLen = mTextTimePaint.measureText(timeText);
            float xOffsetTime = timeTextLen / 2;
            if (mAmbient) {
                if (!is24Hour) {
                    xOffsetTime = xOffsetTime + (mTextTimeSecondsPaint.measureText(amPmText) / 2);
                }
            } else {
                xOffsetTime = xOffsetTime + (mTextTimeSecondsPaint.measureText(secondsText) / 2);
            }
            float xOffsetTimeFromCenter = bounds.centerX() - xOffsetTime;
            canvas.drawText(timeText, xOffsetTimeFromCenter, mTimeYOffset, mTextTimePaint);
            if (mAmbient) {
                if (!is24Hour) {
                    canvas.drawText(amPmText, xOffsetTimeFromCenter + timeTextLen + 5, mTimeYOffset, mTextTimeSecondsPaint);
                }
            } else {
                canvas.drawText(secondsText, xOffsetTimeFromCenter + timeTextLen + 5, mTimeYOffset, mTextTimeSecondsPaint);
            }

            Paint datePaint = mAmbient ? mTextDateAmbientPaint : mTextDatePaint;

            Resources resources = getResources();

            String dayOfWeekString   = Utils.getDayOfWeekString(resources, mCalendar.get(Calendar.DAY_OF_WEEK));
            String monthOfYearString = Utils.getMonthOfYearString(resources, mCalendar.get(Calendar.MONTH));

            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int year = mCalendar.get(Calendar.YEAR);

            String dateText = String.format("%s, %s %d %d", dayOfWeekString, monthOfYearString, dayOfMonth, year);
            float xOffsetDate = datePaint.measureText(dateText) / 2;
            canvas.drawText(dateText, bounds.centerX() - xOffsetDate, mDateYOffset, datePaint);

            if (mWeatherHigh != null && mWeatherLow != null && mWeatherIcon != null) {
                canvas.drawLine(bounds.centerX() - 20, mDividerYOffset, bounds.centerX() + 20, mDividerYOffset, datePaint);

                float highTextLen = mTextTempHighPaint.measureText(mWeatherHigh);

                if (mAmbient) {
                    float lowTextLen = mTextTempLowAmbientPaint.measureText(mWeatherLow);
                    float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                    canvas.drawText(mWeatherLow, xOffset + highTextLen + 20, mWeatherYOffset, mTextTempLowAmbientPaint);
                } else {
                    float xOffset = bounds.centerX() - (highTextLen / 2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                    canvas.drawText(mWeatherLow, bounds.centerX() + (highTextLen / 2) + 20, mWeatherYOffset, mTextTempLowPaint);
                    float iconXOffset = bounds.centerX() - ((highTextLen / 2) + mWeatherIcon.getWidth() + 30);
                    canvas.drawBitmap(mWeatherIcon, iconXOffset, mWeatherYOffset - mWeatherIcon.getHeight(), null);
                }
            }
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d(TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
                trigger();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "suspended");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG,"Data Changed");
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, path);
                    if (path.equals(WEATHER_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mWeatherHigh = dataMap.getString(KEY_HIGH);
                            Log.d(TAG, "High = " + mWeatherHigh);
                        } else {
                            Log.d(TAG, "No Data Available High temp");
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mWeatherLow = dataMap.getString(KEY_LOW);
                            Log.d(TAG, "Low = " + mWeatherLow);
                        } else {
                            Log.d(TAG, "No Data Available Low temp");
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable b = getResources().getDrawable(Utils.getIconResourceForWeatherCondition(weatherId));
                            Bitmap icon = null;
                            if (((BitmapDrawable) b) != null) {
                                icon = ((BitmapDrawable) b).getBitmap();
                            }
                            float scaledWidth = (mTextTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                            mWeatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextTempHighPaint.getTextSize(), true);

                        } else {
                            Log.d(TAG, "No Data Available Weather ID ");
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "onConnection failed");
        }

        public void trigger() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString("DATA", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Trigger failed for weather data");
                            } else {
                                Log.d(TAG, "Trigger success for weather data");
                            }
                        }
                    });
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
