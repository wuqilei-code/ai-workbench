package com.aiworkbench.common;

import com.aiworkbench.config.CryptoProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 对称加解密(provider apiKey 落库加密)。
 * <p>
 * 密文格式:{@code enc:<base64(iv(12B) || ciphertext+tag)>}。前缀用于区分明文/密文,
 * {@link #decrypt} 遇无前缀串原样返回,从而向后兼容历史明文数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoService {

    private static final String PREFIX = "enc:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final CryptoProperties props;
    private SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void init() {
        keySpec = new SecretKeySpec(decodeKey(), "AES");
    }

    private byte[] decodeKey() {
        String k = props.getAesKey();
        if (k == null || k.isBlank()) {
            log.warn("app.crypto.aes-key 未配置,使用内置 dev key(严禁生产使用)");
            return "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(k);
            if (decoded.length == 32) return decoded;
            log.warn("aes-key 解码后非 32 字节(实际 {}),补/截到 32", decoded.length);
            return padTo32(decoded);
        } catch (IllegalArgumentException e) {
            log.warn("aes-key 非 Base64,按 UTF-8 取前 32 字节");
            return padTo32(k.getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] padTo32(byte[] src) {
        byte[] fixed = new byte[32];
        System.arraycopy(src, 0, fixed, 0, Math.min(src.length, 32));
        return fixed;
    }

    /** 加密;null/空原样返回;已加密(带前缀)原样返回 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (plain.startsWith(PREFIX)) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("encrypt failed", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "加密失败");
        }
    }

    /** 解密;null/空/无 enc: 前缀原样返回(兼容明文);解密失败返回原值并告警 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            byte[] cipherText = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            System.arraycopy(combined, IV_LEN, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("decrypt failed, return raw: {}", e.getMessage());
            return stored;
        }
    }

    /** 脱敏:解密后取后4位;解密失败(仍为密文)返回 **** */
    public String mask(String stored) {
        String plain = decrypt(stored);
        if (plain == null || plain.isEmpty()) return "";
        if (plain.startsWith(PREFIX)) return "****";
        if (plain.length() <= 4) return "****";
        return "****" + plain.substring(plain.length() - 4);
    }
}
