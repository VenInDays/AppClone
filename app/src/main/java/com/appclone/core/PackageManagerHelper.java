package com.appclone.core;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Helper class for package management operations.
 */
public class PackageManagerHelper {

    /**
     * Check if an app is installed
     */
    public static boolean isAppInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Get app version name
     */
    public static String getAppVersionName(Context context, String packageName) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                .getPackageInfo(packageName, 0);
            return info.versionName != null ? info.versionName : "Unknown";
        } catch (PackageManager.NameNotFoundException e) {
            return "N/A";
        }
    }

    /**
     * Launch an app
     */
    public static boolean launchApp(Context context, String packageName) {
        try {
            Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Get app UID for a package
     */
    public static int getAppUid(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }
}
