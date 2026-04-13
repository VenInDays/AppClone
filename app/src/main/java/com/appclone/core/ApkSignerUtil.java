package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * APK Signing utility using V1 (JAR) signing scheme.
 * Uses only standard Java APIs - no external BouncyCastle dependency needed.
 * Creates PKCS#7 signature block with manual DER encoding.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";
    private static final String KEY_ALIAS = "appclone_signer";

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

        javax.security.auth.x500.X500Principal principal =
            new javax.security.auth.x500.X500Principal(dn);

        // Use sun.security.x509 or fallback to a simpler approach
        // On standard JDK 17, use the built-in cert generation
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");

        // Generate a self-signed cert using keytool-like approach via sun classes or manual DER
        byte[] certBytes = generateSelfSignedCertDER(keyPair, dn, startDate, endDate);
        ByteArrayInputStream bais = new ByteArrayInputStream(certBytes);
        return (X509Certificate) cf.generateCertificate(bais);
    }

    /**
     * Generate a self-signed X.509 certificate as raw DER bytes.
     * Uses manual ASN.1 DER encoding - works on any JVM.
     */
    private static byte[] generateSelfSignedCertDER(KeyPair keyPair, String dn,
                                                    Date startDate, Date endDate) throws Exception {
        // Sign the TBS certificate to get the signature
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());

        // Build TBSCertificate (to-be-signed)
        byte[] tbsBytes = buildTBSCertificate(keyPair, dn, startDate, endDate);
        sig.update(tbsBytes);
        byte[] signature = sig.sign();

        // Build full certificate: SEQUENCE { tbsCert, sigAlgId, signature }
        ByteArrayOutputStream certOut = new ByteArrayOutputStream();
        writeLengthPrefixed(certOut, 0x30, () -> {
            certOut.write(tbsBytes);
            // signatureAlgorithm: SEQUENCE { OID(sha256WithRSAEncryption), NULL }
            writeLengthPrefixed(certOut, 0x30, () -> {
                writeOID(certOut, new int[]{1, 2, 840, 113549, 1, 1, 11}); // sha256WithRSAEncryption
                certOut.write(0x05, 0x00); // NULL
            });
            // signatureValue: BIT STRING
            certOut.write(0x03);
            byte[] sigWithBit = new byte[signature.length + 1];
            sigWithBit[0] = 0;
            System.arraycopy(signature, 0, sigWithBit, 1, signature.length);
            writeDERLength(certOut, sigWithBit.length);
            certOut.write(sigWithBit);
        });

        return certOut.toByteArray();
    }

    private static byte[] buildTBSCertificate(KeyPair keyPair, String dn,
                                               Date startDate, Date endDate) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // version [0] EXPLICIT INTEGER v3
        out.write(0xA0, 0x03, 0x02, 0x01, 0x02);

        // serialNumber INTEGER
        writeLengthPrefixed(out, 0x02, () -> {
            byte[] serial = BigInteger.valueOf(System.currentTimeMillis()).toByteArray();
            out.write(serial);
        });

        // signature algorithm
        writeLengthPrefixed(out, 0x30, () -> {
            writeOID(out, new int[]{1, 2, 840, 113549, 1, 1, 11});
            out.write(0x05, 0x00); // NULL
        });

        // issuer: Raw DN encoding
        byte[] issuerBytes = encodeDN(dn);
        out.write(issuerBytes);

        // validity: SEQUENCE { notBefore UTCTime, notAfter UTCTime }
        writeLengthPrefixed(out, 0x30, () -> {
            writeUTCTime(out, startDate);
            writeUTCTime(out, endDate);
        });

        // subject: same as issuer for self-signed
        out.write(encodeDN(dn));

        // subjectPublicKeyInfo
        byte[] spkiBytes = keyPair.getPublic().getEncoded(); // X.509 SPKI already in DER
        out.write(spkiBytes);

        return out.toByteArray();
    }

    private static byte[] encodeDN(String dn) {
        // Parse "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN"
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] parts = dn.split(", ");
        // Build SET of SEQUENCE of AttributeTypeAndValue
        ByteArrayOutputStream setOut = new ByteArrayOutputStream();
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            String attrType = kv[0].trim();
            String attrValue = kv[1].trim();
            ByteArrayOutputStream seqOut = new ByteArrayOutputStream();
            writeOID(seqOut, getDN_Oid(attrType));
            writeLengthPrefixed(seqOut, 0x0C, () -> seqOut.write(attrValue.getBytes()));
            byte[] seqBytes = seqOut.toByteArray();
            setOut.write(0x31);
            writeDERLength(setOut, seqBytes.length);
            setOut.write(seqBytes);
        }
        byte[] setBytes = setOut.toByteArray();
        out.write(0x31);
        writeDERLength(out, setBytes.length);
        out.write(setBytes);
        return out.toByteArray();
    }

    private static int[] getDN_Oid(String type) {
        switch (type) {
            case "CN": return new int[]{2, 5, 4, 3};
            case "OU": return new int[]{2, 5, 4, 11};
            case "O": return new int[]{2, 5, 4, 10};
            case "L": return new int[]{2, 5, 4, 7};
            case "ST": return new int[]{2, 5, 4, 8};
            case "C": return new int[]{2, 5, 4, 6};
            default: return new int[]{2, 5, 4, 3};
        }
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
            byte[] digest = md.digest(e.getValue());
            sb.append("Name: ").append(e.getKey()).append("\r\n");
            sb.append("SHA-256-Digest: ").append(Base64.getEncoder().encodeToString(digest)).append("\r\n\r\n");
        }
        return sb.toString().getBytes("UTF-8");
    }

    private static byte[] createSignatureFile(byte[] manifestBytes, Map<String, byte[]> entries)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        sb.append("Signature-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n");
        sb.append("SHA-256-Digest-Manifest: ")
          .append(Base64.getEncoder().encodeToString(md.digest(manifestBytes))).append("\r\n");

        String manifestStr = new String(manifestBytes, "UTF-8");
        int firstSectionEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstSectionEnd > 0) {
            byte[] mainDigest = md.digest(manifestStr.substring(0, firstSectionEnd).getBytes("UTF-8"));
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
              .append(Base64.getEncoder().encodeToString(mainDigest)).append("\r\n");
        }
        sb.append("\r\n");

        for (String entryName : entries.keySet()) {
            String sectionStart = "Name: " + entryName + "\r\n";
            int idx = manifestStr.indexOf(sectionStart);
            if (idx >= 0) {
                int end = manifestStr.indexOf("\r\n\r\n", idx + sectionStart.length());
                if (end > idx) {
                    sb.append("Name: ").append(entryName).append("\r\n");
                    sb.append("SHA-256-Digest: ")
                      .append(Base64.getEncoder().encodeToString(
                          md.digest(manifestStr.substring(idx, end).getBytes("UTF-8")))).append("\r\n\r\n");
                }
            }
        }
        return sb.toString().getBytes("UTF-8");
    }

    /**
     * Create PKCS#7 signature block (CERT.RSA) using manual DER encoding.
     * No BouncyCastle required - pure Java implementation.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();

        // Build PKCS#7 SignedData:
        // ContentInfo { OID(signedData), SignedData { version, digestAlgorithms,
        //   encapContentInfo { id-data }, certificates, signerInfos } }

        ByteArrayOutputStream outerContent = new ByteArrayOutputStream();

        // version INTEGER 1
        outerContent.write(0x02, 0x01, 0x01);

        // digestAlgorithms SET { AlgorithmIdentifier { SHA-256, NULL } }
        ByteArrayOutputStream digestAlgSet = new ByteArrayOutputStream();
        writeLengthPrefixed(digestAlgSet, 0x30, () -> {
            writeOID(digestAlgSet, new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1}); // SHA-256
            digestAlgSet.write(0x05, 0x00); // NULL
        });
        outerContent.write(0x31); // SET
        writeDERLength(outerContent, digestAlgSet.size());
        outerContent.write(digestAlgSet.toByteArray());

        // encapContentInfo SEQUENCE { OID(id-data), [0] EXPLICIT OCTET STRING(sfBytes) }
        ByteArrayOutputStream encapContent = new ByteArrayOutputStream();
        writeOID(encapContent, new int[]{1, 2, 840, 113549, 1, 7, 1}); // id-data
        // [0] EXPLICIT content
        encapContent.write(0xA0);
        ByteArrayOutputStream contentWrap = new ByteArrayOutputStream();
        contentWrap.write(0x04); // OCTET STRING
        writeDERLength(contentWrap, sfBytes.length);
        contentWrap.write(sfBytes);
        writeDERLength(encapContent, contentWrap.size());
        encapContent.write(contentWrap.toByteArray());
        outerContent.write(0x30);
        writeDERLength(outerContent, encapContent.size());
        outerContent.write(encapContent.toByteArray());

        // certificates [0] IMPLICIT EXPLICIT SET OF Certificate
        byte[] certDer = cert.getEncoded(); // X.509 cert already in DER
        outerContent.write(0xA0);
        writeDERLength(outerContent, certDer.length);
        outerContent.write(certDer);

        // signerInfos SET { SignerInfo }
        ByteArrayOutputStream signerInfo = new ByteArrayOutputStream();
        // version INTEGER 1
        signerInfo.write(0x02, 0x01, 0x01);
        // sid: IssuerAndSerialNumber
        writeLengthPrefixed(signerInfo, 0x30, () -> {
            // issuer: GeneralName (certificate issuer)
            signerInfo.write(cert.getIssuerX500Principal().getEncoded());
            // serialNumber
            byte[] serial = cert.getSerialNumber().toByteArray();
            signerInfo.write(0x02);
            writeDERLength(signerInfo, serial.length);
            signerInfo.write(serial);
        });
        // digestAlgorithm
        writeLengthPrefixed(signerInfo, 0x30, () -> {
            writeOID(signerInfo, new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1});
            signerInfo.write(0x05, 0x00);
        });
        // signatureAlgorithm
        writeLengthPrefixed(signerInfo, 0x30, () -> {
            writeOID(signerInfo, new int[]{1, 2, 840, 113549, 1, 1, 11}); // sha256WithRSA
            signerInfo.write(0x05, 0x00);
        });
        // signature OCTET STRING
        signerInfo.write(0x04);
        writeDERLength(signerInfo, signatureBytes.length);
        signerInfo.write(signatureBytes);

        // Wrap signerInfo in SET
        outerContent.write(0x31);
        writeDERLength(outerContent, signerInfo.size());
        outerContent.write(signerInfo.toByteArray());

        // Wrap in SEQUENCE for SignedData
        ByteArrayOutputStream signedData = new ByteArrayOutputStream();
        signedData.write(0x30);
        writeDERLength(signedData, outerContent.size());
        signedData.write(outerContent.toByteArray());

        // Wrap in ContentInfo: SEQUENCE { OID(signedData), [0] EXPLICIT SignedData }
        ByteArrayOutputStream contentInfo = new ByteArrayOutputStream();
        writeOID(contentInfo, new int[]{1, 2, 840, 113549, 1, 7, 2}); // id-signedData
        contentInfo.write(0xA0);
        writeDERLength(contentInfo, signedData.size());
        contentInfo.write(signedData.toByteArray());

        // Wrap in outer SEQUENCE
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0x30);
        writeDERLength(result, contentInfo.size());
        result.write(contentInfo.toByteArray());

        return result.toByteArray();
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
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }

            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".SF"));
            zos.write(sfBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".RSA"));
            zos.write(pkcs7Bytes);
            zos.closeEntry();
        }
    }

    // --- DER encoding helpers ---

    private static void writeOID(ByteArrayOutputStream out, int[] oid) throws IOException {
        ByteArrayOutputStream oidOut = new ByteArrayOutputStream();
        oidOut.write(encodeOIDFirst(oid[0], oid[1]));
        for (int i = 2; i < oid.length; i++) {
            encodeOIDComponent(oidOut, oid[i]);
        }
        byte[] oidBytes = oidOut.toByteArray();
        out.write(0x06);
        writeDERLength(out, oidBytes.length);
        out.write(oidBytes);
    }

    private static int encodeOIDFirst(int a, int b) {
        return a * 40 + b;
    }

    private static void encodeOIDComponent(ByteArrayOutputStream out, int value) {
        if (value < 128) {
            out.write(value);
        } else {
            int numBytes = (32 - Integer.numberOfLeadingZeros(value) + 6) / 7;
            for (int i = numBytes - 1; i >= 0; i--) {
                int shift = i * 7;
                byte b = (byte) ((value >> shift) & 0x7F);
                if (i > 0) b |= 0x80;
                out.write(b);
            }
        }
    }

    private static void writeUTCTime(ByteArrayOutputStream out, Date date) throws IOException {
        String format = date.before(new Date(System.currentTimeMillis() - 2524608000000L))
            ? "yyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'";
        if (date.before(new Date(2050010100000L))) format = "yyMMddHHmmss'Z'";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] timeBytes = sdf.format(date).getBytes();
        out.write(0x17);
        writeDERLength(out, timeBytes.length);
        out.write(timeBytes);
    }

    interface DERWriter { void write(ByteArrayOutputStream out) throws IOException; }

    private static void writeLengthPrefixed(ByteArrayOutputStream out, int tag, DERWriter writer)
            throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writer.write(inner);
        out.write(tag);
        writeDERLength(out, inner.size());
        out.write(inner.toByteArray());
    }

    private static void writeDERLength(ByteArrayOutputStream out, int length) throws IOException {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
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
