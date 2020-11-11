package com.ads.appgm.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.ads.appgm.R;
import com.ads.appgm.model.Actuation;
import com.ads.appgm.model.MyLocation;
import com.ads.appgm.util.Constants;
import com.ads.appgm.util.MyNotification;
import com.ads.appgm.util.MyPermission;
import com.ads.appgm.util.SettingsUtils;
import com.ads.appgm.util.SharedPreferenceUtil;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.permissioneverywhere.PermissionEverywhere;
import com.permissioneverywhere.PermissionResultCallback;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ForegroundLocationService extends Service {

    private static final String TAG = ForegroundLocationService.class.getSimpleName();

    private final IBinder mBinder = new LocalBinder();
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationManager locationManager;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private MyNotification myNotification;
    private Location location;
    private Handler serviceHandler;
    private boolean changingConfiguration = false;

    public ForegroundLocationService() {
    }

    @Override
    public void onCreate() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onNewLocation(locationResult.getLastLocation());
            }
        };

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        createLocationRequest();
        getLastLocation();

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        serviceHandler = new Handler(handlerThread.getLooper());
        myNotification = MyNotification.getInstance(getApplicationContext());
        myNotification.createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean startedFromNotification = intent.getBooleanExtra(Constants.EXTRA_STARTED_FROM_NOTIFICATION,
                false);
        boolean startedFromPanicQuick = intent.getBooleanExtra(Constants.EXTRA_STARTED_FROM_PANICQUICK, false);

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            removeLocationUpdates();
            stopSelf();
            myNotification.cancel(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE);
        }

        if (startedFromPanicQuick) {
            if (intent.getBooleanExtra(Constants.PANIC, false)) {
                Log.i(TAG, "Starting foreground service");
                requestLocationUpdates();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE,
                            myNotification.foregroundNotification(getApplicationContext()),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE,
                            myNotification.foregroundNotification(getApplicationContext()));
                }
            } else {
                myNotification.cancel(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE);
                removeLocationUpdates();
                stopSelf();
            }
        }

        // Tells the system to not try to recreate the service after it has been killed.
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        changingConfiguration = true;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
//        stopForeground(true);
        changingConfiguration = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
//        stopForeground(true);
        changingConfiguration = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.
//        if (!changingConfiguration && SettingsUtils.requestingLocationUpdates(this)) {
////            Log.i(TAG, "Starting foreground service");
//
//            startForeground(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE, myNotification.foregroundNotification(getApplicationContext()));
//        }
        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
