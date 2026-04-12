package com.appclone.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Device Admin Receiver for managing the Work Profile.
 * Required for the profile owner functionality.
 */
public class CloneDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "CloneDeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin enabled");
        Toast.makeText(context, "AppClone: Quản lý hồ sơ đã được kích hoạt", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin disabled");
        Toast.makeText(context, "AppClone: Quản lý hồ sơ đã bị vô hiệu hóa", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        Log.i(TAG, "Profile provisioning complete");
    }
}
