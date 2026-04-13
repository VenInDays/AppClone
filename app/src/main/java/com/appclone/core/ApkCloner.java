package com.appclone.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.FileProvider;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core APK cloning engine.
 * 
 * Clones apps by:
 * 1. Extracting the source APK
 * 2. Modifying package name in binary manifest and DEX files (same-length replacement)
 * 3. Signing the modified APK with V1 scheme
 * 4. Triggering installation of the cloned APK
 *
 * This approach creates a truly independent app copy with a different package name,
 * allowing it to run alongside the original app with separate data.
 */
public class ApkCloner {

    private static final String TAG = "ApkCloner";
    private static volatile ApkCloner instance;
    private final Context context;
    private final File cloneOutputDir;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private ApkCloner(Context context) {
        this.context = context.getApplicationContext();
        this.cloneOutputDir = new File(context.getExternalFilesDir(), "cloned_apks");
        if (!cloneOutputDir.exists()) {
            cloneOutputDir.mkdirs();
        }
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static ApkCloner getInstance(Context context) {
        if (instance == null) {
            synchronized (ApkCloner.class) {
                if (instance == null) {
                    instance = new ApkCloner(context);
                }
            }
        }
        return instance;
    }

    /**
     * Result of a clone operation.
     */
    public static class CloneResult {
        public final boolean success;
        public final String originalPackage;
        public final String clonedPackage;
        public final String cloneLabel;
        public final int cloneNumber;
        public final String apkPath;
        public final String error;

        private CloneResult(boolean success, String originalPackage, String clonedPackage,
                            String cloneLabel, int cloneNumber, String apkPath, String error) {
            this.success = success;
            this.originalPackage = originalPackage;
            this.clonedPackage = clonedPackage;
            this.cloneLabel = cloneLabel;
            this.cloneNumber = cloneNumber;
            this.apkPath = apkPath;
            this.error = error;
        }

        public static CloneResult success(String originalPkg, String clonedPkg,
                                           String label, int number, String path) {
            return new CloneResult(true, originalPkg, clonedPkg, label, number, path, null);
        }

        public static CloneResult failure(String originalPkg, String error) {
            return new CloneResult(false, originalPkg, null, null, 0, null, error);
        }
    }

    /**
     * Clone an app asynchronously.
     *
     * @param packageName The original app's package name
     * @param appName     The original app's display name
     * @param cloneCount  Number of existing clones (used to generate unique package name)
     * @param callback    Callback for progress and result
     */
    public void cloneApp(String packageName, String appName, int cloneCount,
                         CloneCallback callback) {
        executor.execute(() -> {
            try {
                // Step 1: Get source APK path
                postProgress(callback, packageName, 5, "Đang lấy thông tin ứng dụng...");
                ApplicationInfo appInfo = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
                String sourceApkPath = appInfo.sourceDir;

                if (sourceApkPath == null || !new File(sourceApkPath).exists()) {
                    postFailure(callback, packageName, "Không tìm thấy file APK của ứng dụng");
                    return;
                }

                // Step 2: Generate clone package name
                postProgress(callback, packageName, 10, "Đang tạo tên gói clone...");
                String clonePkg = PackageUtils.generateClonePackage(packageName, cloneCount);
                String cloneLabel = PackageUtils.generateCloneLabel(appName, cloneCount + 1);

                // Step 3: Check if clone already exists
                postProgress(callback, packageName, 15, "Đang kiểm tra...");
                if (isAppInstalled(clonePkg)) {
                    // Try next available number
                    for (int i = cloneCount + 1; i < 100; i++) {
                        clonePkg = PackageUtils.generateClonePackage(packageName, i);
                        cloneLabel = PackageUtils.generateCloneLabel(appName, i + 1);
                        if (!isAppInstalled(clonePkg)) break;
                    }
                }

                // Step 4: Create working directory
                postProgress(callback, packageName, 20, "Đang chuẩn bị...");
                File workDir = new File(context.getCacheDir(), "clone_work_" + System.currentTimeMillis());
                workDir.mkdirs();

                try {
                    // Step 5: Copy APK to working directory
                    postProgress(callback, packageName, 25, "Đang sao chép APK...");
                    File workingApk = new File(workDir, "base.apk");
                    copyFile(new File(sourceApkPath), workingApk);

                    // Step 6: Modify APK - change package name
                    postProgress(callback, packageName, 35, "Đang sửa đổi package name...");
                    BinaryFileEditor.modifyPackageInApk(workingApk, packageName, clonePkg);

                    // Step 7: Sign the modified APK
                    postProgress(callback, packageName, 60, "Đang ký APK...");
                    File outputApk = new File(cloneOutputDir,
                        PackageUtils.packageToFileName(clonePkg) + ".apk");
                    ApkSignerUtil.signApk(workingApk, outputApk);

                    if (!outputApk.exists() || outputApk.length() == 0) {
                        postFailure(callback, packageName, "Ký APK thất bại");
                        return;
                    }

                    postProgress(callback, packageName, 85, "Đang chuẩn bị cài đặt...");

                    // Step 8: Save clone info to database (done by caller via callback)
                    postProgress(callback, packageName, 90, "Hoàn thành! Đang mở cài đặt...");

                    // Step 9: Trigger installation
                    boolean installStarted = installApk(outputApk);

                    if (installStarted) {
                        postProgress(callback, packageName, 100, "Clone thành công!");
                        postSuccess(callback, packageName, clonePkg, cloneLabel,
                            cloneCount + 1, outputApk.getAbsolutePath());
                    } else {
                        // APK is ready but install couldn't start automatically
                        // User can install manually from cloned apps list
                        postProgress(callback, packageName, 100, "Clone thành công! (Cài thủ công)");
                        postSuccess(callback, packageName, clonePkg, cloneLabel,
                            cloneCount + 1, outputApk.getAbsolutePath());
                    }

                } finally {
                    // Cleanup working directory
                    deleteDir(workDir);
                }

            } catch (final Exception e) {
                Log.e(TAG, "Clone failed for " + packageName, e);
                postFailure(callback, packageName, "Clone thất bại: " + e.getMessage());
            }
        });
    }

    /**
     * Install an APK file using system installer.
     */
    public boolean installApk(File apkFile) {
        try {
            Uri apkUri;
            try {
                apkUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apkFile);
            } catch (Exception e) {
                // Fallback: use file URI (works on older devices)
                Log.w(TAG, "FileProvider failed, using file URI", e);
                apkUri = Uri.fromFile(apkFile);
            }

            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start APK installation", e);
            return false;
        }
    }

    /**
     * Uninstall a cloned app.
     */
    public boolean uninstallClone(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to uninstall " + packageName, e);
            return false;
        }
    }

    /**
     * Check if an app is installed.
     */
    private boolean isAppInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Get the output directory for cloned APKs.
     */
    public File getCloneOutputDir() {
        return cloneOutputDir;
    }

    /**
     * Check if install permission is granted.
     */
    public boolean canInstallPackages() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /**
     * Request install permission (for Android 8+).
     */
    public void requestInstallPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request install permission", e);
            }
        }
    }

    // --- Private helpers ---

    private void postProgress(CloneCallback callback, String pkg, int progress, String message) {
        mainHandler.post(() -> callback.onProgress(pkg, progress, message));
    }

    private void postSuccess(CloneCallback callback, String origPkg, String clonePkg,
                              String label, int number, String apkPath) {
        mainHandler.post(() -> callback.onSuccess(origPkg, clonePkg, label, number, apkPath));
    }

    private void postFailure(CloneCallback callback, String pkg, String error) {
        mainHandler.post(() -> callback.onFailure(pkg, error));
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buffer = new byte[16384];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteDir(f);
            }
        }
        dir.delete();
    }

    public void shutdown() {
        executor.shutdown();
    }
}
