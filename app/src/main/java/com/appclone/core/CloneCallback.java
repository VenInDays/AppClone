package com.appclone.core;

/**
 * Callback interface for clone operations.
 */
public interface CloneCallback {
    void onSuccess(String packageName, int cloneId);
    void onFailure(String packageName, String error);
    void onProgress(String packageName, int progress);
}
