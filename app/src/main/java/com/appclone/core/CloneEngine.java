package com.appclone.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core engine for scanning and managing installed applications.
 * Written in Java as part of the core layer.
 */
public class CloneEngine {

    private static final String TAG = "CloneEngine";
    private static volatile CloneEngine instance;
    private final Context context;
    private final PackageManager pm;
    private final ExecutorService executor;

    // Popular apps that are commonly cloned
    private static final String[] KNOWN_CLONE_TARGETS = {
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.whatsapp",
        "com.zing.zalo",
        "com.instagram.android",
        "org.telegram.messenger",
        "com.snapchat.android",
        "com.twitter.android",
        "com.linkedin.android",
        "com.skype.raider",
        "com.viber.voip",
        "com.google.android.apps.messaging",
        "com.discord",
        "com.tencent.mm",
        "linecorp.linelite",
        "jp.naver.line.android",
        "com.kakao.talk",
        "org.thoughtcrime.securesms",
        "com.samsung.android.messaging",
    };

    private CloneEngine(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
        this.executor = Executors.newFixedThreadPool(4);
    }

    public static CloneEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (CloneEngine.class) {
                if (instance == null) {
                    instance = new CloneEngine(context);
                }
            }
        }
        return instance;
    }

    /**
     * Get all installed apps that can be cloned.
     */
    public List<AppInfo> getAllInstalledApps() {
        List<AppInfo> appList = new ArrayList<>();
        List<PackageInfo> packages;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
        } else {
            packages = pm.getInstalledPackages(0);
        }

        for (PackageInfo pkgInfo : packages) {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            if (appInfo == null) continue;
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (appInfo.packageName.equals(context.getPackageName())) continue;

            AppInfo info = new AppInfo();
            info.setPackageName(pkgInfo.packageName);
            info.setAppName(pm.getApplicationLabel(appInfo).toString());
            info.setVersionName(pkgInfo.versionName != null ? pkgInfo.versionName : "Unknown");
            info.setVersionCode(pkgInfo.versionCode);
            info.setFirstInstallTime(pkgInfo.firstInstallTime);
            info.setLastUpdateTime(pkgInfo.lastUpdateTime);
            info.setSystemApp((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);

            try {
                File apkFile = new File(appInfo.sourceDir);
                info.setAppSize(apkFile.length());
            } catch (Exception e) {
                info.setAppSize(0);
            }

            info.setCategory(getCategory(appInfo.packageName));

            appList.add(info);
        }

        Collections.sort(appList, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
        return appList;
    }

    /**
     * Get list of popular clone-able apps (filter from installed apps).
     */
    public List<AppInfo> getPopularCloneTargets() {
        List<AppInfo> allApps = getAllInstalledApps();
        List<AppInfo> popular = new ArrayList<>();
        for (AppInfo info : allApps) {
            for (String target : KNOWN_CLONE_TARGETS) {
                if (info.getPackageName().equals(target)) {
                    popular.add(info);
                    break;
                }
            }
        }
        return popular;
    }

    /**
     * Search apps by name or package name.
     */
    public List<AppInfo> searchApps(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllInstalledApps();
        }
        String lowerQuery = query.toLowerCase().trim();
        List<AppInfo> allApps = getAllInstalledApps();
        List<AppInfo> results = new ArrayList<>();
        for (AppInfo info : allApps) {
            if (info.getAppName().toLowerCase().contains(lowerQuery) ||
                info.getPackageName().toLowerCase().contains(lowerQuery)) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * Get app icon drawable
     */
    public Drawable getAppIcon(String packageName) {
        try {
            return pm.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Icon not found for: " + packageName);
            return pm.getDefaultActivityIcon();
        }
    }

    /**
     * Get application info for a specific package
     */
    public AppInfo getAppInfo(String packageName) {
        try {
            PackageInfo pkgInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgInfo = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
            } else {
                pkgInfo = pm.getPackageInfo(packageName, 0);
            }
            if (pkgInfo != null && pkgInfo.applicationInfo != null) {
                AppInfo info = new AppInfo();
                info.setPackageName(pkgInfo.packageName);
                info.setAppName(pm.getApplicationLabel(pkgInfo.applicationInfo).toString());
                info.setVersionName(pkgInfo.versionName != null ? pkgInfo.versionName : "Unknown");
                info.setVersionCode(pkgInfo.versionCode);
                info.setFirstInstallTime(pkgInfo.firstInstallTime);
                info.setLastUpdateTime(pkgInfo.lastUpdateTime);
                return info;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "App not found: " + packageName, e);
        }
        return null;
    }

    /**
     * Get APK file path for a package
     */
    public String getApkPath(String packageName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return appInfo.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Check if an app is a known clone target
     */
    public boolean isKnownCloneTarget(String packageName) {
        for (String target : KNOWN_CLONE_TARGETS) {
            if (target.equals(packageName)) return true;
        }
        return false;
    }

    /**
     * Get category for an app based on package name
     */
    private String getCategory(String packageName) {
        if (packageName.contains("facebook") || packageName.contains("instagram") ||
            packageName.contains("twitter") || packageName.contains("linkedin") ||
            packageName.contains("snapchat")) return "Social";
        if (packageName.contains("whatsapp") || packageName.contains("messenger") ||
            packageName.contains("zalo") || packageName.contains("telegram") ||
            packageName.contains("skype") || packageName.contains("viber") ||
            packageName.contains("discord") || packageName.contains("signal") ||
            packageName.contains("line")) return "Messaging";
        if (packageName.contains("google") || packageName.contains("gmail")) return "Google";
        return "Other";
    }

    /**
     * Run a task on the background executor
     */
    public <T> void executeAsync(Callable<T> task, CloneCallback<T> callback) {
        executor.execute(() -> {
            try {
                T result = task.call();
                if (callback != null) {
                    callback.onResult(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "Async task failed", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    public interface CloneCallback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
