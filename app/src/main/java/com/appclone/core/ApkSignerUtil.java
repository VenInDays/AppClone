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
 * APK Signing utility supporting both V1 (JAR) and V2 signing schemes.
 * Uses streaming to avoid OOM on large APKs.
 * Uses BouncyCastle for certificate generation and V1 PKCS#7 signing.
 * Implements APK Signature Scheme V2 natively for maximum compatibility.
 */
public class ApkSignerUtil {

    private static final String TAG = "ApkSignerUtil";
    private static final String SIGNER_NAME = "CERT";

    // V2 constants
    private static final int APK_SIG_V2_BLOCK_ID = 0x7109871a;
    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 0x0103;
    private static final int SIGNATURE_ALGORITHM_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    private static final int V2_CHUNK_SIZE = 1048576; // 1 MB

    static {
        try {
            if (Security.getProvider("BC") == null) {
                Security.insertProviderAt(
                    new org.bouncycastle.jce.provider.BouncyCastleProvider(), 2);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to register BC", e);
        }
    }

    // ======================== PUBLIC API ========================

    public static void signApk(File inputApk, File outputApk) throws Exception {
        // Generate RSA key pair and self-signed certificate
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        X509Certificate cert = generateCertificate(keyPair);

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

    /**
     * Build V1 (JAR) signed APK using two-pass streaming.
     * Pass 1: Compute SHA-256 digests of all entries (16KB buffer, no full load).
     * Pass 2: Stream-copy entries + write META-INF signature files.
     */
    private static void buildV1Signed(File inputApk, File outputApk,
                                       KeyPair keyPair, X509Certificate cert) throws Exception {
        // Pass 1: Compute entry digests (streaming)
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

        // Build MANIFEST.MF from pre-computed digests
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

        // Build .RSA PKCS#7 signature block (BouncyCastle CMS)
        byte[] rsaBytes = buildPKCS7(sfBytes, keyPair.getPrivate(), cert);

        // Pass 2: Write signed ZIP (streaming)
        try (ZipFile zf = new ZipFile(inputApk);
             ZipOutputStream zos = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(outputApk)))) {

            // MANIFEST.MF must come first
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(manifestBytes);
            zos.closeEntry();

            // Stream-copy all non-META-INF entries
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

            // .SF
            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".SF"));
            zos.write(sfBytes);
            zos.closeEntry();

