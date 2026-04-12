package com.appclone.core;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages work profile creation and app cloning operations.
 * Uses Android's Work Profile API for app isolation.
 */
public class ProfileManager {

    private static final String TAG = "ProfileManager";
    private static volatile ProfileManager instance;
    private final Context context;
    private final DevicePolicyManager dpm;
    private final UserManager userManager;

    private ProfileManager(Context context) {
        this.context = context.getApplicationContext();
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    public static ProfileManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ProfileManager.class) {
                if (instance == null) {
                    instance = new ProfileManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * Check if the app is the profile owner.
     */
    public boolean isProfileOwner() {
        ComponentName admin = getAdminComponent();
        if (admin == null) return false;
        return dpm.isProfileOwnerApp(admin.getPackageName());
    }

    /**
     * Check if a work profile exists.
     */
    public boolean hasWorkProfile() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<android.os.UserHandle> profiles = userManager.getUserProfiles();
                return profiles.size() > 1;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking work profile", e);
        }
        return false;
    }

    /**
     * Get the admin component name for device admin receiver.
     */
    private ComponentName getAdminComponent() {
        try {
            return new ComponentName(context, com.appclone.receiver.CloneDeviceAdminReceiver.class);
        } catch (Exception e) {
            Log.e(TAG, "Error getting admin component", e);
            return null;
        }
    }

    /**
     * Start the profile provisioning process.
     * This will launch Android's built-in provisioning flow.
     */
    public void startProvisioning() {
        try {
            Intent intent = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                    getAdminComponent());
            }
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting provisioning", e);
        }
    }

    /**
     * Clone an app by installing it in the work profile.
     */
    public boolean cloneApp(String packageName) {
        if (!isProfileOwner() || !hasWorkProfile()) {
            Log.w(TAG, "Cannot clone: not profile owner or no work profile");
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.enableSystemApp(getAdminComponent(), packageName);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cloning app: " + packageName, e);
        }
        return false;
    }

    /**
     * Remove a cloned app from the work profile.
     */
    public boolean removeClone(String packageName) {
        if (!isProfileOwner()) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setApplicationHidden(getAdminComponent(), packageName, true);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing clone: " + packageName, e);
        }
        return false;
    }

    /**
     * Get list of cloned apps in the work profile.
     */
    public List<String> getClonedApps() {
        List<String> cloned = new ArrayList<>();
        if (!isProfileOwner()) return cloned;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<String> apps = dpm.getInstalledApplications(getAdminComponent(), 0);
                if (apps != null) {
                    cloned.addAll(apps);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting cloned apps", e);
        }
        return cloned;
    }

    /**
     * Remove the work profile entirely.
     */
    public void removeWorkProfile() {
        if (!isProfileOwner()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.wipeData(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing work profile", e);
        }
    }
}
