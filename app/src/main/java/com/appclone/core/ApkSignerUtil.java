package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * APK Signing utility using V1 (JAR) signing scheme.
 * Uses BouncyCastle (built into Android 8+) for certificate and PKCS#7 generation.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";

    static {
        try {
            // Ensure BouncyCastle provider is available
            if (Security.getProvider("BC") == null) {
                Security.insertProviderAt(
                    new org.bouncycastle.jce.provider.BouncyCastleProvider(), 2);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to register BC provider", e);
        }
    }

    public static void signApk(File inputApk, File outputApk) throws Exception {
        // Step 1: Generate RSA keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        // Step 2: Generate self-signed X.509 certificate via BouncyCastle
        X509Certificate cert = generateCertificate(keyPair);

        // Step 3: Read all ZIP entries (skip existing META-INF)
        Map<String, byte[]> entries = readZipEntries(inputApk);

        // Step 4: Create MANIFEST.MF with SHA-256 digests
        byte[] manifestBytes = createManifest(entries);

        // Step 5: Create .SF signature file
        byte[] sfBytes = createSignatureFile(manifestBytes, entries);

        // Step 6: Create PKCS#7 (.RSA) signature block via BouncyCastle CMS
        byte[] pkcs7Bytes = createPkcs7Signature(sfBytes, keyPair.getPrivate(), cert);

        // Step 7: Write signed ZIP
        writeSignedZip(outputApk, entries, manifestBytes, sfBytes, pkcs7Bytes);

        android.util.Log.d(TAG, "APK signed successfully: " + outputApk.getName());
    }

    /**
     * Generate a self-signed X.509 certificate using BouncyCastle.
     * This produces a standard, Conscrypt-compatible certificate.
     */
    private static X509Certificate generateCertificate(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - 86400000L); // 1 day ago to avoid clock skew
        Date endDate = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000); // 30 years

        X500Name issuer = new X500Name("CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN");
        BigInteger serial = BigInteger.valueOf(Math.abs(System.currentTimeMillis()));

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            issuer,
            serial,
            startDate,
            endDate,
            issuer,
            keyPair.getPublic()
        );

        // Add basic constraints (CA:true)
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            new BasicConstraints(true)
        );

        // Add key usage: digitalSignature + keyCertSign
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(
                KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign)
        );

        // Sign with SHA256withRSA
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.getPrivate());

        // Build and convert to JCA X509Certificate
        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);
    }

    /**
     * Create PKCS#7 signature block using BouncyCastle CMS API.
     * This produces a standard, Android-compatible .RSA file.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();

        // Create content signer
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .build(privateKey);

        // Add signer info
        gen.addSignerInfoGenerator(
            new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build()
            ).build(signer, cert)
        );

        // Add certificate
        gen.addCertificate(new X509CertificateHolder(cert.getEncoded()));

        // Generate CMS SignedData with encapsulated content
        CMSSignedData signedData = gen.generate(
            new CMSProcessableByteArray(sfBytes),
            true  // encapsulate = include content in signature
        );

        return signedData.getEncoded();
    }

    private static Map<String, byte[]> readZipEntries(File apkFile) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;
                entries.put(entry.getName(), readStream(zf.getInputStream(entry)));
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
            md.reset();
            byte[] digest = md.digest(e.getValue());
            sb.append("Name: ").append(e.getKey()).append("\r\n");
            sb.append("SHA-256-Digest: ")
              .append(Base64.getEncoder().encodeToString(digest)).append("\r\n\r\n");
        }
        return sb.toString().getBytes("UTF-8");
    }

    private static byte[] createSignatureFile(byte[] manifestBytes, Map<String, byte[]> entries)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        sb.append("Signature-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n");

        // Digest of entire manifest
        md.reset();
        sb.append("SHA-256-Digest-Manifest: ")
          .append(Base64.getEncoder().encodeToString(md.digest(manifestBytes)))
          .append("\r\n");

        // Digest of main attributes section
        String manifestStr = new String(manifestBytes, "UTF-8");
        int firstEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstEnd > 0) {
            md.reset();
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
              .append(Base64.getEncoder().encodeToString(
                  md.digest(manifestStr.substring(0, firstEnd).getBytes("UTF-8"))))
              .append("\r\n");
        }
        sb.append("\r\n");

        // Per-entry digests (digest of each section in manifest)
        for (String name : entries.keySet()) {
            String start = "Name: " + name + "\r\n";
            int idx = manifestStr.indexOf(start);
            if (idx >= 0) {
                int end = manifestStr.indexOf("\r\n\r\n", idx + start.length());
                if (end > idx) {
                    md.reset();
                    sb.append("Name: ").append(name).append("\r\n");
                    sb.append("SHA-256-Digest: ").append(
                        Base64.getEncoder().encodeToString(
                            md.digest(manifestStr.substring(idx, end).getBytes("UTF-8"))))
                      .append("\r\n\r\n");
                }
            }
        }
        return sb.toString().getBytes("UTF-8");
    }

    private static void writeSignedZip(File outputApk, Map<String, byte[]> entries,
                                        byte[] manifestBytes, byte[] sfBytes,
                                        byte[] pkcs7Bytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputApk)))) {
            // MANIFEST.MF must come first
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(manifestBytes);
            zos.closeEntry();

            // All APK entries
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }

            // .SF signature file
            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".SF"));
            zos.write(sfBytes);
            zos.closeEntry();

            // .RSA PKCS#7 signature block
            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".RSA"));
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
