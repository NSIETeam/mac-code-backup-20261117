package cn.siyue.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.text.Normalizer;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Versioned AES-256-GCM storage; nonce, ciphertext and tag travel together. */
@Component
public class PiiCipher {
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();
    /** 从配置加载带版本编号的 AES-256 密钥环，异常消息不包含配置原文。
     * @param json 密钥编号到 Base64 编码 32 字节密钥的 JSON 映射
     * @param activeKeyId 当前写入密钥编号，必须存在于密钥环
     * @param mapper 配置 JSON 解码器
     * @throws IllegalArgumentException JSON、编号、Base64、密钥长度或当前密钥配置无效时
     */
    @Autowired
    public PiiCipher(@Value("${siyue.pii-keys-json}") String json,
            @Value("${siyue.pii-active-key-id}") String activeKeyId, ObjectMapper mapper) {
        this(parseKeys(json, mapper), activeKeyId);
    }
    /** Test constructor; production wiring uses the configured versioned keyring. */
    PiiCipher(String encoded) {
        this(Map.of("test", encoded), "test");
    }
    /** Builds a keyring and rejects missing or malformed active key configuration. */
    PiiCipher(Map<String, String> encodedKeys, String activeKeyId) {
        if (activeKeyId == null || !activeKeyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("PII active key id is invalid");
        }
        Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
        encodedKeys.forEach((id, encoded) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("PII key id is invalid");
            }
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("PII key encoding is invalid"); }
            if (bytes.length != 32) { throw new IllegalArgumentException("PII key must be 256 bits"); }
            parsed.put(id, new SecretKeySpec(bytes, "AES"));
        });
        if (parsed.isEmpty() || !parsed.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("PII active key is absent from keyring");
        }
        this.keys = Map.copyOf(parsed);
        this.activeKeyId = activeKeyId;
    }
    private static Map<String, String> parseKeys(String json, ObjectMapper mapper) {
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>() { }); }
        catch (Exception error) { throw new IllegalArgumentException("PII keyring configuration is invalid"); }
    }
    /** 使用当前密钥和新的 12 字节 nonce 加密 UTF-8 内容，并绑定密钥编号作为附加认证数据。
     * @param text 非 null 明文；空串可以加密
     * @return 含版本、密钥编号、nonce、密文及认证标签的 v2 编码
     * @throws IllegalStateException 加密算法或密钥初始化、加密操作失败时
     */
    public String encrypt(String text) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(activeKeyId));
            return "v2." + activeKeyId + "." + Base64.getEncoder().encodeToString(nonce) + "."
                + Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("PII encryption failed", error);
        }
    }
    /** 按密文携带的密钥编号解密并验证认证标签，不接受未知格式或缺失密钥。
     * @param encoded 非 null 的 v2 加密编码
     * @return 验证成功后的 UTF-8 明文
     * @throws IllegalStateException 格式、Base64、密钥编号、认证标签或解密操作无效时
     */
    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split("\\.");
            if (parts.length != 4 || !parts[0].equals("v2")) {
                throw new IllegalArgumentException("Unknown PII encoding");
            }
            SecretKeySpec key = keys.get(parts[1]);
            if (key == null) { throw new IllegalArgumentException("Unknown PII key id"); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[2])));
            cipher.updateAAD(aad(parts[1]));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("PII decryption failed", error);
        }
    }
    /** 为密钥环内每个版本生成用途隔离的 HMAC 检索值，以兼容密钥轮换期间的查询。
     * @param purpose 1 至 32 位小写字母或下划线用途标识
     * @param value 非 null 待索引值；进行 NFKC、首尾空白及大小写归一，phone 另去除空白、括号和短横线
     * @return 密钥编号到对应 HMAC 字节数组的不可修改映射
     * @throws IllegalArgumentException 用途标识无效时
     * @throws IllegalStateException 索引密钥派生或 HMAC 计算失败时
     */
    public Map<String, byte[]> blindIndexes(String purpose, String value) {
        if (purpose == null || !purpose.matches("[a-z_]{1,32}")) {
            throw new IllegalArgumentException("Blind index purpose is invalid");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().toLowerCase(Locale.ROOT);
        if (purpose.equals("phone")) { normalized = normalized.replaceAll("[\\s()\\-]", ""); }
        String indexedValue = normalized;
        Map<String, byte[]> indexes = new LinkedHashMap<>();
        keys.forEach((keyId, encryptionKey) -> {
            try {
                Mac derive = Mac.getInstance("HmacSHA256");
                derive.init(new SecretKeySpec(encryptionKey.getEncoded(), "HmacSHA256"));
                byte[] indexKey = derive.doFinal(("siyue-index-key:v1:" + keyId)
                    .getBytes(StandardCharsets.UTF_8));
                Mac index = Mac.getInstance("HmacSHA256");
                index.init(new SecretKeySpec(indexKey, "HmacSHA256"));
                index.update(("siyue-index:v1:" + purpose + ":").getBytes(StandardCharsets.UTF_8));
                indexes.put(keyId, index.doFinal(indexedValue.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("PII blind index failed", error);
            }
        });
        return Map.copyOf(indexes);
    }
    /** 为外部标识生成精确匹配索引，不改变大小写、Unicode 或空白。
     * @param purpose 1 至 32 位小写字母或下划线用途标识
     * @param value 非 null 外部标识原文
     * @return 各密钥版本对应的用途隔离 HMAC 字节数组映射
     * @throws IllegalArgumentException 用途标识或输入值无效时
     * @throws IllegalStateException 派生或 HMAC 计算失败时
     */
    public Map<String, byte[]> exactBlindIndexes(String purpose, String value) {
        if (purpose == null || !purpose.matches("[a-z_]{1,32}") || value == null) {
            throw new IllegalArgumentException("Exact blind index input is invalid");
        }
        Map<String, byte[]> indexes = new LinkedHashMap<>();
        keys.forEach((keyId, encryptionKey) -> {
            try {
                Mac derive = Mac.getInstance("HmacSHA256");
                derive.init(new SecretKeySpec(encryptionKey.getEncoded(), "HmacSHA256"));
                byte[] indexKey = derive.doFinal(("siyue-exact-index-key:v1:" + keyId)
                    .getBytes(StandardCharsets.UTF_8));
                Mac index = Mac.getInstance("HmacSHA256");
                index.init(new SecretKeySpec(indexKey, "HmacSHA256"));
                index.update(("siyue-exact-index:v1:" + purpose + ":").getBytes(StandardCharsets.UTF_8));
                indexes.put(keyId, index.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("Exact blind index failed", error);
            }
        });
        return Map.copyOf(indexes);
    }
    /** 生成当前密钥下的用途隔离指纹，供短期限流使用，不承诺跨密钥轮换稳定。
     * @param purpose 1 至 32 位小写字母、下划线或短横线用途标识
     * @param value 非空输入，进行 NFKC 和首尾空白归一
     * @return 绑定当前密钥编号和用途的 HMAC 指纹字节数组
     * @throws IllegalArgumentException 用途或输入值无效时
     * @throws IllegalStateException 指纹密钥派生或 HMAC 计算失败时
     */
    public byte[] fingerprint(String purpose,String value) {
        if(purpose==null||!purpose.matches("[a-z_-]{1,32}")||value==null||value.isBlank()){
            throw new IllegalArgumentException("Fingerprint input is invalid");
        }
        try {
            Mac derive=Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(keys.get(activeKeyId).getEncoded(),"HmacSHA256"));
            byte[] fingerprintKey=derive.doFinal(("siyue-fingerprint-key:v1:"+activeKeyId).getBytes(StandardCharsets.UTF_8));
            Mac fingerprint=Mac.getInstance("HmacSHA256");
            fingerprint.init(new SecretKeySpec(fingerprintKey,"HmacSHA256"));
            fingerprint.update(("siyue-fingerprint:v1:"+activeKeyId+":"+purpose+":").getBytes(StandardCharsets.UTF_8));
            return fingerprint.doFinal(Normalizer.normalize(value,Normalizer.Form.NFKC).trim().getBytes(StandardCharsets.UTF_8));
        } catch(GeneralSecurityException error) {
            throw new IllegalStateException("PII fingerprint failed",error);
        }
    }
    private static byte[] aad(String keyId) {
        return ("siyue-pii:v2:" + keyId).getBytes(StandardCharsets.UTF_8);
    }
    /** 解密电话号码后掩码，保留足够长号码的前三位与后四位。
     * @param encoded 加密电话号码；null 或空白表示没有号码
     * @return 缺值时为空串，少于 7 字符时为四个星号，否则返回部分掩码号码
     * @throws IllegalStateException 非空值解密或认证失败时
     */
    public String maskedPhone(String encoded) {
        if (encoded == null || encoded.isBlank()) { return ""; }
        String phone = decrypt(encoded);
        return phone.length() < 7 ? "****" : phone.substring(0, 3) + "****"
            + phone.substring(phone.length() - 4);
    }
    /** 按联系渠道掩码解密结果，非 phone 渠道采用邮件形式处理。
     * @param channel 非 null 渠道；phone 使用电话号码掩码
     * @param encoded 加密联系方式；null 或空白表示没有联系方式
     * @return 缺值为空串；电话部分掩码；邮件保留首字符和域名，无有效分隔符时为四个星号
     * @throws IllegalStateException 非空值解密或认证失败时
     */
    public String maskedContact(String channel, String encoded) {
        if (encoded == null || encoded.isBlank()) { return ""; }
        if (channel.equals("phone")) { return maskedPhone(encoded); }
        String email = decrypt(encoded);
        int separator = email.lastIndexOf('@');
        if (separator < 1) { return "****"; }
        return email.substring(0, 1) + "***" + email.substring(separator);
    }
}
