package com.appclone.core;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Edits APK binary files by performing byte-level search and replace.
 * This works because clone package names have the EXACT same byte length
 * as originals, so offsets remain valid.
 */
public class BinaryFileEditor {

    private static final String TAG = "BinaryFileEditor";

    /**
     * Represents a replacement to apply to a ZIP entry.
     */
    public static class Replacement {
        public final byte[] oldBytes;
        public final byte[] newBytes;

        public Replacement(String oldStr, String newStr, Charset charset) {
            this.oldBytes = oldStr.getBytes(charset);
            this.newBytes = newStr.getBytes(charset);
        }
    }

    /**
     * Modify a ZIP/APK file in-place by applying replacements to specified entries.
     * Also removes old META-INF signature files.
     *
     * @param apkFile        The APK file to modify
     * @param replacements   Map of entry name -> list of replacements
     */
    public static void modifyZip(File apkFile, Map<String, List<Replacement>> replacements) throws IOException {
        File tempFile = File.createTempFile("apk_mod_", ".apk", apkFile.getParentFile());

        try (ZipFile zf = new ZipFile(apkFile);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // Remove old signature files (we'll re-sign later)
                if (name.startsWith("META-INF/")) {
                    continue;
                }

                // Read entry data
                byte[] data = readAllBytes(zf.getInputStream(entry));

                // Apply replacements for this entry
                List<Replacement> entryReplacements = replacements.get(name);
                if (entryReplacements != null) {
                    for (Replacement rep : entryReplacements) {
                        data = replaceAll(data, rep.oldBytes, rep.newBytes);
                    }
                }

                // Also apply global replacements (applied to all entries)
                List<Replacement> globalReplacements = replacements.get("*");
                if (globalReplacements != null) {
                    for (Replacement rep : globalReplacements) {
                        data = replaceAll(data, rep.oldBytes, rep.newBytes);
                    }
                }

                // Write entry - always use DEFLATED to avoid STORED entry issues
                ZipEntry newEntry = new ZipEntry(name);
                newEntry.setMethod(ZipEntry.DEFLATED);
                zos.putNextEntry(newEntry);
                zos.write(data);
                zos.closeEntry();
            }
        }

        // Replace original file
        if (!apkFile.delete()) {
            tempFile.delete();
            throw new IOException("Failed to delete original APK");
        }
        if (!tempFile.renameTo(apkFile)) {
            throw new IOException("Failed to rename temp file to original APK");
        }
    }

    /**
     * Modify a ZIP/APK file: replace all occurrences of oldPkg with newPkg.
     * Handles binary XML (UTF-16LE) and DEX files (MUTF-8/UTF-8).
     */
    public static void modifyPackageInApk(File apkFile, String oldPkg, String newPkg) throws IOException {
        Map<String, List<Replacement>> replacements = new LinkedHashMap<>();

        // For binary XML files (AndroidManifest.xml and others): UTF-16LE
        // For DEX files: UTF-8 (MUTF-8 is UTF-8 compatible for ASCII)
        // For other files: try UTF-8

        String oldSlash = PackageUtils.dotToSlash(oldPkg);
        String newSlash = PackageUtils.dotToSlash(newPkg);

        // Dotted form (for manifests): UTF-16LE for XML, UTF-8 for DEX
        // Slash form (for DEX class references): UTF-8

        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);

                List<Replacement> entryReplacements = new ArrayList<>();

                if (name.equals("androidmanifest.xml") || name.endsWith(".xml")) {
                    // Binary XML: replace dotted package name in UTF-16LE
                    entryReplacements.add(new Replacement(oldPkg, newPkg, StandardCharsets.UTF_16LE));
                }

                if (name.endsWith(".dex")) {
                    // DEX files: replace both dotted and slash-separated forms in UTF-8
                    entryReplacements.add(new Replacement(oldPkg, newPkg, StandardCharsets.UTF_8));
                    entryReplacements.add(new Replacement(oldSlash, newSlash, StandardCharsets.UTF_8));
                }

                // Also check for .so libraries (native code) - skip for safety
                if (name.endsWith(".so") || name.endsWith(".png") || name.endsWith(".jpg")
                        || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".ttf")
                        || name.endsWith(".otf")) {
                    continue; // Skip binary resources
                }

                if (!entryReplacements.isEmpty()) {
                    replacements.put(entry.getName(), entryReplacements);
                }
            }
        }

        modifyZip(apkFile, replacements);
    }

    /**
     * Replace all occurrences of a byte pattern in data.
     */
    public static byte[] replaceAll(byte[] data, byte[] oldBytes, byte[] newBytes) {
        if (oldBytes.length == 0) return data;
        if (oldBytes.length != newBytes.length) {
            // Lengths must match for safe binary replacement
            throw new IllegalArgumentException(
                "Byte lengths must match: old=" + oldBytes.length + " new=" + newBytes.length);
        }

        // Count occurrences first
        int count = 0;
        int pos = 0;
        while ((pos = indexOf(data, oldBytes, pos)) >= 0) {
            count++;
            pos += oldBytes.length;
        }

        if (count == 0) return data; // No changes needed

        // Since lengths are equal, we can modify in place
        byte[] result = data.clone();
        pos = 0;
        while ((pos = indexOf(result, oldBytes, pos)) >= 0) {
            System.arraycopy(newBytes, 0, result, pos, newBytes.length);
            pos += newBytes.length;
        }
        return result;
    }

    /**
     * Find first occurrence of pattern in data starting from offset.
     */
    private static int indexOf(byte[] data, byte[] target, int from) {
        if (target.length == 0) return from;
        if (from < 0) from = 0;
        if (from > data.length - target.length) return -1;

        outer:
        for (int i = from; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Read all bytes from an input stream.
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}
