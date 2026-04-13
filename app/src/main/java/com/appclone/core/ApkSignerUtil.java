package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * APK Signing utility using V1 (JAR) signing scheme.
 * Uses BouncyCastle CMSSignedDataGenerator for PKCS#7 signature.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";
    private static final String KEY_ALIAS = "appclone_signer";

    /**
     * Sign an unsigned APK with V1 (JAR) signing scheme.
     */
    public static void signApk(File inputApk, File outputApk) throws Exception {
        KeyStore ks = getOrCreateKeyStore(outputApk.getParentFile());
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(
            KEY_ALIAS, new KeyStore.PasswordProtection("appclone123".toCharArray()));

        PrivateKey privateKey = entry.getPrivateKey();
        X509Certificate cert = (X509Certificate) entry.getCertificate();

        Map<String, byte[]> entries = readZipEntries(inputApk);
        byte[] manifestBytes = createManifest(entries);
        byte[] sfBytes = createSignatureFile(manifestBytes, entries);
        byte[] pkcs7Bytes = createPkcs7Signature(sfBytes, privateKey, cert);

        writeSignedZip(outputApk, entries, manifestBytes, sfBytes, pkcs7Bytes);
    }

    private static KeyStore getOrCreateKeyStore(File dir) throws Exception {
        File ksFile = new File(dir, ".signing_keystore");
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, "appclone123".toCharArray());
            }
            if (ks.containsAlias(KEY_ALIAS)) {
                return ks;
            }
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000);

        X509Certificate cert = createSelfSignedCertificate(
            keyPair, "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN",
            startDate, endDate
        );

        ks.load(null, null);
        ks.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(),
            "appclone123".toCharArray(),
            new java.security.cert.Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, "appclone123".toCharArray());
        }

        return ks;
    }

    private static X509Certificate createSelfSignedCertificate(
            KeyPair keyPair, String dn, Date startDate, Date endDate) throws Exception {

        org.bouncycastle.asn1.x500.X500Name issuerName =
            new org.bouncycastle.asn1.x500.X500Name(dn);
        org.bouncycastle.asn1.x500.X500Name subjectName =
            new org.bouncycastle.asn1.x500.X500Name(dn);

        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo pubKeyInfo =
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
                keyPair.getPublic().getEncoded());

        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
            new org.bouncycastle.cert.X509v3CertificateBuilder(
                issuerName, serial, startDate, endDate, subjectName, pubKeyInfo);

        org.bouncycastle.cert.jcajce.JcaContentSignerBuilder signerBuilder =
            new org.bouncycastle.cert.jcajce.JcaContentSignerBuilder("SHA256withRSA");
        org.bouncycastle.cert.X509CertificateHolder certHolder =
            certBuilder.build(signerBuilder.build(keyPair.getPrivate()));

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        ByteArrayInputStream bais = new ByteArrayInputStream(certHolder.getEncoded());
        return (X509Certificate) cf.generateCertificate(bais);
    }

    private static Map<String, byte[]> readZipEntries(File apkFile) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] data = readStream(zf.getInputStream(entry));
                entries.put(entry.getName(), data);
            }
        }
        return entries;
    }

    private static byte[] createManifest(Map<String, byte[]> entries) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();

        sb.append("Manifest-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n\r\n");

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            byte[] data = e.getValue();
            byte[] digest = md.digest(data);
            String digestBase64 = Base64.getEncoder().encodeToString(digest);
            sb.append("Name: ").append(name).append("\r\n");
            sb.append("SHA-256-Digest: ").append(digestBase64).append("\r\n\r\n");
        }

        return sb.toString().getBytes("UTF-8");
    }

    private static byte[] createSignatureFile(byte[] manifestBytes, Map<String, byte[]> entries)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();

        sb.append("Signature-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n");

        byte[] manifestDigest = md.digest(manifestBytes);
        String manifestDigestBase64 = Base64.getEncoder().encodeToString(manifestDigest);
        sb.append("SHA-256-Digest-Manifest: ").append(manifestDigestBase64).append("\r\n");

        String manifestStr = new String(manifestBytes, "UTF-8");
        int firstSectionEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstSectionEnd > 0) {
            byte[] mainAttrsBytes = manifestStr.substring(0, firstSectionEnd).getBytes("UTF-8");
            byte[] mainAttrsDigest = md.digest(mainAttrsBytes);
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
              .append(Base64.getEncoder().encodeToString(mainAttrsDigest)).append("\r\n");
        }

        sb.append("\r\n");

        for (String entryName : entries.keySet()) {
            String sectionStart = "Name: " + entryName + "\r\n";
            int sectionIdx = manifestStr.indexOf(sectionStart);
            if (sectionIdx >= 0) {
                int nextSectionIdx = manifestStr.indexOf("\r\n\r\n", sectionIdx + sectionStart.length());
                if (nextSectionIdx > sectionIdx) {
                    String section = manifestStr.substring(sectionIdx, nextSectionIdx);
                    byte[] sectionDigest = md.digest(section.getBytes("UTF-8"));
                    sb.append("Name: ").append(entryName).append("\r\n");
                    sb.append("SHA-256-Digest: ")
                      .append(Base64.getEncoder().encodeToString(sectionDigest)).append("\r\n\r\n");
                }
            }
        }

        return sb.toString().getBytes("UTF-8");
    }

    /**
     * Create PKCS#7 signature block using CMSSignedDataGenerator.
     * Compatible with both Android built-in BC and external bcprov-jdk15on.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        // Sign the signature file
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();

        // Use high-level CMSSignedDataGenerator API
        org.bouncycastle.cms.CMSSignedDataGenerator gen = new org.bouncycastle.cms.CMSSignedDataGenerator();

        // Add certificate
        org.bouncycastle.cert.X509CertificateHolder certHolder =
            new org.bouncycastle.cert.X509CertificateHolder(cert.getEncoded());
        gen.addCertificate(certHolder);

        // Add signer info using content signer
        org.bouncycastle.cert.jcajce.JcaContentSignerBuilder contentSignerBuilder =
            new org.bouncycastle.cert.jcajce.JcaContentSignerBuilder("SHA256withRSA");
        org.bouncycastle.operator.ContentSigner contentSigner =
            contentSignerBuilder.build(privateKey);

        org.bouncycastle.cms.CMSProcessableByteArray content =
            new org.bouncycastle.cms.CMSProcessableByteArray(sfBytes);

        org.bouncycastle.cms.CMSSignedData signedData = gen.generate(content, false);

        // Return DER-encoded PKCS#7
        org.bouncycastle.asn1.cms.ContentInfo contentInfo = signedData.toASN1Structure();
        return contentInfo.getEncoded("DER");
    }

    private static void writeSignedZip(File outputApk, Map<String, byte[]> entries,
                                        byte[] manifestBytes, byte[] sfBytes,
                                        byte[] pkcs7Bytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputApk)))) {

            ZipEntry mfEntry = new ZipEntry("META-INF/MANIFEST.MF");
            zos.putNextEntry(mfEntry);
            zos.write(manifestBytes);
            zos.closeEntry();

            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }

            ZipEntry sfEntry = new ZipEntry("META-INF/" + SIGNER_NAME + ".SF");
            zos.putNextEntry(sfEntry);
            zos.write(sfBytes);
            zos.closeEntry();

            String sigExtension = "RSA";
            ZipEntry rsaEntry = new ZipEntry("META-INF/" + SIGNER_NAME + "." + sigExtension);
            zos.putNextEntry(rsaEntry);
            zos.write(pkcs7Bytes);
            zos.closeEntry();
        }
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}
