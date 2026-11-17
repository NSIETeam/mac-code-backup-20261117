package cn.siyue.core;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Issues a signed, HttpOnly visitor cookie and preserves verified visitor identity across the Node migration. */
@Component
public class PublicStoreVisitor {
    private static final String COOKIE = "siyue_visitor";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}");
    private final PiiCipher cipher;
    private final String legacySecret;
    private final SecureRandom random = new SecureRandom();

    public PublicStoreVisitor(PiiCipher cipher) {this(cipher,"");}
    @org.springframework.beans.factory.annotation.Autowired
    public PublicStoreVisitor(PiiCipher cipher,@org.springframework.beans.factory.annotation.Value("${siyue.legacy-visitor-secret:}") String legacySecret) {
        this.cipher=cipher;this.legacySecret=legacySecret;
        if(!legacySecret.isEmpty()&&!legacySecret.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Legacy visitor key must be 32-byte hex text");
    }

    /** Validates or creates a one-year SameSite=Strict visitor session. */
    public String fingerprint(HttpServletRequest request, HttpServletResponse response) {
        String id=identity(request,response);
        return fingerprintBytes(id, false);
    }

    /** Fingerprints accepted for status reads, both derived from this verified cookie identity. */
    public List<String> receiptFingerprints(HttpServletRequest request, HttpServletResponse response) {
        String id=identity(request,response);
        return List.of(fingerprintBytes(id, false), fingerprintBytes(id, true));
    }

    private String fingerprintBytes(String id, boolean legacyNodeJson) {
        try {
            String material=legacyNodeJson ? "\""+id+"\"" : id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException("SHA-256 is unavailable",error);}
    }

    /** Returns the verified random visitor identity only to internal hashing code. */
    public String identity(HttpServletRequest request,HttpServletResponse response) {
        String supplied=cookie(request);String id=supplied==null?null:verify(supplied);
        boolean legacy=id==null&&supplied!=null&&!legacySecret.isEmpty()&&legacyValid(supplied);
        if(legacy) {id=supplied.split("\\.",-1)[0];setCookie(response,id);}
        if (id == null) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            setCookie(response,id);
        }
        return id;
    }
    private void setCookie(HttpServletResponse response,String id) {
        response.addHeader("Set-Cookie",COOKIE+"="+id+"."+signature(id)+"; Path=/crm-api/; HttpOnly; Secure; SameSite=Strict; Max-Age=31536000");
    }
    private boolean legacyValid(String token) {
        String[] parts=token.split("\\.",-1);
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(legacySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            String expected=Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(parts[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(parts[1].getBytes(java.nio.charset.StandardCharsets.US_ASCII),expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch(java.security.GeneralSecurityException error) {throw new IllegalStateException("Legacy visitor verification is unavailable",error);}
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) { return null; }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && TOKEN.matcher(cookie.getValue()).matches()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String verify(String token) {
        String[] parts = token.split("\\.", -1);
        byte[] supplied = parts[1].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] expected = signature(parts[0]).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(supplied, expected) ? parts[0] : null;
    }

    private String signature(String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cipher.fingerprint("public_store_visitor_sig", id));
    }
}
