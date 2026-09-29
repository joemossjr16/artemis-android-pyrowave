package com.limelight.computers;

import android.content.Context;
import android.util.Base64;

import com.limelight.nvstream.http.ComputerDetails;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable, encrypted backup of paired-host data and the client identity required to reuse pairings. */
public final class PairedComputerBackup {
    private static final String FORMAT = "pyrowave-paired-computers";
    private static final int VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final String CERT_FILE = "client.crt";
    private static final String KEY_FILE = "client.key";
    private static final String UID_FILE = "uniqueid";

    private PairedComputerBackup() {}

    public static byte[] create(Context context, String passphrase) throws Exception {
        return create(context, passphrase, null);
    }

    public static byte[] create(Context context, String passphrase, String computerUuid) throws Exception {
        requirePassphrase(passphrase);
        AndroidIdentity.ensure(context);
        JSONObject payload = new JSONObject();
        payload.put("format", FORMAT);
        payload.put("version", VERSION);
        payload.put("createdAt", System.currentTimeMillis());

        JSONArray computers = new JSONArray();
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        try {
            for (ComputerDetails computer : db.getAllComputers()) {
                if (computerUuid != null && !computerUuid.equals(computer.uuid)) continue;
                JSONObject item = new JSONObject();
                item.put("uuid", computer.uuid);
                item.put("name", computer.name);
                item.put("addresses", addressJson(computer));
                item.put("macAddress", computer.macAddress == null ? JSONObject.NULL : computer.macAddress);
                item.put("serverCert", computer.serverCert == null ? JSONObject.NULL : Base64.encodeToString(computer.serverCert.getEncoded(), Base64.NO_WRAP));
                computers.put(item);
            }
        } finally {
            db.close();
        }
        if (computers.length() == 0) throw new IOException("That PC is no longer in the saved computer list");
        payload.put("computers", computers);

        File files = context.getFilesDir();
        payload.put("clientCertificate", readBase64(new File(files, CERT_FILE)));
        payload.put("clientPrivateKey", readBase64(new File(files, KEY_FILE)));
        payload.put("uniqueId", readText(new File(files, UID_FILE)));

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, passphrase, salt, iv);