            // .RSA
            zos.putNextEntry(new ZipEntry("META-INF/" + SIGNER_NAME + ".RSA"));
            zos.write(rsaBytes);
            zos.closeEntry();
        }
    }

    /**
     * Build .SF (Signature File) from MANIFEST.MF.
     * Contains digests of the entire manifest and per-entry sections.
     */
    private static byte[] buildSignatureFile(byte[] manifestBytes, String manifestStr) throws Exception {
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
        int firstEnd = manifestStr.indexOf("\r\n\r\n");
        if (firstEnd > 0) {
            md.reset();
            sb.append("SHA-256-Digest-Manifest-Main-Attributes: ")
                    .append(Base64.getEncoder().encodeToString(
                            md.digest(manifestStr.substring(0, firstEnd).getBytes("UTF-8"))))
                    .append("\r\n");
        }
        sb.append("\r\n");

        // Per-entry section digests
        int searchFrom = 0;
        while (searchFrom < manifestStr.length()) {
            int nameStart = manifestStr.indexOf("Name: ", searchFrom);
            if (nameStart < 0) break;

            int sectionEnd = manifestStr.indexOf("\r\n\r\n", nameStart);
            if (sectionEnd < 0) break;
            sectionEnd += 4; // include the \r\n\r\n

            String section = manifestStr.substring(nameStart, sectionEnd);
            md.reset();
            sb.append(section);
            // Replace/add digest line
            String digestLine = "SHA-256-Digest: "
                    + Base64.getEncoder().encodeToString(
                    md.digest(section.trim().getBytes("UTF-8")));
            sb.append(digestLine).append("\r\n\r\n");

            searchFrom = sectionEnd;
        }

        return sb.toString().getBytes("UTF-8");
    }

    /**
     * Build PKCS#7 (.RSA) signature block using BouncyCastle CMS API.
     */
    private static byte[] buildPKCS7(byte[] sfBytes, PrivateKey privateKey,
                                      X509Certificate cert) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(privateKey);

        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().build()
                ).build(signer, cert));

        gen.addCertificate(new X509CertificateHolder(cert.getEncoded()));

        CMSSignedData signedData = gen.generate(
                new CMSProcessableByteArray(sfBytes), true);

        return signedData.getEncoded();
    }

    // ======================== V2 SIGNING ========================

    /**
     * Add APK Signature Scheme V2 block to the V1-signed APK.
     * The V2 block is inserted between the ZIP entries and the Central Directory.
     * Uses streaming to avoid OOM on large APKs.
     */
    private static void addV2SigningBlock(File v1File, File outputFile,
                                           KeyPair keyPair, X509Certificate cert) throws Exception {
        long fileLen = v1File.length();

        // Step 1: Find EOCD (End of Central Directory)
        int eocdOffset = findEOCD(v1File);
        int eocdSize = (int) (fileLen - eocdOffset);

        // Step 2: Read EOCD header to get CD offset and CD size
        int cdOffset, cdSize;
        byte[] eocdBuf = new byte[eocdSize];
        try (RandomAccessFile raf = new RandomAccessFile(v1File, "r")) {
            raf.seek(eocdOffset);
            raf.readFully(eocdBuf);
        }
        cdSize = readUInt32LE(eocdBuf, 12);
        cdOffset = readUInt32LE(eocdBuf, 16);

        // Step 3: Compute V2 content digests (streaming, chunked SHA-256)
        // Section 0: ZIP entries (offset 0 to cdOffset)
        byte[] digest0 = computeChunkedDigest(v1File, 0, cdOffset);
        // Section 1: Central Directory
        byte[] digest1 = computeChunkedDigest(v1File, cdOffset, cdSize);
        // Section 2: EOCD with zeroed CD offset field
        byte[] eocdZeroed = eocdBuf.clone();
        eocdZeroed[16] = 0; eocdZeroed[17] = 0;
        eocdZeroed[18] = 0; eocdZeroed[19] = 0;
        byte[] digest2 = computeChunkedDigest(eocdZeroed, 0, eocdZeroed.length);

        // Step 4: Build signed data
        byte[] signedDataBytes = buildV2SignedData(digest0, digest1, digest2, cert);

        // Step 5: Sign the signed data
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(signedDataBytes);
        byte[] signatureBytes = sig.sign();

        // Step 6: Build APK Signing Block
        byte[] signingBlock = buildV2SigningBlock(signedDataBytes, signatureBytes,
                keyPair.getPublic().getEncoded());

        // Step 7: Write final APK: entries | signing_block | CD | EOCD (updated offset)
        int newCdOffset = cdOffset + signingBlock.length;
        writeFinalApk(v1File, outputFile, cdOffset, cdSize, eocdBuf,
                signingBlock, newCdOffset);
    }

    /**
     * Build V2 signed data structure (what gets signed).
     * Format: digests || certificates || additional_attributes
     * Each section is length-prefixed (uint32 BE).
     */
    private static byte[] buildV2SignedData(byte[] digest0, byte[] digest1,
                                              byte[] digest2, X509Certificate cert) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // --- Digests (length-prefixed) ---
        ByteArrayOutputStream digests = new ByteArrayOutputStream();
        // Section 0 digest
        writeUInt32BE(digests, CONTENT_DIGEST_CHUNKED_SHA256);
        writeUInt32BE(digests, digest0.length);
        digests.write(digest0);
        // Section 1 digest
        writeUInt32BE(digests, CONTENT_DIGEST_CHUNKED_SHA256);
        writeUInt32BE(digests, digest1.length);
        digests.write(digest1);
        // Section 2 digest
        writeUInt32BE(digests, CONTENT_DIGEST_CHUNKED_SHA256);
        writeUInt32BE(digests, digest2.length);
        digests.write(digest2);
        byte[] digestsBytes = digests.toByteArray();
        writeUInt32BE(out, digestsBytes.length);
        out.write(digestsBytes);

        // --- Certificates (length-prefixed) ---
        byte[] certDer = cert.getEncoded();
        ByteArrayOutputStream certs = new ByteArrayOutputStream();
        writeUInt32BE(certs, certDer.length);
        certs.write(certDer);
        byte[] certsBytes = certs.toByteArray();
        writeUInt32BE(out, certsBytes.length);
        out.write(certsBytes);

        // --- Additional attributes (empty) ---
        writeUInt32BE(out, 0);

        return out.toByteArray();
    }

    /**
     * Build the full APK Signing Block.
     * Format: uint64 size | ID-value pairs | uint64 size
     */
    private static byte[] buildV2SigningBlock(byte[] signedData, byte[] signature,
                                               byte[] publicKey) throws Exception {
        // --- Build signer ---
        ByteArrayOutputStream signer = new ByteArrayOutputStream();

        // Signatures (length-prefixed)
        ByteArrayOutputStream sigs = new ByteArrayOutputStream();
        writeUInt32BE(sigs, SIGNATURE_ALGORITHM_RSA_PKCS1_V1_5_WITH_SHA256);
        writeUInt32BE(sigs, signature.length);
        sigs.write(signature);
        byte[] sigsBytes = sigs.toByteArray();
        writeUInt32BE(signer, sigsBytes.length);
        signer.write(sigsBytes);

        // Public key (length-prefixed)
        writeUInt32BE(signer, publicKey.length);
        signer.write(publicKey);

        // Prepend signed data to signer
        ByteArrayOutputStream signerFull = new ByteArrayOutputStream();
        signerFull.write(signedData);
        signerFull.write(signer.toByteArray());
        byte[] signerBytes = signerFull.toByteArray();

        // --- Build signers sequence (length-prefixed) ---
        ByteArrayOutputStream signers = new ByteArrayOutputStream();
        writeUInt32BE(signers, signerBytes.length);
        signers.write(signerBytes);
        byte[] signersBytes = signers.toByteArray();

        // --- Build ID-value pair ---
        ByteArrayOutputStream pair = new ByteArrayOutputStream();
        // Pair value = signers (uint32 BE length + signers data)
        pair.write(signersBytes);
        byte[] pairValue = pair.toByteArray();

        // Pair header: uint64 LE (pair length = 4 + value length)
        long pairTotalLen = 4L + pairValue.length;
        long pairWithHeaderLen = 8L + pairTotalLen;
        ByteArrayOutputStream pairFull = new ByteArrayOutputStream();
        writeUInt64LE(pairFull, pairWithHeaderLen);
        writeUInt32LE(pairFull, APK_SIG_V2_BLOCK_ID);
        pairFull.write(pairValue);
        byte[] pairBytes = pairFull.toByteArray();

        // --- Build signing block: uint64 size | pairs | uint64 size ---
        long blockSize = 8L + pairBytes.length + 8L;
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeUInt64LE(block, blockSize);
        block.write(pairBytes);
        writeUInt64LE(block, blockSize);

        return block.toByteArray();
    }

    /**
     * Write the final APK: entries | V2 signing block | CD | EOCD with updated offset.
     * All streaming with 64KB buffer.
     */
    private static void writeFinalApk(File input, File output,
                                       int cdOffset, int cdSize,
                                       byte[] eocdOriginal, byte[] signingBlock,
                                       int newCdOffset) throws Exception {
        byte[] buf = new byte[65536];

        try (RandomAccessFile in = new RandomAccessFile(input, "r");
             BufferedOutputStream out = new BufferedOutputStream(
                     new FileOutputStream(output))) {

            // 1. Copy ZIP entries (offset 0 to cdOffset)
            in.seek(0);
            long remaining = cdOffset;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                in.readFully(buf, 0, toRead);
                out.write(buf, 0, toRead);
                remaining -= toRead;
            }

            // 2. Write V2 signing block
            out.write(signingBlock);

            // 3. Copy Central Directory
            in.seek(cdOffset);
            remaining = cdSize;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                in.readFully(buf, 0, toRead);
                out.write(buf, 0, toRead);
                remaining -= toRead;
            }

            // 4. Write EOCD with updated CD offset
            byte[] eocdOut = eocdOriginal.clone();
            eocdOut[16] = (byte) (newCdOffset & 0xFF);
            eocdOut[17] = (byte) ((newCdOffset >> 8) & 0xFF);
            eocdOut[18] = (byte) ((newCdOffset >> 16) & 0xFF);
            eocdOut[19] = (byte) ((newCdOffset >> 24) & 0xFF);
            out.write(eocdOut);
        }
    }

    // ======================== DIGEST HELPERS ========================

    /**
     * Compute chunked SHA-256 digest of a region of a file.
     * Reads in 1MB chunks, each prefixed with 4-byte BE length.
     * Uses streaming - never loads the entire region into memory.
     */
    private static byte[] computeChunkedDigest(File file, long offset, int length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[V2_CHUNK_SIZE];
        byte[] lenPrefix = new byte[4];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int remaining = length;
            long pos = offset;
            while (remaining > 0) {
                int chunkSize = Math.min(V2_CHUNK_SIZE, remaining);
                raf.seek(pos);
                raf.readFully(buf, 0, chunkSize);

                // 4-byte BE length prefix
                lenPrefix[0] = (byte) ((chunkSize >> 24) & 0xFF);
                lenPrefix[1] = (byte) ((chunkSize >> 16) & 0xFF);
                lenPrefix[2] = (byte) ((chunkSize >> 8) & 0xFF);
                lenPrefix[3] = (byte) (chunkSize & 0xFF);
                md.update(lenPrefix);
                md.update(buf, 0, chunkSize);

                pos += chunkSize;
                remaining -= chunkSize;
            }
        }

        return md.digest();
    }

    /**
     * Compute chunked SHA-256 digest of a byte array.
     * Used for the small EOCD section.
     */
    private static byte[] computeChunkedDigest(byte[] data, int offset, int length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] lenPrefix = new byte[4];
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int chunkSize = Math.min(V2_CHUNK_SIZE, remaining);
            lenPrefix[0] = (byte) ((chunkSize >> 24) & 0xFF);
            lenPrefix[1] = (byte) ((chunkSize >> 16) & 0xFF);
            lenPrefix[2] = (byte) ((chunkSize >> 8) & 0xFF);
            lenPrefix[3] = (byte) (chunkSize & 0xFF);
            md.update(lenPrefix);
            md.update(data, pos, chunkSize);
            pos += chunkSize;
            remaining -= chunkSize;
        }
        return md.digest();
    }

    // ======================== EOCD HELPERS ========================

    /**
     * Find EOCD (End of Central Directory) by scanning backwards for the signature.
     * EOCD signature: 0x06054b50 (little-endian: 50 4b 05 06)
     */
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
        throw new IOException("EOCD not found in APK");
    }

    // ======================== BYTE ORDER HELPERS ========================

    private static void writeUInt32BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeUInt32BE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeUInt64LE(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 32) & 0xFF));
        out.write((int) ((value >> 40) & 0xFF));
        out.write((int) ((value >> 48) & 0xFF));
        out.write((int) ((value >> 56) & 0xFF));
    }

    private static int readUInt32LE(byte[] buf, int offset) {
        return buf[offset] & 0xFF
                | (buf[offset + 1] & 0xFF) << 8
                | (buf[offset + 2] & 0xFF) << 16
                | (buf[offset + 3] & 0xFF) << 24;
    }

    // ======================== CERTIFICATE GENERATION ========================

    /**
     * Generate a self-signed X.509v3 certificate using BouncyCastle.
     * Standard Conscrypt-compatible certificate for Android.
     */
    private static X509Certificate generateCertificate(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - 86400000L);
        Date endDate = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000);

        X500Name issuer = new X500Name(
                "CN=AppClone, OU=Dev, O=AppClone, L=HCM, ST=HC, C=VN");
        BigInteger serial = BigInteger.valueOf(Math.abs(System.currentTimeMillis()));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, startDate, endDate, issuer, keyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign
                        | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(holder);
    }
}
