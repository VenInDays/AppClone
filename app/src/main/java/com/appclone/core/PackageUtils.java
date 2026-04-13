package com.appclone.core;

/**
 * Utility class for generating clone package names.
 * Ensures the clone package name has the EXACT same character length
 * as the original, enabling safe byte-level replacement in binary files.
 */
public class PackageUtils {

    /**
     * Generate a clone package name with the SAME character length as the original.
     * Strategy: truncate the last N characters of the original and replace with clone number.
     *
     * Examples:
     *   com.facebook.katana (20 chars) -> clone 1 -> com.facebook.katan1 (20 chars)
     *   com.facebook.katana (20 chars) -> clone 9 -> com.facebook.katan9 (20 chars)
     *   com.facebook.katana (20 chars) -> clone 10 -> com.facebook.kat10 (20 chars)
     *   com.zing.zalo (13 chars) -> clone 1 -> com.zing.zal1 (13 chars)
     *   com.whatsapp (11 chars) -> clone 1 -> com.whatsap1 (11 chars)
     */
    public static String generateClonePackage(String originalPackage, int cloneNumber) {
        int origLen = originalPackage.length();
        if (origLen < 3) {
            return originalPackage + "_c" + cloneNumber;
        }

        int numDigits = String.valueOf(cloneNumber).length();
        // We need to truncate the last (numDigits) characters and replace with the number
        // But we must keep at least the domain prefix intact (e.g., "com.")
        int minKeep = originalPackage.lastIndexOf('.');
        if (minKeep < 0) minKeep = 0;
        minKeep++; // Keep at least "com." -> keep position after the dot

        // The suffix (clone number) goes at the end
        String suffix = String.valueOf(cloneNumber);
        int needed = suffix.length();

        if (origLen - needed <= minKeep) {
            // Package name too short to truncate properly
            // Use a different strategy: insert number before the last dot segment
            int lastDot = originalPackage.lastIndexOf('.');
            String prefix = originalPackage.substring(0, lastDot);
            String lastSegment = originalPackage.substring(lastDot + 1);
            // Try to fit: e.g., "com.zalo" -> "com.1zalo" (same length)
            if (prefix.length() + 1 + lastSegment.length() == origLen) {
                // Can insert 1 digit before last segment
                if (cloneNumber < 10) {
                    return prefix + "." + cloneNumber + lastSegment.substring(1);
                }
            }
            // Fallback: replace last chars
            int truncateTo = origLen - needed;
            if (truncateTo < 1) truncateTo = 1;
            return originalPackage.substring(0, truncateTo) + suffix;
        }

        // Normal case: truncate last N chars and append number
        return originalPackage.substring(0, origLen - needed) + suffix;
    }

    /**
     * Convert package name to file-safe name.
     * com.facebook.katana1 -> com_facebook_katana1
     */
    public static String packageToFileName(String packageName) {
        return packageName.replace('.', '_');
    }

    /**
     * Convert dotted package name to slash-separated (for DEX references).
     * com.facebook.katana -> com/facebook/katana
     */
    public static String dotToSlash(String dotted) {
        return dotted.replace('.', '/');
    }

    /**
     * Convert slash-separated package to dotted.
     * com/facebook/katana -> com.facebook.katana
     */
    public static String slashToDot(String slashed) {
        return slashed.replace('/', '.');
    }

    /**
     * Generate a clone app label.
     * "Facebook" + clone 1 -> "Facebook (Clone 1)"
     */
    public static String generateCloneLabel(String originalName, int cloneNumber) {
        return originalName + " (Clone " + cloneNumber + ")";
    }
}
