package com.github.mostbean.codingswitch.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Base64;
import java.util.List;

public final class WindowsCredentialStore {

    private static final Logger LOG = Logger.getInstance(WindowsCredentialStore.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    private WindowsCredentialStore() {
    }

    public static String readGenericCredentialSnapshot(String targetName) {
        if (!isWindows() || isBlank(targetName)) {
            return null;
        }
        PointerByReference credentialPointer = new PointerByReference();
        if (!Advapi32.INSTANCE.CredRead(new WString(targetName), CRED_TYPE_GENERIC, 0, credentialPointer)) {
            return null;
        }
        Pointer pointer = credentialPointer.getValue();
        try {
            Credential credential = new Credential(pointer);
            byte[] blob = credential.CredentialBlobSize > 0 && credential.CredentialBlob != null
                    ? credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize)
                    : new byte[0];
            return GSON.toJson(new CredentialSnapshot(
                    targetName,
                    credential.UserName == null ? "" : credential.UserName.toString(),
                    credential.Persist,
                    Base64.getEncoder().encodeToString(blob)));
        } finally {
            Advapi32.INSTANCE.CredFree(pointer);
        }
    }

    public static boolean writeGenericCredentialSnapshot(String rawSnapshot) {
        if (!isWindows() || isBlank(rawSnapshot)) {
            return false;
        }
        try {
            CredentialSnapshot snapshot = GSON.fromJson(rawSnapshot, CredentialSnapshot.class);
            if (snapshot == null || isBlank(snapshot.targetName) || snapshot.blobBase64 == null) {
                return false;
            }
            byte[] blob = Base64.getDecoder().decode(snapshot.blobBase64);
            Memory blobMemory = blob.length == 0 ? null : new Memory(blob.length);
            if (blobMemory != null) {
                blobMemory.write(0, blob, 0, blob.length);
            }

            Credential credential = new Credential();
            credential.Flags = 0;
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = new WString(snapshot.targetName);
            credential.Comment = null;
            credential.LastWritten = new FileTime();
            credential.CredentialBlobSize = blob.length;
            credential.CredentialBlob = blobMemory;
            credential.Persist = snapshot.persist > 0 ? snapshot.persist : CRED_PERSIST_LOCAL_MACHINE;
            credential.AttributeCount = 0;
            credential.Attributes = null;
            credential.TargetAlias = null;
            credential.UserName = new WString(isBlank(snapshot.userName) ? snapshot.targetName : snapshot.userName);
            credential.write();
            return Advapi32.INSTANCE.CredWrite(credential, 0);
        } catch (Exception e) {
            LOG.warn("Failed to write Windows credential snapshot", e);
            return false;
        }
    }

    public static void deleteGenericCredential(String targetName) {
        if (!isWindows() || isBlank(targetName)) {
            return;
        }
        Advapi32.INSTANCE.CredDelete(new WString(targetName), CRED_TYPE_GENERIC, 0);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CredRead(WString targetName, int type, int flags, PointerByReference credential);

        boolean CredWrite(Credential credential, int flags);

        boolean CredDelete(WString targetName, int type, int flags);

        void CredFree(Pointer credential);
    }

    public static class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwLowDateTime", "dwHighDateTime");
        }
    }

    public static class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FileTime LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() {
            super();
        }

        public Credential(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of(
                    "Flags",
                    "Type",
                    "TargetName",
                    "Comment",
                    "LastWritten",
                    "CredentialBlobSize",
                    "CredentialBlob",
                    "Persist",
                    "AttributeCount",
                    "Attributes",
                    "TargetAlias",
                    "UserName");
        }
    }

    private static final class CredentialSnapshot {
        private String targetName;
        private String userName;
        private int persist;
        private String blobBase64;

        private CredentialSnapshot(String targetName, String userName, int persist, String blobBase64) {
            this.targetName = targetName;
            this.userName = userName;
            this.persist = persist;
            this.blobBase64 = blobBase64;
        }
    }
}