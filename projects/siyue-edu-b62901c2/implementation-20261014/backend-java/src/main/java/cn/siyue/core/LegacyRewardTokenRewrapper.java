package cn.siyue.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One-way in-memory migration from the Node visitor key to the target PII keyring. Never logs token or key. */
final class LegacyRewardTokenRewrapper {
    private LegacyRewardTokenRewrapper() { }
    static String rewrap(String sourceCipher,String tokenHash,PiiCipher target) {
        String keyHex=System.getenv("SIYUE_LEGACY_REWARD_KEY_HEX");
        if(keyHex==null||!keyHex.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("历史奖励凭证迁移需要受控环境中的旧visitor密钥");
        byte[] key=HexFormat.of().parseHex(keyHex);
        try { return rewrap(sourceCipher,tokenHash,target,key); }
        finally {java.util.Arrays.fill(key,(byte)0);}
    }
    static String rewrap(String sourceCipher,String tokenHash,PiiCipher target,byte[] sourceKey) {
        if(sourceKey==null||sourceKey.length!=32) throw new IllegalArgumentException("历史奖励密钥长度无效");
        byte[] key=sourceKey.clone();byte[] plaintext=null;
        try {
            String[] parts=sourceCipher.split("\\.");
            if(parts.length!=3) throw new IllegalArgumentException("历史奖励凭证格式无效");
            byte[] iv=Base64.getUrlDecoder().decode(parts[0]),tag=Base64.getUrlDecoder().decode(parts[1]);
            byte[] body=Base64.getUrlDecoder().decode(parts[2]);
            if(iv.length!=12||tag.length!=16) throw new IllegalArgumentException("历史奖励凭证格式无效");
            byte[] encrypted=new byte[body.length+tag.length];System.arraycopy(body,0,encrypted,0,body.length);System.arraycopy(tag,0,encrypted,body.length,tag.length);
            Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding");decrypt.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            plaintext=decrypt.doFinal(encrypted);String token=new String(plaintext,StandardCharsets.UTF_8);
            if(!LegacyCatalogRehearsal.hash(token).equals(tokenHash)||token.length()<24||token.length()>128)
                throw new IllegalArgumentException("历史奖励凭证摘要不匹配");
            return target.encrypt(token);
        } catch(java.security.GeneralSecurityException|IllegalArgumentException error) {
            throw new IllegalArgumentException("历史奖励凭证认证或摘要验证失败");
        } finally {java.util.Arrays.fill(key,(byte)0);if(plaintext!=null)java.util.Arrays.fill(plaintext,(byte)0);}
    }
}
