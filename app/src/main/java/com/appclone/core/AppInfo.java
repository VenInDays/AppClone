package com.appclone.core;

/**
 * Represents an installed application that can be cloned.
 * This class holds all metadata about an app package.
 */
public class AppInfo {
    private String packageName;
    private String appName;
    private String versionName;
    int versionCode;
    long firstInstallTime;
    long lastUpdateTime;
    long appSize;
    boolean isSystemApp;
    boolean isCloned;
    int cloneCount;
    private String category;
    private String iconPath;

    public AppInfo() {}

    public AppInfo(String packageName, String appName) {
        this.packageName = packageName;
        this.appName = appName;
    }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public int getVersionCode() { return versionCode; }
    public void setVersionCode(int versionCode) { this.versionCode = versionCode; }
    public long getFirstInstallTime() { return firstInstallTime; }
    public void setFirstInstallTime(long firstInstallTime) { this.firstInstallTime = firstInstallTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public long getAppSize() { return appSize; }
    public void setAppSize(long appSize) { this.appSize = appSize; }
    public boolean isSystemApp() { return isSystemApp; }
    public void setSystemApp(boolean systemApp) { this.isSystemApp = systemApp; }
    public boolean isCloned() { return isCloned; }
    public void setCloned(boolean cloned) { this.isCloned = cloned; }
    public int getCloneCount() { return cloneCount; }
    public void setCloneCount(int cloneCount) { this.cloneCount = cloneCount; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getIconPath() { return iconPath; }
    public void setIconPath(String iconPath) { this.iconPath = iconPath; }

    public String getFormattedSize() {
        if (appSize <= 0) return "Unknown";
        if (appSize < 1024) return appSize + " B";
        if (appSize < 1024 * 1024) return String.format("%.1f KB", appSize / 1024.0);
        if (appSize < 1024 * 1024 * 1024) return String.format("%.1f MB", appSize / (1024.0 * 1024));
        return String.format("%.1f GB", appSize / (1024.0 * 1024 * 1024));
    }

    public String getCloneLabel() {
        return appName + " (Clone " + (cloneCount + 1) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppInfo appInfo = (AppInfo) o;
        return packageName != null && packageName.equals(appInfo.packageName);
    }

    @Override
    public int hashCode() {
        return packageName != null ? packageName.hashCode() : 0;
    }
}