        JSONObject envelope = new JSONObject();
        envelope.put("format", FORMAT);
        envelope.put("version", VERSION);
        envelope.put("kdf", "PBKDF2-HMAC-SHA256");
        envelope.put("iterations", ITERATIONS);
        envelope.put("cipher", "AES-256-GCM");
        envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        envelope.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Decrypt and validate without changing app state. */
    public static Preview preview(byte[] backup, String passphrase) throws Exception {
        JSONObject envelope = new JSONObject(new String(backup, StandardCharsets.UTF_8));
        if (!FORMAT.equals(envelope.getString("format")) || envelope.getInt("version") != VERSION ||
                !"PBKDF2-HMAC-SHA256".equals(envelope.getString("kdf")) ||
                !"AES-256-GCM".equals(envelope.getString("cipher"))) {
            throw new IOException("Unsupported paired-computer backup format");
        }
        int iterations = envelope.getInt("iterations");
        if (iterations < 100_000 || iterations > 2_000_000) {
            throw new IOException("Invalid backup encryption parameters");
        }
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, Base64.decode(envelope.getString("data"), Base64.DEFAULT),
                passphrase, Base64.decode(envelope.getString("salt"), Base64.DEFAULT),
                Base64.decode(envelope.getString("iv"), Base64.DEFAULT), iterations);
        JSONObject payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
        if (!FORMAT.equals(payload.getString("format")) || payload.getInt("version") != VERSION) {
            throw new IOException("Invalid paired-computer backup contents");
        }
        JSONArray computers = payload.getJSONArray("computers");
        if (!payload.has("clientCertificate") || !payload.has("clientPrivateKey") || !payload.has("uniqueId")) {
            throw new IOException("Backup does not contain the pairing identity");
        }
        // Parse every record now, before any destructive operation can be offered.
        List<ComputerDetails> parsed = parseComputers(computers);
        byte[] cert = Base64.decode(payload.getString("clientCertificate"), Base64.DEFAULT);
        byte[] key = Base64.decode(payload.getString("clientPrivateKey"), Base64.DEFAULT);
        if (cert.length == 0 || key.length == 0 || payload.getString("uniqueId").length() != 16) {
            throw new IOException("Backup is missing valid client identity data");
        }
        try {
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(cert));
            java.security.PrivateKey privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(key));
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(x509.getPublicKey());
            signature.update(challenge);
            if (!signature.verify(signed)) throw new IOException("Client certificate and key do not match");
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid client identity in backup", e);
        }
        return new Preview(payload, parsed);
    }

    /** Merge restored records by UUID, then restore the identity required for those pairings. */
    public static void restore(Context context, Preview preview) throws Exception {
        restore(context, preview, null);
    }

    public static void restore(Context context, Preview preview, String computerUuid) throws Exception {
        if (preview == null) throw new IOException("No validated backup to restore");
        JSONObject payload = preview.payload;
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        try {
            boolean restored = false;
            for (ComputerDetails computer : preview.computers) {
                if (computerUuid == null || computerUuid.equals(computer.uuid)) {
                    if (!db.updateComputer(computer)) throw new IOException("Could not save the restored PC");
                    restored = true;
                }
            }
            if (!restored) throw new IOException("This backup does not contain the selected PC");
        } finally {
            db.close();
        }

        File files = context.getFilesDir();
        writeAtomic(new File(files, CERT_FILE), Base64.decode(payload.getString("clientCertificate"), Base64.DEFAULT));
        writeAtomic(new File(files, KEY_FILE), Base64.decode(payload.getString("clientPrivateKey"), Base64.DEFAULT));
        writeAtomic(new File(files, UID_FILE), payload.getString("uniqueId").getBytes(StandardCharsets.UTF_8));
    }

    public static final class Preview {
        private final JSONObject payload;
        public final List<ComputerDetails> computers;
        private Preview(JSONObject payload, List<ComputerDetails> computers) {
            this.payload = payload;
            this.computers = computers;
        }
        public int getComputerCount() { return computers.size(); }
        public boolean containsComputer(String uuid) {
            for (ComputerDetails computer : computers) if (computer.uuid.equals(uuid)) return true;
            return false;
        }
    }

    private static JSONObject addressJson(ComputerDetails c) throws JSONException {
        JSONObject addresses = new JSONObject();
        addresses.put("local", tupleJson(c.localAddress));
        addresses.put("remote", tupleJson(c.remoteAddress));
        addresses.put("manual", tupleJson(c.manualAddress));
        addresses.put("ipv6", tupleJson(c.ipv6Address));
        return addresses;
    }

    private static Object tupleJson(ComputerDetails.AddressTuple tuple) throws JSONException {
        if (tuple == null) return JSONObject.NULL;
        JSONObject result = new JSONObject();
        result.put("address", tuple.address);
        result.put("port", tuple.port);
        return result;
    }

    private static List<ComputerDetails> parseComputers(JSONArray json) throws Exception {
        List<ComputerDetails> result = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject item = json.getJSONObject(i);
            ComputerDetails c = new ComputerDetails();
            c.uuid = item.getString("uuid");
            c.name = item.getString("name");
            if (c.uuid.isEmpty() || c.name.isEmpty()) throw new IOException("Invalid computer record");
            JSONObject addresses = item.getJSONObject("addresses");
            c.localAddress = parseTuple(addresses, "local");
            c.remoteAddress = parseTuple(addresses, "remote");
            c.manualAddress = parseTuple(addresses, "manual");
            c.ipv6Address = parseTuple(addresses, "ipv6");
            c.externalPort = c.remoteAddress != null ? c.remoteAddress.port : com.limelight.nvstream.http.NvHTTP.DEFAULT_HTTP_PORT;
            c.macAddress = item.isNull("macAddress") ? null : item.getString("macAddress");
            if (!item.isNull("serverCert")) {
                byte[] certBytes = Base64.decode(item.getString("serverCert"), Base64.DEFAULT);
                c.serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certBytes));
            }
            c.state = ComputerDetails.State.UNKNOWN;
            result.add(c);
        }
        return result;
    }

    private static ComputerDetails.AddressTuple parseTuple(JSONObject addresses, String name) throws JSONException, IOException {
        if (!addresses.has(name) || addresses.isNull(name)) return null;
        JSONObject tuple = addresses.getJSONObject(name);
        String address = tuple.getString("address");
        int port = tuple.getInt("port");
        if (address.isEmpty() || port < 1 || port > 65535) throw new IOException("Invalid host address");
        return new ComputerDetails.AddressTuple(address, port);
    }

    private static byte[] crypt(int mode, byte[] input, String passphrase, byte[] salt, byte[] iv) throws Exception {
        return crypt(mode, input, passphrase, salt, iv, ITERATIONS);
    }

    private static byte[] crypt(int mode, byte[] input, String passphrase, byte[] salt, byte[] iv, int iterations) throws Exception {
        requirePassphrase(passphrase);
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS);
        byte[] keyBytes = null;
        try {
            keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", new org.bouncycastle.jce.provider.BouncyCastleProvider())
                    .generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } finally {
            spec.clearPassword();
            if (keyBytes != null) java.util.Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static void requirePassphrase(String passphrase) throws IOException {
        if (passphrase == null || passphrase.length() < 12) throw new IOException("Use a passphrase with at least 12 characters");
    }

    private static String readBase64(File file) throws IOException {
        return Base64.encodeToString(readBytes(file), Base64.NO_WRAP);
    }

    private static final class AndroidIdentity {
        static void ensure(Context context) throws IOException {
            com.limelight.binding.crypto.AndroidCryptoProvider crypto =
                    new com.limelight.binding.crypto.AndroidCryptoProvider(context);
            if (crypto.getClientCertificate() == null || crypto.getClientPrivateKey() == null) {
                throw new IOException("Could not load the client pairing identity");
            }
            File uid = new File(context.getFilesDir(), UID_FILE);
            if (!uid.exists()) {
                new IdentityManager(context);
            }
        }
    }
    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
    private static String readText(File file) throws IOException {
        return new String(readBytes(file), StandardCharsets.UTF_8).trim();
    }
    private static void writeAtomic(File file, byte[] data) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".restore");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); out.getFD().sync(); }
        if (!temp.renameTo(file)) {
            if (!temp.delete()) throw new IOException("Could not clean up temporary identity file");
            throw new IOException("Could not safely replace " + file.getName());
        }
    }
}
