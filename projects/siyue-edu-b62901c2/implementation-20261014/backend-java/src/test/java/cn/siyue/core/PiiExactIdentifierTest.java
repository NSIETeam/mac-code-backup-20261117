package cn.siyue.core;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PiiExactIdentifierTest {
    String key(int value){byte[] bytes=new byte[32];java.util.Arrays.fill(bytes,(byte)value);return Base64.getEncoder().encodeToString(bytes);}
    @Test void preservesLeadingZeroCaseUnicodeAndWhitespaceWithoutChangingLegacyIndexes(){
        PiiCipher cipher=new PiiCipher(key(1));
        for(String other:new String[]{"1AbC","001abc","００１AbC","001AbC "})assertFalse(java.util.Arrays.equals(cipher.exactBlindIndexes("student_number","001AbC").get("test"),cipher.exactBlindIndexes("student_number",other).get("test")));
        assertArrayEquals(cipher.blindIndexes("name"," AbC ").get("test"),cipher.blindIndexes("name","abc").get("test"));
    }
    @Test void allConfiguredKeysParticipateAndOldIndexSurvivesOverlapRotation(){
        PiiCipher old=new PiiCipher(Map.of("old",key(1)),"old"),rotated=new PiiCipher(Map.of("old",key(1),"new",key(2)),"new");
        assertEquals(2,rotated.exactBlindIndexes("student_number","001").size());
        assertArrayEquals(old.exactBlindIndexes("student_number","001").get("old"),rotated.exactBlindIndexes("student_number","001").get("old"));
        assertNotEquals(old.encrypt("001"),rotated.encrypt("001"));assertEquals("001",rotated.decrypt(old.encrypt("001")));
    }
    @Test void namespaceLengthFramingAndPurposeSeparationDoNotAlias(){
        PiiCipher c=new PiiCipher(key(1));
        assertFalse(java.util.Arrays.equals(c.exactBlindIndexes("student_identity","1:A2:BC").get("test"),c.exactBlindIndexes("student_identity","2:AB1:C").get("test")));
        assertFalse(java.util.Arrays.equals(c.exactBlindIndexes("student_number","001").get("test"),c.exactBlindIndexes("student_identity","001").get("test")));
        assertFalse(java.util.Arrays.equals(c.exactBlindIndexes("student_number","001").get("test"),c.blindIndexes("student_number","001").get("test")));
    }
    @Test void invalidPurposeOrNullValueFailWithoutLeakingData(){
        PiiCipher c=new PiiCipher(key(1));assertThrows(IllegalArgumentException.class,()->c.exactBlindIndexes("bad-purpose","001"));assertThrows(IllegalArgumentException.class,()->c.exactBlindIndexes("student_number",null));
    }
}
