package com.appclone.core;

/**
 * Callback interface for clone operations with progress tracking.
 */
public interface CloneCallback {
    /**
     * Called when clone operation makes progress.
     * @param packageName The original package being cloned
     * @param progress    Progress percentage (0-100)
     * @param message     Human-readable status message (Vietnamese)
     */
    void onProgress(String packageName, int progress, String message);

    /**
     * Called when clone succeeds and APK is ready.
     * @param originalPackage  Original package name
     * @param clonedPackage    New clone package name
     * @param cloneLabel       Display label for the clone
     * @param cloneNumber      Clone number (1-based)
     * @param apkPath          Absolute path to the signed clone APK
     */
    void onSuccess(String originalPackage, String clonedPackage,
                   String cloneLabel, int cloneNumber, String apkPath);

    /**
     * Called when clone fails.
     * @param packageName The original package name
     * @param error       Error message
     */
    void onFailure(String packageName, String error);
}
