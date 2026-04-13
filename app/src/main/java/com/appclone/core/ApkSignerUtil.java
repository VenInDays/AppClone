package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * APK Signing utility using V1 (JAR) signing scheme.
 * Pure Java implementation - no external dependencies.
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

        // Always regenerate keystore to ensure fixed DER-encoded certificate
        // Old keystores contain broken certs from v2.0-v2.1
        if (ksFile.exists()) {
            ksFile.delete();
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000);

        X509Certificate cert = createSelfSignedCertificate(
            keyPair, "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN",
            startDate, endDate);

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
        // Build TBS Certificate content (raw, without outer SEQUENCE wrapper)
        byte[] tbsContent = buildTBSCertificate(keyPair, dn, startDate, endDate);

        // Wrap TBS content in SEQUENCE to get proper DER-encoded TBSCertificate
        byte[] tbsCertDer = wrapTag(0x30, tbsContent);

        // Sign the DER-encoded TBSCertificate (not just the raw content)
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCertDer);
        byte[] signature = sig.sign();

        // Build Certificate: SEQUENCE { tbsCert SEQUENCE, sigAlgId SEQUENCE, signature BIT STRING }
        byte[] certContent = concat(
            tbsCertDer,
            buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11}),
            buildBitString(signature)
        );
        byte[] certDer = wrapTag(0x30, certContent);

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    private static byte[] buildTBSCertificate(KeyPair keyPair, String dn,
                                               Date startDate, Date endDate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // version [0] EXPLICIT INTEGER v3
        out.write(new byte[]{(byte)0xA0, 0x03, 0x02, 0x01, 0x02});
        // serialNumber INTEGER
        out.write(buildInteger(BigInteger.valueOf(System.currentTimeMillis()).toByteArray()));
        // signature algorithm SEQUENCE { OID, NULL }
        out.write(buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11}));
        // issuer
        out.write(encodeDN(dn));
        // validity SEQUENCE { notBefore, notAfter }
        byte[] validity = concat(buildUTCTime(startDate), buildUTCTime(endDate));
        out.write(0x30);
        writeDERLength(out, validity.length);
        out.write(validity);
        // subject
        out.write(encodeDN(dn));
        // subjectPublicKeyInfo (from key)
        out.write(keyPair.getPublic().getEncoded());
        return out.toByteArray();
    }

    private static byte[] encodeDN(String dn) throws IOException {
        String[] parts = dn.split(", ");
        ByteArrayOutputStream setOut = new ByteArrayOutputStream();
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            byte[] oidBytes = encodeOID(getDNOid(kv[0].trim()));
            byte[] valueBytes = kv[1].trim().getBytes();
            // Build UTF8String TLV for the value
            byte[] valueTLV = new byte[1 + getDERLengthSize(valueBytes.length) + valueBytes.length];
            valueTLV[0] = 0x0C; // UTF8String tag
            writeDERLengthTo(valueTLV, 1, valueBytes.length);
            System.arraycopy(valueBytes, 0, valueTLV, 1 + getDERLengthSize(valueBytes.length), valueBytes.length);
            // AttributeTypeAndValue = SEQUENCE { type OID, value UTF8String }
            byte[] atvContent = concat(oidBytes, valueTLV);
            byte[] atvSeq = wrapTag(0x30, atvContent);
            // RelativeDistinguishedName = SET SIZE (1..MAX) OF AttributeTypeAndValue
            byte[] rdnSet = wrapTag(0x31, atvSeq);
            setOut.write(rdnSet);
        }
        // Name = SEQUENCE { RelativeDistinguishedName, ... }
        byte[] setContents = setOut.toByteArray();
        return wrapTag(0x30, setContents);
    }

    private static int[] getDNOid(String type) {
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
        int firstEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstEnd > 0) {
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
              .append(Base64.getEncoder().encodeToString(
                  md.digest(manifestStr.substring(0, firstEnd).getBytes("UTF-8")))).append("\r\n");
        }
        sb.append("\r\n");
        for (String name : entries.keySet()) {
            String start = "Name: " + name + "\r\n";
            int idx = manifestStr.indexOf(start);
            if (idx >= 0) {
                int end = manifestStr.indexOf("\r\n\r\n", idx + start.length());
                if (end > idx) {
                    sb.append("Name: ").append(name).append("\r\n");
                    sb.append("SHA-256-Digest: ").append(
                        Base64.getEncoder().encodeToString(
                            md.digest(manifestStr.substring(idx, end).getBytes("UTF-8")))).append("\r\n\r\n");
                }
            }
        }
        return sb.toString().getBytes("UTF-8");
    }

    /**
     * Create PKCS#7 signature block - pure Java DER encoding.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();

        // --- Build SignedData content ---
        ByteArrayOutputStream sd = new ByteArrayOutputStream();
        // version
        sd.write(new byte[]{0x02, 0x01, 0x01});
        // digestAlgorithms: SET of AlgorithmIdentifier
        byte[] digestAlg = buildAlgIdSeq(new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1});
        byte[] digestAlgSet = wrapTag(0x31, digestAlg);
        sd.write(digestAlgSet);
        // encapContentInfo: SEQUENCE { id-data, [0] EXPLICIT OCTET STRING }
        ByteArrayOutputStream eci = new ByteArrayOutputStream();
        eci.write(encodeOID(new int[]{1, 2, 840, 113549, 1, 7, 1}));
        byte[] contentOctet = buildOctetString(sfBytes);
        byte[] explicit0 = new byte[2 + contentOctet.length];
        explicit0[0] = (byte) 0xA0;
        writeDERLengthTo(explicit0, 1, contentOctet.length);
        System.arraycopy(contentOctet, 0, explicit0, 2, contentOctet.length);
        eci.write(explicit0);
        byte[] eciSeq = wrapTag(0x30, eci.toByteArray());
        sd.write(eciSeq);
        // certificates: [0] EXPLICIT Certificate
        byte[] certDer = cert.getEncoded();
        byte[] certWrap = new byte[2 + certDer.length];
        certWrap[0] = (byte) 0xA0;
        writeDERLengthTo(certWrap, 1, certDer.length);
        System.arraycopy(certDer, 0, certWrap, 2, certDer.length);
        sd.write(certWrap);
        // signerInfos: SET of SignerInfo
        ByteArrayOutputStream si = new ByteArrayOutputStream();
        // si.version
        si.write(new byte[]{0x02, 0x01, 0x01});
        // si.sid: IssuerAndSerialNumber SEQUENCE
        ByteArrayOutputStream isan = new ByteArrayOutputStream();
        byte[] issuerBytes = cert.getIssuerX500Principal().getEncoded();
        isan.write(issuerBytes);
        byte[] serial = cert.getSerialNumber().toByteArray();
        isan.write(0x02);
        writeDERLength(isan, serial.length);
        isan.write(serial);
        byte[] isanSeq = wrapTag(0x30, isan.toByteArray());
        si.write(isanSeq);
        // si.digestAlgorithm
        si.write(buildAlgIdSeq(new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1}));
        // si.signatureAlgorithm
        si.write(buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11}));
        // si.signature OCTET STRING
        si.write(buildOctetString(signatureBytes));
        byte[] signerInfoSet = wrapTag(0x31, si.toByteArray());
        sd.write(signerInfoSet);

        // Wrap SignedData in SEQUENCE
        byte[] signedDataSeq = wrapTag(0x30, sd.toByteArray());

        // ContentInfo: SEQUENCE { id-signedData, [0] EXPLICIT SignedData }
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        ci.write(encodeOID(new int[]{1, 2, 840, 113549, 1, 7, 2}));
        byte[] sdExplicit = new byte[2 + signedDataSeq.length];
        sdExplicit[0] = (byte) 0xA0;
        writeDERLengthTo(sdExplicit, 1, signedDataSeq.length);
        System.arraycopy(signedDataSeq, 0, sdExplicit, 2, signedDataSeq.length);
        ci.write(sdExplicit);

        return wrapTag(0x30, ci.toByteArray());
    }

    private static void writeSignedZip(File outputApk, Map<String, byte[]> entries,
                                        byte[] manifestBytes, byte[] sfBytes,
                                        byte[] pkcs7Bytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputApk)))) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
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

    // --- DER Helpers ---

    private static byte[] wrapTag(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDERLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] buildAlgIdSeq(int[] oid) throws IOException {
        byte[] oidBytes = encodeOID(oid);
        byte[] nullBytes = new byte[]{0x05, 0x00}; // NULL
        byte[] content = concat(oidBytes, nullBytes);
        return wrapTag(0x30, content);
    }

    private static byte[] buildInteger(byte[] value) throws IOException {
        // Ensure positive
        if (value[0] < 0) {
            byte[] padded = new byte[value.length + 1];
            System.arraycopy(value, 0, padded, 1, value.length);
            value = padded;
        }
        return wrapTag(0x02, value);
    }

    private static byte[] buildOctetString(byte[] data) throws IOException {
        return wrapTag(0x04, data);
    }

    private static byte[] buildBitString(byte[] data) throws IOException {
        byte[] padded = new byte[data.length + 1];
        padded[0] = 0; // no unused bits
        System.arraycopy(data, 0, padded, 1, data.length);
        return wrapTag(0x03, padded);
    }

    private static byte[] buildUTCTime(Date date) throws IOException {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] timeBytes = sdf.format(date).getBytes();
        return wrapTag(0x17, timeBytes);
    }

    private static byte[] encodeOID(int[] oid) throws IOException {
        ByteArrayOutputStream oidOut = new ByteArrayOutputStream();
        oidOut.write(encodeOIDFirst(oid[0], oid[1]));
        for (int i = 2; i < oid.length; i++) {
            encodeOIDComponent(oidOut, oid[i]);
        }
        byte[] oidBytes = oidOut.toByteArray();
        return wrapTag(0x06, oidBytes);
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
                int b = (value >> shift) & 0x7F;
                if (i > 0) b |= 0x80;
                out.write(b);
            }
        }
    }

    private static void writeDERLength(ByteArrayOutputStream out, int length) throws IOException {
        byte[] buf = new byte[5];
        int pos = writeDERLengthTo(buf, 0, length);
        out.write(buf, 0, pos);
    }

    private static int writeDERLengthTo(byte[] buf, int pos, int length) {
        if (length < 128) {
            buf[pos] = (byte) length;
            return pos + 1;
        } else if (length < 256) {
            buf[pos] = (byte) 0x81;
            buf[pos + 1] = (byte) length;
            return pos + 2;
        } else if (length < 65536) {
            buf[pos] = (byte) 0x82;
            buf[pos + 1] = (byte) ((length >> 8) & 0xFF);
            buf[pos + 2] = (byte) (length & 0xFF);
            return pos + 3;
        } else {
            buf[pos] = (byte) 0x83;
            buf[pos + 1] = (byte) ((length >> 16) & 0xFF);
            buf[pos + 2] = (byte) ((length >> 8) & 0xFF);
            buf[pos + 3] = (byte) (length & 0xFF);
            return pos + 4;
        }
    }

    private static int getDERLengthSize(int length) {
        if (length < 128) return 1;
        if (length < 256) return 2;
        if (length < 65536) return 3;
        return 4;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
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
