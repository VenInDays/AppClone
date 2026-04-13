package com.appclone.core;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * APK Signing utility supporting both V1 (JAR) and V2 signing schemes.
 * Pure Java implementation - NO external dependencies.
 * Uses streaming to avoid OOM on large APKs.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";

    // V2 constants
    private static final int APK_SIG_V2_BLOCK_ID = 0x7109871a;
    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 0x0103;
    private static final int SIGNATURE_ALGORITHM_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    private static final int V2_CHUNK_SIZE = 1048576; // 1 MB

    // ======================== PUBLIC API ========================

    public static void signApk(File inputApk, File outputApk) throws Exception {
        // Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        // Generate self-signed X.509 certificate (manual DER, fixed encoding)
        long now = System.currentTimeMillis();
        X509Certificate cert = createSelfSignedCertificate(
                keyPair,
                "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN",
                new Date(now - 86400000L),
                new Date(now + 30L * 365 * 24 * 60 * 60 * 1000));

        // Phase 1: V1 signing (streaming, OOM-safe) -> temp file
        File v1File = new File(outputApk.getParentFile(),
                ".v1_" + System.currentTimeMillis() + ".apk");
        try {
            buildV1Signed(inputApk, v1File, keyPair, cert);
            // Phase 2: Add V2 signing block (streaming) -> final output
            addV2SigningBlock(v1File, outputApk, keyPair, cert);
        } finally {
            v1File.delete();
        }

        android.util.Log.d(TAG, "APK signed V1+V2: " + outputApk.getName()
                + " (" + (outputApk.length() / 1024 / 1024) + "MB)");
    }

    // ======================== V1 SIGNING (streaming) ========================

    private static void buildV1Signed(File inputApk, File outputApk,
                                       KeyPair keyPair, X509Certificate cert) throws Exception {
        // Pass 1: Compute SHA-256 digests of all entries (streaming)
        LinkedHashMap<String, byte[]> digestMap = new LinkedHashMap<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (ZipFile zf = new ZipFile(inputApk)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;

                md.reset();
                try (InputStream is = new BufferedInputStream(zf.getInputStream(entry))) {
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        md.update(buf, 0, len);
                    }
                }
                digestMap.put(name, md.digest());
            }
        }

        // Build MANIFEST.MF
        StringBuilder manifest = new StringBuilder();
        manifest.append("Manifest-Version: 1.0\r\n");
        manifest.append("Created-By: 1.0 (AppClone Signer)\r\n\r\n");
        for (Map.Entry<String, byte[]> e : digestMap.entrySet()) {
            manifest.append("Name: ").append(e.getKey()).append("\r\n");
            manifest.append("SHA-256-Digest: ")
                    .append(Base64.getEncoder().encodeToString(e.getValue()))
                    .append("\r\n\r\n");
        }
        byte[] manifestBytes = manifest.toString().getBytes("UTF-8");
        String manifestStr = manifest.toString();

        // Build .SF signature file
        byte[] sfBytes = buildSignatureFile(manifestBytes, manifestStr);

        // Build .RSA PKCS#7 signature block (manual DER)
        byte[] rsaBytes = createPkcs7Signature(sfBytes, keyPair.getPrivate(), cert);

        // Pass 2: Write signed ZIP (streaming)
        try (ZipFile zf = new ZipFile(inputApk);
             ZipOutputStream zos = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(outputApk)))) {

            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(manifestBytes);
            zos.closeEntry();

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;

                zos.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream is = new BufferedInputStream(zf.getInputStream(entry))) {
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }

            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".SF"));
            zos.write(sfBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".RSA"));
            zos.write(rsaBytes);
            zos.closeEntry();
        }
    }

    private static byte[] buildSignatureFile(byte[] manifestBytes, String manifestStr) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        sb.append("Signature-Version: 1.0\r\n");
        sb.append("Created-By: 1.0 (AppClone Signer)\r\n");

        md.reset();
        sb.append("SHA-256-Digest-Manifest: ")
                .append(Base64.getEncoder().encodeToString(md.digest(manifestBytes)))
                .append("\r\n");

        int firstEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstEnd > 0) {
            md.reset();
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
                    .append(Base64.getEncoder().encodeToString(
                            md.digest(manifestStr.substring(0, firstEnd).getBytes("UTF-8"))))
                    .append("\r\n");
        }
        sb.append("\r\n");

        int searchFrom = 0;
        while (searchFrom < manifestStr.length()) {
            int nameStart = manifestStr.indexOf("Name: ", searchFrom);
            if (nameStart < 0) break;
            int sectionEnd = manifestStr.indexOf("\r\n\r\n", nameStart);
            if (sectionEnd < 0) break;
            sectionEnd += 4;

            String section = manifestStr.substring(nameStart, sectionEnd);
            md.reset();
            String digestLine = "SHA-256-Digest: "
                    + Base64.getEncoder().encodeToString(
                    md.digest(section.trim().getBytes("UTF-8")));
            sb.append(section);
            sb.append(digestLine).append("\r\n\r\n");

            searchFrom = sectionEnd;
        }

        return sb.toString().getBytes("UTF-8");
    }

    // ======================== CERTIFICATE GENERATION ========================

    /**
     * Generate self-signed X.509v3 certificate using manual DER encoding.
     * Fixed: encodeDN() wraps each ATV in SEQUENCE before SET (fixes WRONG_TAG).
     */
    private static X509Certificate createSelfSignedCertificate(
            KeyPair keyPair, String dn, Date startDate, Date endDate) throws Exception {
        // Build TBS Certificate
        byte[] tbsContent = buildTBSCertificate(keyPair, dn, startDate, endDate);
        byte[] tbsCertDer = wrapTag(0x30, tbsContent);

        // Sign the TBS certificate
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCertDer);
        byte[] signature = sig.sign();

        // Build Certificate: SEQUENCE { tbsCert, sigAlgId, signature BIT STRING }
        byte[] certContent = concat(
                tbsCertDer,
                buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11}),
                buildBitString(signature));
        byte[] certDer = wrapTag(0x30, certContent);

        // Validate by parsing with CertificateFactory
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certDer));
    }

    private static byte[] buildTBSCertificate(KeyPair keyPair, String dn,
                                               Date startDate, Date endDate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // version [0] EXPLICIT INTEGER v3
        out.write(new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02});
        // serialNumber
        out.write(buildInteger(BigInteger.valueOf(
                Math.abs(System.currentTimeMillis())).toByteArray()));
        // signature algorithm
        out.write(buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11}));
        // issuer (using fixed DN encoding)
        out.write(encodeDN(dn));
        // validity
        byte[] validity = concat(buildUTCTime(startDate), buildUTCTime(endDate));
        out.write(0x30);
        writeDERLength(out, validity.length);
        out.write(validity);
        // subject
        out.write(encodeDN(dn));
        // subjectPublicKeyInfo
        out.write(keyPair.getPublic().getEncoded());
        return out.toByteArray();
    }

    /**
     * Encode X.500 Distinguished Name in DER.
     * Fixed: Each ATV is SEQUENCE { OID, value } then wrapped in SET.
     * Full structure: Name = SEQUENCE { SET { SEQUENCE { OID, UTF8String } } ... }
     */
    private static byte[] encodeDN(String dn) throws IOException {
        String[] parts = dn.split(", ");
        ByteArrayOutputStream setOut = new ByteArrayOutputStream();
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            byte[] oidBytes = encodeOID(getDNOid(kv[0].trim()));
            byte[] valueBytes = kv[1].trim().getBytes();
            // UTF8String TLV for value
            byte[] valueTLV = new byte[1 + getDERLengthSize(valueBytes.length) + valueBytes.length];
            valueTLV[0] = 0x0C; // UTF8String
            writeDERLengthTo(valueTLV, 1, valueBytes.length);
            System.arraycopy(valueBytes, 0, valueTLV,
                    1 + getDERLengthSize(valueBytes.length), valueBytes.length);
            // AttributeTypeAndValue = SEQUENCE { OID, UTF8String }
            byte[] atvContent = concat(oidBytes, valueTLV);
            byte[] atvSeq = wrapTag(0x30, atvContent);
            // RelativeDistinguishedName = SET { ATV }
            byte[] rdnSet = wrapTag(0x31, atvSeq);
            setOut.write(rdnSet);
        }
        // Name = SEQUENCE { RDN, RDN, ... }
        return wrapTag(0x30, setOut.toByteArray());
    }

    private static int[] getDNOid(String type) {
        switch (type) {
            case "CN": return new int[]{2, 5, 4, 3};
            case "OU": return new int[]{2, 5, 4, 11};
            case "O":  return new int[]{2, 5, 4, 10};
            case "L":  return new int[]{2, 5, 4, 7};
            case "ST": return new int[]{2, 5, 4, 8};
            case "C":  return new int[]{2, 5, 4, 6};
            default:  return new int[]{2, 5, 4, 3};
        }
    }

    // ======================== PKCS#7 SIGNATURE ========================

    /**
     * Create PKCS#7 signature block using manual DER encoding.
     * SignedData with encapsulated content, one signer, one certificate.
     */
    private static byte[] createPkcs7Signature(byte[] sfBytes, PrivateKey privateKey,
                                                 X509Certificate cert) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();

        ByteArrayOutputStream sd = new ByteArrayOutputStream();
        // version
        sd.write(new byte[]{0x02, 0x01, 0x01});
        // digestAlgorithms SET
        byte[] digestAlg = buildAlgIdSeq(new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1});
        sd.write(wrapTag(0x31, digestAlg));
        // encapContentInfo
        ByteArrayOutputStream eci = new ByteArrayOutputStream();
        eci.write(encodeOID(new int[]{1, 2, 840, 113549, 1, 7, 1}));
        byte[] contentOctet = buildOctetString(sfBytes);
        byte[] explicit0 = new byte[2 + contentOctet.length];
        explicit0[0] = (byte) 0xA0;
        writeDERLengthTo(explicit0, 1, contentOctet.length);
        System.arraycopy(contentOctet, 0, explicit0, 2, contentOctet.length);
        eci.write(explicit0);
        sd.write(wrapTag(0x30, eci.toByteArray()));
        // certificates [0] EXPLICIT
        byte[] certDer = cert.getEncoded();
        byte[] certWrap = new byte[2 + certDer.length];
        certWrap[0] = (byte) 0xA0;
        writeDERLengthTo(certWrap, 1, certDer.length);
        System.arraycopy(certDer, 0, certWrap, 2, certDer.length);
        sd.write(certWrap);
        // signerInfos SET
        ByteArrayOutputStream si = new ByteArrayOutputStream();
        si.write(new byte[]{0x02, 0x01, 0x01}); // version
        // IssuerAndSerialNumber
        ByteArrayOutputStream isan = new ByteArrayOutputStream();
        isan.write(cert.getIssuerX500Principal().getEncoded());
        byte[] serial = cert.getSerialNumber().toByteArray();
        isan.write(0x02);
        writeDERLength(isan, serial.length);
        isan.write(serial);
        si.write(wrapTag(0x30, isan.toByteArray()));
        si.write(buildAlgIdSeq(new int[]{2, 16, 840, 1, 101, 3, 4, 2, 1})); // digest alg
        si.write(buildAlgIdSeq(new int[]{1, 2, 840, 113549, 1, 1, 11})); // sig alg
        si.write(buildOctetString(signatureBytes));
        sd.write(wrapTag(0x31, si.toByteArray()));

        byte[] signedDataSeq = wrapTag(0x30, sd.toByteArray());
        // ContentInfo
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        ci.write(encodeOID(new int[]{1, 2, 840, 113549, 1, 7, 2}));
        byte[] sdExplicit = new byte[2 + signedDataSeq.length];
        sdExplicit[0] = (byte) 0xA0;
        writeDERLengthTo(sdExplicit, 1, signedDataSeq.length);
        System.arraycopy(signedDataSeq, 0, sdExplicit, 2, signedDataSeq.length);
        ci.write(sdExplicit);
        return wrapTag(0x30, ci.toByteArray());
    }

    // ======================== V2 SIGNING ========================

    private static void addV2SigningBlock(File v1File, File outputFile,
                                           KeyPair keyPair, X509Certificate cert) throws Exception {
        long fileLen = v1File.length();
        int eocdOffset = findEOCD(v1File);
        int eocdSize = (int) (fileLen - eocdOffset);

        byte[] eocdBuf = new byte[eocdSize];
        try (RandomAccessFile raf = new RandomAccessFile(v1File, "r")) {
            raf.seek(eocdOffset);
            raf.readFully(eocdBuf);
        }
        int cdSize = readUInt32LE(eocdBuf, 12);
        int cdOffset = readUInt32LE(eocdBuf, 16);

        // Compute V2 digests (streaming)
        byte[] digest0 = computeChunkedDigest(v1File, 0, cdOffset);
        byte[] digest1 = computeChunkedDigest(v1File, cdOffset, cdSize);
        byte[] eocdZeroed = eocdBuf.clone();
        eocdZeroed[16] = 0; eocdZeroed[17] = 0;
        eocdZeroed[18] = 0; eocdZeroed[19] = 0;
        byte[] digest2 = computeChunkedDigest(eocdZeroed, 0, eocdZeroed.length);

        // Build signed data and sign it
        byte[] signedDataBytes = buildV2SignedData(digest0, digest1, digest2, cert);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(signedDataBytes);
        byte[] signatureBytes = sig.sign();

        // Build V2 signing block
        byte[] signingBlock = buildV2SigningBlock(signedDataBytes, signatureBytes,
                keyPair.getPublic().getEncoded());

        // Write final APK
        int newCdOffset = cdOffset + signingBlock.length;
        writeFinalApk(v1File, outputFile, cdOffset, cdSize, eocdBuf,
                signingBlock, newCdOffset);
    }

    private static byte[] buildV2SignedData(byte[] d0, byte[] d1, byte[] d2,
                                              X509Certificate cert) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Digests
        ByteArrayOutputStream digests = new ByteArrayOutputStream();
        for (byte[] d : new byte[][]{d0, d1, d2}) {
            writeUInt32BE(digests, CONTENT_DIGEST_CHUNKED_SHA256);
            writeUInt32BE(digests, d.length);
            digests.write(d);
        }
        byte[] digestsBytes = digests.toByteArray();
        writeUInt32BE(out, digestsBytes.length);
        out.write(digestsBytes);
        // Certificates
        byte[] certDer = cert.getEncoded();
        ByteArrayOutputStream certs = new ByteArrayOutputStream();
        writeUInt32BE(certs, certDer.length);
        certs.write(certDer);
        byte[] certsBytes = certs.toByteArray();
        writeUInt32BE(out, certsBytes.length);
        out.write(certsBytes);
        // Additional attributes (empty)
        writeUInt32BE(out, 0);
        return out.toByteArray();
    }

    private static byte[] buildV2SigningBlock(byte[] signedData, byte[] signature,
                                               byte[] publicKey) throws Exception {
        // Signer = signedData || signatures || publicKeyKey
        ByteArrayOutputStream signer = new ByteArrayOutputStream();
        // Signatures
        ByteArrayOutputStream sigs = new ByteArrayOutputStream();
        writeUInt32BE(sigs, SIGNATURE_ALGORITHM_RSA_PKCS1_V1_5_WITH_SHA256);
        writeUInt32BE(sigs, signature.length);
        sigs.write(signature);
        byte[] sigsBytes = sigs.toByteArray();
        writeUInt32BE(signer, sigsBytes.length);
        signer.write(sigsBytes);
        // Public key
        writeUInt32BE(signer, publicKey.length);
        signer.write(publicKey);
        byte[] signerFull = concat(signedData, signer.toByteArray());

        // Signers (length-prefixed)
        ByteArrayOutputStream signersOut = new ByteArrayOutputStream();
        writeUInt32BE(signersOut, signerFull.length);
        signersOut.write(signerFull);
        byte[] signersBytes = signersOut.toByteArray();

        // ID-value pair
        ByteArrayOutputStream pair = new ByteArrayOutputStream();
        writeUInt64LE(pair, 8L + 4L + signersBytes.length);
        writeUInt32LE(pair, APK_SIG_V2_BLOCK_ID);
        pair.write(signersBytes);
        byte[] pairBytes = pair.toByteArray();

        // Signing block: size | pairs | size
        long blockSize = 8L + pairBytes.length + 8L;
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeUInt64LE(block, blockSize);
        block.write(pairBytes);
        writeUInt64LE(block, blockSize);
        return block.toByteArray();
    }

    private static void writeFinalApk(File input, File output,
                                       int cdOffset, int cdSize,
                                       byte[] eocdOriginal, byte[] signingBlock,
                                       int newCdOffset) throws Exception {
        byte[] buf = new byte[65536];
        try (RandomAccessFile in = new RandomAccessFile(input, "r");
             BufferedOutputStream out = new BufferedOutputStream(
                     new FileOutputStream(output))) {
            // Entries
            in.seek(0);
            long rem = cdOffset;
            while (rem > 0) {
                int n = (int) Math.min(buf.length, rem);
                in.readFully(buf, 0, n);
                out.write(buf, 0, n);
                rem -= n;
            }
            // V2 block
            out.write(signingBlock);
            // CD
            in.seek(cdOffset);
            rem = cdSize;
            while (rem > 0) {
                int n = (int) Math.min(buf.length, rem);
                in.readFully(buf, 0, n);
                out.write(buf, 0, n);
                rem -= n;
            }
            // EOCD with updated offset
            byte[] eocdOut = eocdOriginal.clone();
            eocdOut[16] = (byte) (newCdOffset & 0xFF);
            eocdOut[17] = (byte) ((newCdOffset >> 8) & 0xFF);
            eocdOut[18] = (byte) ((newCdOffset >> 16) & 0xFF);
            eocdOut[19] = (byte) ((newCdOffset >> 24) & 0xFF);
            out.write(eocdOut);
        }
    }

    // ======================== HELPERS ========================

    private static int findEOCD(File file) throws IOException {
        int searchSize = Math.min((int) file.length(), 65535 + 22);
        byte[] buf = new byte[searchSize];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(file.length() - searchSize);
            raf.readFully(buf);
        }
        for (int i = buf.length - 22; i >= 0; i--) {
            if (buf[i] == 0x50 && buf[i + 1] == 0x4b &&
                    buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
                return (int) (file.length() - searchSize + i);
            }
        }
        throw new IOException("EOCD not found");
    }

    private static byte[] computeChunkedDigest(File file, long offset, int length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[V2_CHUNK_SIZE];
        byte[] lp = new byte[4];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int rem = length;
            long pos = offset;
            while (rem > 0) {
                int chunk = Math.min(V2_CHUNK_SIZE, rem);
                raf.seek(pos);
                raf.readFully(buf, 0, chunk);
                lp[0] = (byte) ((chunk >> 24) & 0xFF);
                lp[1] = (byte) ((chunk >> 16) & 0xFF);
                lp[2] = (byte) ((chunk >> 8) & 0xFF);
                lp[3] = (byte) (chunk & 0xFF);
                md.update(lp);
                md.update(buf, 0, chunk);
                pos += chunk;
                rem -= chunk;
            }
        }
        return md.digest();
    }

    private static byte[] computeChunkedDigest(byte[] data, int offset, int length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] lp = new byte[4];
        int rem = length, pos = offset;
        while (rem > 0) {
            int chunk = Math.min(V2_CHUNK_SIZE, rem);
            lp[0] = (byte) ((chunk >> 24) & 0xFF);
            lp[1] = (byte) ((chunk >> 16) & 0xFF);
            lp[2] = (byte) ((chunk >> 8) & 0xFF);
            lp[3] = (byte) (chunk & 0xFF);
            md.update(lp);
            md.update(data, pos, chunk);
            pos += chunk;
            rem -= chunk;
        }
        return md.digest();
    }

    // --- DER encoding helpers ---

    private static byte[] wrapTag(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDERLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] buildAlgIdSeq(int[] oid) throws IOException {
        return wrapTag(0x30, concat(encodeOID(oid), new byte[]{0x05, 0x00}));
    }

    private static byte[] buildInteger(byte[] value) throws IOException {
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
        System.arraycopy(data, 0, padded, 1, data.length);
        return wrapTag(0x03, padded);
    }

    private static byte[] buildUTCTime(Date date) throws IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return wrapTag(0x17, sdf.format(date).getBytes());
    }

    private static byte[] encodeOID(int[] oid) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(oid[0] * 40 + oid[1]);
        for (int i = 2; i < oid.length; i++) {
            int v = oid[i];
            if (v < 128) {
                out.write(v);
            } else {
                int numBytes = (32 - Integer.numberOfLeadingZeros(v) + 6) / 7;
                for (int j = numBytes - 1; j >= 0; j--) {
                    int b = (v >> (j * 7)) & 0x7F;
                    if (j > 0) b |= 0x80;
                    out.write(b);
                }
            }
        }
        return wrapTag(0x06, out.toByteArray());
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

    // --- Byte order helpers ---

    private static void writeUInt32BE(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeUInt32BE(byte[] buf, int off, int v) {
        buf[off] = (byte) ((v >> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeUInt64LE(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++)
            out.write((int) ((v >> (8 * i)) & 0xFF));
    }

    private static int readUInt32LE(byte[] buf, int off) {
        return buf[off] & 0xFF | (buf[off + 1] & 0xFF) << 8
                | (buf[off + 2] & 0xFF) << 16 | (buf[off + 3] & 0xFF) << 24;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] r = new byte[total];
        int p = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, r, p, a.length);
            p += a.length;
        }
        return r;
    }
}
