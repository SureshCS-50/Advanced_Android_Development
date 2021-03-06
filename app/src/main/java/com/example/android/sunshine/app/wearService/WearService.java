package com.example.android.sunshine.app.wearService;

import android.app.IntentService;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by Sureshkumar on 17/11/16.
 */

public class WearService extends Service implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = WearService.class.getSimpleName();

    public static final String ACTION_UPDATE_WATCH_FACE = "ACTION_UPDATE_WATCH_FACE";

    private static final String KEY_PATH = "/weather";
    private static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";
    private static final String KEY_MAX_TEMP = "KEY_MAX_TEMP";
    private static final String KEY_MIN_TEMP = "KEY_MIN_TEMP";

    private GoogleApiClient mGoogleApiClient;

    public WearService() {
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "Updating the WatchFace");
        String locationQuery = Utility.getPreferredLocation(this);

        Uri weatherUri = WeatherContract.WeatherEntry
                .buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        Cursor c = getContentResolver().query(
                weatherUri,
                new String[]{WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
                        WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                        WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
                }, null, null, null);

        if (c.moveToFirst()) {
            int weatherId = c.getInt(c.getColumnIndex(
                    WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
            String maxTemp = Utility.formatTemperature(this, c.getDouble(
                    c.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP)));
            String minTemp = Utility.formatTemperature(this, c.getDouble(
                    c.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP)));

            final PutDataMapRequest mapRequest = PutDataMapRequest.create(KEY_PATH);
            mapRequest.getDataMap().putInt(KEY_WEATHER_ID, weatherId);
            mapRequest.getDataMap().putString(KEY_MAX_TEMP, maxTemp);
            mapRequest.getDataMap().putString(KEY_MIN_TEMP, minTemp);

            PendingResult<DataApi.DataItemResult> pendingResult =
                    Wearable.DataApi.putDataItem(mGoogleApiClient, mapRequest.asPutDataRequest());
        }
        c.close();
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "onConnection suspended");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "onConnection failed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && intent.getAction() != null
                && intent.getAction().equals(ACTION_UPDATE_WATCH_FACE)) {

            mGoogleApiClient = new GoogleApiClient.Builder(WearService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }
        return super.onStartCommand(intent, flags, startId);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mGoogleApiClient.disconnect();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}