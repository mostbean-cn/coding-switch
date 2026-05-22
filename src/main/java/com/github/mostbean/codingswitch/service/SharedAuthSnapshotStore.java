package com.github.mostbean.codingswitch.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

public final class SharedAuthSnapshotStore {

    private static final Logger LOG = Logger.getInstance(SharedAuthSnapshotStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_ACCESS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private SharedAuthSnapshotStore() {
    }

    public static void save(String namespace, String snapshotKey, String rawSnapshot) {
        if (isBlank(namespace) || isBlank(snapshotKey) || isBlank(rawSnapshot)) {
            return;
        }
        try {
            byte[] key = readOrCreateKey();
            byte[] iv = randomBytes(IV_BYTES);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(rawSnapshot.trim().getBytes(StandardCharsets.UTF_8));
            writeFile(snapshotPath(namespace, snapshotKey), GSON.toJson(new SnapshotFile(
                    1,
                    CIPHER,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(encrypted))));
        } catch (Exception e) {
            LOG.warn("Failed to save shared auth snapshot: " + namespace, e);
        }
    }

    public static String load(String namespace, String snapshotKey) {
        if (isBlank(namespace) || isBlank(snapshotKey)) {
            return null;
        }
        try {
            Path path = snapshotPath(namespace, snapshotKey);
            if (!Files.exists(path)) {
                return null;
            }
            SnapshotFile file = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SnapshotFile.class);
            if (file == null || file.version != 1 || isBlank(file.iv) || isBlank(file.ciphertext)) {
                return null;
            }
            byte[] key = readOrCreateKey();
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(file.iv)));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(file.ciphertext));
            String raw = new String(decrypted, StandardCharsets.UTF_8).trim();
            return raw.isBlank() ? null : raw;
        } catch (Exception e) {
            LOG.warn("Failed to load shared auth snapshot: " + namespace, e);
            return null;
        }
    }

    public static void delete(String namespace, String snapshotKey) {
        if (isBlank(namespace) || isBlank(snapshotKey)) {
            return;
        }
        try {
            Files.deleteIfExists(snapshotPath(namespace, snapshotKey));
        } catch (IOException e) {
            LOG.warn("Failed to delete shared auth snapshot: " + namespace, e);
        }
    }

    private static Path snapshotRoot() {
        return PluginDataStorage.getUserSharedRootDir().resolve("auth-snapshots");
    }

    private static Path keyPath() {
        return snapshotRoot().resolve(".snapshot-key");
    }

    private static Path snapshotPath(String namespace, String snapshotKey) {
        String fileName = sha256(namespace.trim() + ":" + snapshotKey.trim()) + ".json";
        return snapshotRoot().resolve(safePathPart(namespace)).resolve(fileName);
    }

    private static byte[] readOrCreateKey() throws IOException {
        Path path = keyPath();
        if (Files.exists(path)) {
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            byte[] decoded = Base64.getDecoder().decode(raw);
            if (decoded.length == KEY_BYTES) {
                return decoded;
            }
        }
        byte[] key = randomBytes(KEY_BYTES);
        writeFile(path, Base64.getEncoder().encodeToString(key));
        return key;
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        restrictOwnerDirectoryAccess(path.getParent());
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(
                    tempFile,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            restrictOwnerFileAccess(tempFile);
            Files.move(
                    tempFile,
                    path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            restrictOwnerFileAccess(path);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            restrictOwnerFileAccess(path);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    private static void restrictOwnerFileAccess(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (Exception ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    private static void restrictOwnerDirectoryAccess(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_DIRECTORY_ACCESS);
        } catch (Exception ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setExecutable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
            path.toFile().setExecutable(true, true);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String safePathPart(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class SnapshotFile {
        private int version;
        private String cipher;
        private String iv;
        private String ciphertext;

        private SnapshotFile(int version, String cipher, String iv, String ciphertext) {
            this.version = version;
            this.cipher = cipher;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}