//        myNotification.cancel(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE);
        serviceHandler.removeCallbacksAndMessages(null);
    }

    private final DialogInterface.OnClickListener listenerGpsOn = (dialog, which) -> {
        if (which == Dialog.BUTTON_POSITIVE) {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        } else {
            dialog.dismiss();
            stopSelf();
        }
    };

    public void requestLocationUpdates() {
        SettingsUtils.setRequestingLocationUpdates(this, true);
        startService(new Intent(getApplicationContext(), ForegroundLocationService.class));
        if (!serviceIsRunningInForeground(getApplicationContext())) {
            startForeground(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE, myNotification.foregroundNotification(getApplicationContext()));
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                myNotification.turnOnGps(getApplicationContext());
                SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
                sp.edit().putBoolean(Constants.PANIC, false).apply();
            }
            fusedLocationProviderClient.requestLocationUpdates(locationRequest,
                    locationCallback, Looper.myLooper());
        } catch (SecurityException unlikely) {
            SettingsUtils.setRequestingLocationUpdates(this, false);
            Log.e(TAG, "Lost location permission. Could not request updates. " + unlikely);
            PermissionEverywhere.getPermission(getApplicationContext(),
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    Constants.GPS_PERMISSION_REQUEST,
                    getString(R.string.app_name),
                    getString(R.string.reason_gps),
                    R.mipmap.ic_launcher)
                    .enqueue(permissionResponse -> {
                        if (permissionResponse.isGranted()) {
                            requestLocationUpdates();
                        } else {
                            stopSelf();
                        }
                    });
        }
    }

    public void removeLocationUpdates() {
        stopForeground(true);
        try {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            SettingsUtils.setRequestingLocationUpdates(this, false);
            SharedPreferenceUtil.initialize(getApplicationContext());
            SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
            sp.edit().putBoolean(Constants.PANIC, false).apply();
            stopSelf();
        } catch (SecurityException unlikely) {
            SettingsUtils.setRequestingLocationUpdates(this, true);
            Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
            PermissionEverywhere.getPermission(getApplicationContext(),
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    Constants.GPS_PERMISSION_REQUEST,
                    getString(R.string.app_name),
                    getString(R.string.reason_gps),
                    R.mipmap.ic_launcher)
                    .enqueue(permissionResponse -> {
                        if (permissionResponse.isGranted()) {
                            removeLocationUpdates();
                        } else {
                            stopSelf();
                        }
                    });
        }
    }

    private void getLastLocation() {
        try {
            fusedLocationProviderClient.getLastLocation()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            location = task.getResult();
                        } else {
                            Log.e(TAG, "Failed to get location.");
                        }
                    })
                    .addOnFailureListener(e -> {
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setTitle(R.string.GPS).setMessage(R.string.reason_gps)
                                .setNegativeButton(R.string.close, listenerGpsOn)
                                .setCancelable(false)
                                .setPositiveButton(R.string.turn_on_gps, listenerGpsOn)
                                .show();
                    });
        } catch (SecurityException unlikely) {
            Log.e(TAG, "Lost location permission." + unlikely);
            MyPermission myPermission = MyPermission.getInstance();
            myPermission.requestGPS(getApplicationContext(), permissionResultCallback);
        }
    }

    private final PermissionResultCallback permissionResultCallback = permissionResponse -> {
        if (permissionResponse.isGranted()) {
            ForegroundLocationService.this.getLastLocation();
        } else {
            ForegroundLocationService.this.stopSelf();
        }
    };

    private void onNewLocation(Location location) {
        Log.e(TAG, "New location: " + location);

        this.location = location;

        // Notify anyone listening for broadcasts about the new location.
        Intent intent = new Intent(Constants.ACTION_BROADCAST);
        intent.putExtra(Constants.EXTRA_LOCATION, location);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
        SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
        if (sp.getBoolean(Constants.FIRST_ACTUATION, true)) {
            sendLocationToBackEnd();
            sp.edit().putBoolean(Constants.FIRST_ACTUATION, false).apply();
        } else {
            getActuation();
        }
        // Update notification content if running as a foreground service.
//        if (serviceIsRunningInForeground(this)) {
//            notificationManager.notify(NOTIFICATION_ID, getNotification());
//        }
    }

    private void getActuation() {
        SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
        BackEndService client = HttpClient.getInstance();
        client.getActuation(sp.getString(Constants.USER_TOKEN, "0"), sp.getString(Constants.MEASURE_ID, "0"))
                .enqueue(getActuationCallback);
    }

    Callback<Actuation> getActuationCallback = new Callback<Actuation>() {
        @Override
        public void onResponse(@NotNull Call<Actuation> call, @NotNull Response<Actuation> response) {

        }

        @Override
        public void onFailure(@NotNull Call<Actuation> call, @NotNull Throwable t) {

        }
    };

    private void sendLocationToBackEnd() {
        BackEndService client = HttpClient.getInstance();
        List<Double> position = new ArrayList<>(2);
        position.add(location.getLatitude());
        position.add(location.getLongitude());
        MyLocation myLocation = new MyLocation(position, true);
        SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
        Call<Void> call = client.postLocation(myLocation, sp.getString(Constants.USER_TOKEN, "0"));
        call.enqueue(sendLocationCallback);
    }

    Callback<Void> sendLocationCallback = new Callback<Void>() {
        @Override
        public void onResponse(@NotNull Call<Void> call, Response<Void> response) {
            if (response.code() == 200) {
                Toast.makeText(getApplicationContext(), "Enviou GPS", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getApplicationContext(), "Erro " + response.code(), Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onFailure(Call<Void> call, Throwable t) {
            Toast.makeText(getApplicationContext(), "Erro " + t.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            if (!call.isCanceled()) {
                call.cancel();
            }
        }
    };

    private void createLocationRequest() {
        locationRequest = new LocationRequest();
        locationRequest.setInterval(Constants.UPDATE_INTERVAL_IN_MILLISECONDS);
        locationRequest.setFastestInterval(Constants.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
//        locationRequest.setSmallestDisplacement(Constants.SMALLEST_DISPLACEMENT);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    public class LocalBinder extends Binder {
        public ForegroundLocationService getService() {
            return ForegroundLocationService.this;
        }
    }

    public boolean serviceIsRunningInForeground(Context context) {
        ActivityManager manager = (ActivityManager) context.
                getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }
            }
        }
        return false;
    }
}