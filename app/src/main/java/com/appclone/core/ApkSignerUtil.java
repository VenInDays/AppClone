package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * APK Signing utility using V1 (JAR) signing scheme.
 * Uses Android's built-in BouncyCastle ASN.1 classes for PKCS#7 signature.
 * No external dependencies required.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";

    // Key alias for the generated signing key
    private static final String KEY_ALIAS = "appclone_signer";

    /**
     * Sign an unsigned APK with V1 (JAR) signing scheme.
     *
     * @param inputApk  The unsigned APK file
     * @param outputApk The output signed APK file
     */
    public static void signApk(File inputApk, File outputApk) throws Exception {
        // Step 1: Get or create signing key pair and certificate
        KeyStore ks = getOrCreateKeyStore(outputApk.getParentFile());
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(
            KEY_ALIAS, new KeyStore.PasswordProtection("appclone123".toCharArray()));

        PrivateKey privateKey = entry.getPrivateKey();
        X509Certificate cert = (X509Certificate) entry.getCertificate();

        // Step 2: Read all entries from the unsigned APK
        Map<String, byte[]> entries = readZipEntries(inputApk);

        // Step 3: Create MANIFEST.MF
        byte[] manifestBytes = createManifest(entries);

        // Step 4: Create CERT.SF (signature file)
        byte[] sfBytes = createSignatureFile(manifestBytes, entries);

        // Step 5: Create CERT.RSA (PKCS#7 signature block)
        byte[] pkcs7Bytes = createPkcs7Signature(sfBytes, privateKey, cert);

        // Step 6: Write the signed APK
        writeSignedZip(outputApk, entries, manifestBytes, sfBytes, pkcs7Bytes);
    }

    /**
     * Get or create a KeyStore with signing keys.
     */
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

        // Generate new key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        // Create self-signed certificate (valid for 30 years)
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000);

        X509Certificate cert = createSelfSignedCertificate(
            keyPair, "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN",
            startDate, endDate
        );

        // Save to keystore
        ks.load(null, null);
        ks.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(),
            "appclone123".toCharArray(),
            new java.security.cert.Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, "appclone123".toCharArray());
        }

        return ks;
    }

    /**
     * Create a self-signed X.509 certificate.
     */
    private static X509Certificate createSelfSignedCertificate(
            KeyPair keyPair, String dn, Date startDate, Date endDate) throws Exception {

        javax.security.auth.x500.X500Principal principal =
            new javax.security.auth.x500.X500Principal(dn);

        // Use BouncyCastle's X509V3CertificateGenerator via reflection/internals
        // On Android, we can use the built-in BouncyCastle
        org.bouncycastle.asn1.x500.X500Name issuerName =
            org.bouncycastle.asn1.x500.X500Name.getInstance(principal.getEncoded());
        org.bouncycastle.asn1.x500.X500Name subjectName =
            org.bouncycastle.asn1.x500.X500Name.getInstance(principal.getEncoded());

        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo pubKeyInfo =
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
                keyPair.getPublic().getEncoded());

        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
            new org.bouncycastle.cert.X509v3CertificateBuilder(
                issuerName, serial, startDate, endDate, subjectName, pubKeyInfo);

        // Add basic constraints
        org.bouncycastle.cert.X509CertificateHolder certHolder;
        try {
            org.bouncycastle.cert.jcajce.JcaContentSignerBuilder signerBuilder =
                new org.bouncycastle.cert.jcajce.JcaContentSignerBuilder("SHA256withRSA");
            signerBuilder.setProvider("BC");
            certHolder = certBuilder.build(signerBuilder.build(keyPair.getPrivate()));
        } catch (Exception e) {
            // Fallback: use default provider
            org.bouncycastle.cert.jcajce.JcaContentSignerBuilder signerBuilder =
                new org.bouncycastle.cert.jcajce.JcaContentSignerBuilder("SHA256withRSA");
            certHolder = certBuilder.build(signerBuilder.build(keyPair.getPrivate()));
        }

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        ByteArrayInputStream bais = new ByteArrayInputStream(certHolder.getEncoded());
        return (X509Certificate) cf.generateCertificate(bais);
    }

    /**
     * Read all entries from a ZIP/APK file.
     */
    private static Map<String, byte[]> readZipEntries(File apkFile) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                // Skip existing META-INF entries
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] data = readStream(zf.getInputStream(entry));
                entries.put(entry.getName(), data);
            }
        }
        return entries;
    }

    /**
     * Create MANIFEST.MF with SHA-256 digests for each entry.
     */
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

    /**
     * Create Signature File (CERT.SF) with digests of manifest sections.
     */
    private static byte[] createSignatureFile(byte[] manifestBytes, Map<String, byte[]> entries)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();

        sb.append("Signature-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n");

        // Digest of entire manifest
        byte[] manifestDigest = md.digest(manifestBytes);
        String manifestDigestBase64 = Base64.getEncoder().encodeToString(manifestDigest);
        sb.append("SHA-256-Digest-Manifest: ").append(manifestDigestBase64).append("\r\n");

        // Digest of manifest main attributes (everything before first blank line section)
        String manifestStr = new String(manifestBytes, "UTF-8");
        int firstSectionEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstSectionEnd > 0) {
            byte[] mainAttrsBytes = manifestStr.substring(0, firstSectionEnd).getBytes("UTF-8");
            byte[] mainAttrsDigest = md.digest(mainAttrsBytes);
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
              .append(Base64.getEncoder().encodeToString(mainAttrsDigest)).append("\r\n");
        }

        sb.append("\r\n");

        // Digest of each section in the manifest
        for (String entryName : entries.keySet()) {
            // Find the section for this entry in the manifest
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
     * Create PKCS#7 signature block (CERT.RSA) using Android's built-in BouncyCastle.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        // Sign the signature file
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();

        // Build PKCS#7 SignedData using BouncyCastle ASN.1 classes
        // (These are available on Android as part of the built-in BC provider)

        org.bouncycastle.asn1.x500.X500Name issuer =
            org.bouncycastle.asn1.x500.X500Name.getInstance(
                cert.getIssuerX500Principal().getEncoded());

        BigInteger serial = cert.getSerialNumber();

        org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber iasn =
            new org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber(issuer, serial);

        org.bouncycastle.asn1.cms.SignerIdentifier signerId =
            new org.bouncycastle.asn1.cms.SignerIdentifier(iasn);

        // Digest algorithm: SHA-256
        org.bouncycastle.asn1.x509.AlgorithmIdentifier digestAlgId =
            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"),
                org.bouncycastle.asn1.DERNull.INSTANCE);

        // Signature algorithm: SHA256withRSA
        org.bouncycastle.asn1.x509.AlgorithmIdentifier sigAlgId =
            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.1.11"),
                org.bouncycastle.asn1.DERNull.INSTANCE);

        // SignerInfo
        org.bouncycastle.asn1.cms.SignerInfo signerInfo =
            new org.bouncycastle.asn1.cms.SignerInfo(
                signerId,
                digestAlgId,
                null, // signed attributes
                sigAlgId,
                new org.bouncycastle.asn1.DEROctetString(signatureBytes));

        // EncapsulatedContentInfo: id-data (1.2.840.113549.1.7.1)
        org.bouncycastle.asn1.cms.ContentInfo encapContentInfo =
            new org.bouncycastle.asn1.cms.ContentInfo(
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.7.1"),
                null);

        // Certificate
        org.bouncycastle.asn1.x509.Certificate bcCert =
            org.bouncycastle.asn1.x509.Certificate.getInstance(cert.getEncoded());

        // DigestAlgorithms set
        org.bouncycastle.asn1.ASN1EncodableVector digestAlgs =
            new org.bouncycastle.asn1.ASN1EncodableVector();
        digestAlgs.add(digestAlgId);
        org.bouncycastle.asn1.DERSet digestAlgsSet = new org.bouncycastle.asn1.DERSet(digestAlgs);

        // SignedData
        org.bouncycastle.asn1.cms.SignedData signedData =
            new org.bouncycastle.asn1.cms.SignedData(
                digestAlgsSet,
                encapContentInfo,
                new org.bouncycastle.asn1.DERSet(bcCert),
                new org.bouncycastle.asn1.DERSet(signerInfo));

        // ContentInfo wrapping SignedData: id-signedData (1.2.840.113549.1.7.2)
        org.bouncycastle.asn1.cms.ContentInfo contentInfo =
            new org.bouncycastle.asn1.cms.ContentInfo(
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.7.2"),
                signedData);

        return contentInfo.getEncoded("DER");
    }

    /**
     * Write the signed APK with all entries and signature files.
     */
    private static void writeSignedZip(File outputApk, Map<String, byte[]> entries,
                                        byte[] manifestBytes, byte[] sfBytes,
                                        byte[] pkcs7Bytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputApk)))) {

            // First: write META-INF/MANIFEST.MF (must be first)
            ZipEntry mfEntry = new ZipEntry("META-INF/MANIFEST.MF");
            zos.putNextEntry(mfEntry);
            zos.write(manifestBytes);
            zos.closeEntry();

            // Then: write all regular entries (ordered for better compression)
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }

            // Last: write signature files
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

    /**
     * Read all bytes from an InputStream.
     */
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
