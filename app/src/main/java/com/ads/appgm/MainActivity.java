package com.ads.appgm;

import android.content.Intent;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ads.appgm.clickListeners.ButtonPanic;
import com.ads.appgm.databinding.ActivityMainBinding;
import com.ads.appgm.dialog.PermissionDialog;
import com.ads.appgm.manager.PanicManager;
import com.ads.appgm.manager.PaniqueManagerListener;
import com.ads.appgm.manager.device.output.OutputDeviceListener;
import com.ads.appgm.service.PaniqueQuick;
import com.ads.appgm.util.SettingsUtils;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;


public class MainActivity extends AppCompatActivity implements PaniqueManagerListener {

    private ActivityMainBinding binding;
    public static MainActivity instance;

    private TransitionDrawable transAnimButFlash;

    boolean panicButtonStatus = false;
    int btnAnimTime = 200;
    private FusedLocationProviderClient fusedLocationProviderClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        binding.panicFunction.setOnClickListener(this::openAccessibilitySettings);

        //transAnimButFlash = (TransitionDrawable) panicButton.getBackground();
        //transAnimButFlash.resetTransition();

        setSupportActionBar(binding.toolbar.getRoot());
    }

    @Override
    protected void onStart() {
        Button panic = findViewById(R.id.buttonPanic);

        panic.setOnClickListener(new ButtonPanic(fusedLocationProviderClient, this));

        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPaniqueQuickServiceRunning()) {
            PaniqueQuick.getInstance().registerPaniqueManagerListener(this);
            if (this.isPanicOn()) {
                this.setPanicButtonStatus(this.isPanicOn());
            }
        }
        binding.panicFunction.setChecked(isPaniqueQuickServiceRunning());
        instance=this;
    }

    @Override
    protected void onPause() {
        if (this.isPaniqueQuickServiceRunning()) {
            PaniqueQuick.getInstance().unregisterPaniqueManagerListener();
        } else if (this.isPanicOn()) {
            this.togglePanic(null);
        }
        super.onPause();
        instance=null;
    }

    @Override
    public void onError(String error) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPanicStatusChanged(final boolean status) {
        runOnUiThread(() -> setPanicButtonStatus(status));
    }

    public void openAccessibilitySettings(View v) {
        if (binding.panicFunction.isChecked()) {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } else {
            this.showDialogPermission();
        }
    }

    private void showDialogPermission() {
        PermissionDialog permissionDialog = new PermissionDialog();
        permissionDialog.show(getFragmentManager(), "Permission Dialog");
    }

    private void setPanicButtonStatus(boolean enabled) {
        panicButtonStatus = enabled;
        if (panicButtonStatus) {
            //transAnimButFlash.startTransition(btnAnimTime);
        } else {
            //transAnimButFlash.reverseTransition(btnAnimTime);
        }
    }

    private boolean isPaniqueQuickServiceRunning() {
        return PaniqueQuick.getInstance() != null;
    }

    public void togglePanic(View v) {
        if (isPaniqueQuickServiceRunning()) {
            PaniqueQuick.getInstance().togglePanic();
        } else {
            PanicManager.getInstance(SettingsUtils.getPanicSource(this), true).setListener(new OutputDeviceListener() {
                @Override
                public void onStatusChanged(String deviceType, final boolean status) {
                    runOnUiThread(() -> setPanicButtonStatus(status));
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            });
            PanicManager.getInstance(SettingsUtils.getPanicSource(this), true).toggle(this);
        }
    }

    private boolean isPanicOn() {
        if (isPaniqueQuickServiceRunning()) {
            return PaniqueQuick.getInstance().getPanicStatus();
        } else {
            return PanicManager.getInstance(SettingsUtils.getPanicSource(this), true).getStatus();
        }
    }
}
