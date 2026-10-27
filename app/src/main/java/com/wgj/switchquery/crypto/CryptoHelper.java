package com.wgj.switchquery.crypto;

import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES 加密工具，用于加密存储交换机用户名、密码等敏感信息。
 * 密钥由固定种子经过 SHA-256 派生，防止直接明文拆包获取。
 */
public class CryptoHelper {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS7Padding";
    private static final int IV_LENGTH = 16;

    // 固定种子（混淆后用于派生密钥，避免明文密钥直接出现在反编译结果中）
    private static final String SEED_PART_1 = "wgj_switch_query_";
    private static final String SEED_PART_2 = "secure_vault_2024";
    private static final String SEED_PART_3 = "_app_credential";

    private static SecretKeySpec keySpec;
    private static final Object LOCK = new Object();

    private static SecretKeySpec getKey() {
        if (keySpec == null) {
            synchronized (LOCK) {
                if (keySpec == null) {
                    try {
                        String seed = SEED_PART_1 + SEED_PART_2 + SEED_PART_3;
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] keyBytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
                        keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to derive encryption key", e);
                    }
                }
            }
        }
        return keySpec;
    }

    /**
     * 加密字符串，返回 Base64 编码的密文（包含 IV）。
     */
    public static String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), ivSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 将 IV + 密文合并后 Base64 编码
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * 解密 Base64 编码的密文。
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null) return null;
        try {
            byte[] combined = Base64.decode(cipherText, Base64.NO_WRAP);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), ivSpec);
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
