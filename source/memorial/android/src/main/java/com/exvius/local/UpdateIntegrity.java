package com.exvius.local;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

final class UpdateIntegrity {
    private UpdateIntegrity() {}

    static boolean matchesSha256(byte[] payload, String expected) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return expected.contentEquals(hex);
    }

    static boolean verify(byte[] payload, byte[] signed, byte[] publicKey) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(payload);
        return verifier.verify(signed);
    }
}
