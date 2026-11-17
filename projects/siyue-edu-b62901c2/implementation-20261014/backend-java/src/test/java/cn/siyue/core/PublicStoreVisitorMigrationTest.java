package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicStoreVisitorMigrationTest {
    private static final String LEGACY="ab".repeat(32);
    private static final String ID="a".repeat(32);
    private final PiiCipher cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void verifiesLegacyUtf8KeyAndResignsTheSameIdentity() throws Exception {
        var visitor=new PublicStoreVisitor(cipher,LEGACY);
        var request=request(ID+"."+signature(LEGACY));var response=new MockHttpServletResponse();
        assertEquals(ID,visitor.identity(request,response));
        String header=response.getHeader("Set-Cookie");assertNotNull(header);
        Cookie renewed=response.getCookie("siyue_visitor");assertNotNull(renewed);
        assertTrue(renewed.isHttpOnly());assertTrue(renewed.getSecure());
        assertEquals("Strict",renewed.getAttribute("SameSite"));assertEquals("/crm-api/",renewed.getPath());
        assertEquals(31536000,renewed.getMaxAge());
        String token=header.split(";",2)[0].substring("siyue_visitor=".length());
        assertEquals(ID,new PublicStoreVisitor(cipher).identity(request(token),new MockHttpServletResponse()));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(ID.getBytes(StandardCharsets.UTF_8))),visitor.fingerprint(request,new MockHttpServletResponse()));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(("\""+ID+"\"").getBytes(StandardCharsets.UTF_8))),
            visitor.receiptFingerprints(request,new MockHttpServletResponse()).get(1));
    }
    @Test void missingKeyAndWrongSignatureDoNotPreserveUnverifiedIdentity() throws Exception {
        assertNotEquals(ID,new PublicStoreVisitor(cipher).identity(request(ID+"."+signature(LEGACY)),new MockHttpServletResponse()));
        assertNotEquals(ID,new PublicStoreVisitor(cipher,LEGACY).identity(request(ID+"."+signature("cd".repeat(32))),new MockHttpServletResponse()));
    }
    private MockHttpServletRequest request(String token) {
        var request=new MockHttpServletRequest();request.setCookies(new Cookie("siyue_visitor",token));return request;
    }
    private String signature(String key) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(ID.getBytes(StandardCharsets.UTF_8)));
    }
}
