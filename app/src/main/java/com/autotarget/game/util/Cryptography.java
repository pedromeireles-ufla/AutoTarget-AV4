package com.autotarget.game.util;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Classe responsável pela criptografia e descriptografia de dados usando AES.
 * Atende ao requisito 6.3.2 b) da AV3.
 */
public class Cryptography {
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    // Chave fixa para o projeto acadêmico, conforme permitido pelos requisitos.
    private static final String SECRET_KEY_SEED = "AutoTarget_AV3_2026_Secret_Key";

    private static SecretKeySpec getSecretKey() throws Exception {
        byte[] key = SECRET_KEY_SEED.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16); // Usando 128 bits (16 bytes) para AES
        return new SecretKeySpec(key, ALGORITHM);
    }

    /**
     * Criptografa uma string usando AES.
     * @param data Texto simples a ser criptografado.
     * @return Texto criptografado em formato Base64.
     */
    public static String encrypt(String data) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT);
        } catch (Exception e) {
            EvidenceLogger.registrarEvento("ERRO_CRIPTOGRAFIA", "Falha ao criptografar: " + e.getMessage());
            return null;
        }
    }

    /**
     * Descriptografa uma string Base64 usando AES.
     * @param encryptedData Texto criptografado em Base64.
     * @return Texto original descriptografado.
     */
    public static String decrypt(String encryptedData) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey());
            byte[] decodedBytes = Base64.decode(encryptedData, Base64.DEFAULT);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            EvidenceLogger.registrarEvento("ERRO_CRIPTOGRAFIA", "Falha ao descriptografar: " + e.getMessage());
            return null;
        }
    }
}
