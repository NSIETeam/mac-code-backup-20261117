package cn.siyue.core;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRewardTokenRewrapperTest {
    @Test void rewrapsOnlyAuthenticatedSourceTokenMatchingItsStoredDigest() throws Exception {
        byte[] key=new byte[32];java.util.Arrays.fill(key,(byte)7);byte[] iv=new byte[12];java.util.Arrays.fill(iv,(byte)3);
        String token="synthetic-coupon-token-0123456789";
        Cipher source=Cipher.getInstance("AES/GCM/NoPadding");source.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        byte[] encrypted=source.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String wire=encoder.encodeToString(iv)+"."+encoder.encodeToString(java.util.Arrays.copyOfRange(encrypted,encrypted.length-16,encrypted.length))
            +"."+encoder.encodeToString(java.util.Arrays.copyOf(encrypted,encrypted.length-16));
        PiiCipher target=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String rewrapped=LegacyRewardTokenRewrapper.rewrap(wire,LegacyCatalogRehearsal.hash(token),target,key);
        assertEquals(token,target.decrypt(rewrapped));assertFalse(rewrapped.contains(token));
        assertThrows(IllegalArgumentException.class,()->LegacyRewardTokenRewrapper.rewrap(wire,"0".repeat(64),target,key));
        byte[] wrongKey=new byte[32];assertThrows(IllegalArgumentException.class,()->LegacyRewardTokenRewrapper.rewrap(wire,LegacyCatalogRehearsal.hash(token),target,wrongKey));
        assertEquals(7,key[0],"调用方提供的密钥缓冲区不得被破坏");
    }
}
