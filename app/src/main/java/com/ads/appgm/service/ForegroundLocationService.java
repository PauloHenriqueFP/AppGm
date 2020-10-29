package com.ads.appgm.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.ads.appgm.R;
import com.ads.appgm.SplashActivity;
import com.ads.appgm.model.MyLocation;
import com.ads.appgm.util.Constants;
import com.ads.appgm.util.SettingsUtils;
import com.ads.appgm.util.SharedPreferenceUtil;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.permissioneverywhere.PermissionEverywhere;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ForegroundLocationService extends Service {
    private static final String PACKAGE_NAME =
            "com.ads.appgm.service.foregroundlocationservice";
    public static final String ACTION_BROADCAST = PACKAGE_NAME + ".broadcast";
    public static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";

    private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 10000;
    private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    private static final String TAG = ForegroundLocationService.class.getSimpleName();
    private static final String CHANNEL_ID = "foreground_location";
    private static final String EXTRA_STARTED_FROM_NOTIFICATION = PACKAGE_NAME +
            ".started_from_notification";
    private static final int NOTIFICATION_ID = Constants.FOREGROUND_NOTIFICATION_ID;

    private final IBinder mBinder = new LocalBinder();
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationManager locationManager;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private NotificationManager notificationManager;
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

        locationManager = (LocationManager) getSystemService(Activity.LOCATION_SERVICE);

        createLocationRequest();
        getLastLocation();

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        serviceHandler = new Handler(handlerThread.getLooper());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel mChannel =
                    new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);

            // Set the Notification Channel for the Notification Manager.
            notificationManager.createNotificationChannel(mChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION,
                false);

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            removeLocationUpdates();
            stopSelf();
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
        stopForeground(true);
        changingConfiguration = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        stopForeground(true);
        changingConfiguration = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.
        if (!changingConfiguration && SettingsUtils.requestingLocationUpdates(this)) {
//            Log.i(TAG, "Starting foreground service");

            startForeground(NOTIFICATION_ID, getNotification());
        }
        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        notificationManager.cancelAll();
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
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                CharSequence text = getText(R.string.app_name);
                Intent intent = new Intent(getApplicationContext(), ForegroundLocationService.class);
                PendingIntent servicePendingIntent = PendingIntent.getService(getApplicationContext(), 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent turnOnGps = PendingIntent.getActivity(getApplicationContext(), 0,
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 0);
                Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .addAction(R.drawable.ic_launch, "Ligar GPS", turnOnGps)
                        .addAction(R.drawable.ic_cancel, "Cancelar", servicePendingIntent)
                        .setContentText(text)
                        .setContentTitle("SOS Maria precisa ler sua localização")
                        .setOngoing(true)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setSmallIcon(R.mipmap.ic_launcher_round)
                        .setTicker(text)
                        .build();
                notificationManager.notify(Constants.FOREGROUND_NOTIFICATION_ID,notification);
                SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
                sp.edit().putBoolean(Constants.PANIC,false).apply();
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
                    "Notification title",
                    "This app needs a write permission",
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
        try {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            SettingsUtils.setRequestingLocationUpdates(this, false);
            stopSelf();
        } catch (SecurityException unlikely) {
            SettingsUtils.setRequestingLocationUpdates(this, true);
            Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
            PermissionEverywhere.getPermission(getApplicationContext(),
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    Constants.GPS_PERMISSION_REQUEST,
                    "Notification title",
                    "This app needs a write permission",
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

    private Notification getNotification() {
        Intent intent = new Intent(this, ForegroundLocationService.class);

        CharSequence text = getText(R.string.app_name);

        // Extra to help us figure out if we arrived in onStartCommand via the notification or not.
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

        // The PendingIntent that leads to a call to onStartCommand() in this service.
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // The PendingIntent to launch activity.
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, SplashActivity.class), 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .addAction(R.drawable.ic_launch, "Abrir app", activityPendingIntent)
                .addAction(R.drawable.ic_cancel, "Cancelar", servicePendingIntent)
                .setContentText(text)
                .setContentTitle("Sua encomenda está a caminho")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setTicker(text)
                .build();
    }

    private void getLastLocation() {
        try {
            fusedLocationProviderClient.getLastLocation()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            location = task.getResult();
                        } else {
                            Log.w(TAG, "Failed to get location.");
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
            PermissionEverywhere.getPermission(getApplicationContext(),
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    Constants.GPS_PERMISSION_REQUEST,
                    "Notification title",
                    "This app needs a write permission",
                    R.mipmap.ic_launcher)
                    .enqueue(permissionResponse -> {
                        if (permissionResponse.isGranted()) {
                            getLastLocation();
                        } else {
                            stopSelf();
                        }
                    });
        }
    }

    private void onNewLocation(Location location) {
        Log.i(TAG, "New location: " + location);

        this.location = location;

        // Notify anyone listening for broadcasts about the new location.
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtra(EXTRA_LOCATION, location);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

        sendLocationToBackEnd();
        // Update notification content if running as a foreground service.
//        if (serviceIsRunningInForeground(this)) {
//            notificationManager.notify(NOTIFICATION_ID, getNotification());
//        }
    }

    private void sendLocationToBackEnd() {
        BackEndService client = HttpClient.getInstance();
        List<Double> position = new ArrayList<>();
        position.add(location.getLatitude());
        position.add(location.getLongitude());
        MyLocation myLocation = new MyLocation(position, true);
        SharedPreferences sp = SharedPreferenceUtil.getSharedePreferences();
        Call<Void> call = client.postLocation(myLocation, sp.getString(Constants.USER_TOKEN, ""));
        call.enqueue(responseCallback);
    }

    Callback<Void> responseCallback = new Callback<Void>() {
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
        locationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        locationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
//        locationRequest.setSmallestDisplacement(10);
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
