package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Protects prior request digests while allowing explicit registration and follow-up choices. */
class LeadDispositionContractTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();

    @Test void omittedChoicesKeepTheOriginalRequestShape() throws Exception {
        var input=new CrmDtos.LeadInput("customer",null,null,"phone","合成咨询",null,null,null,null,null);
        var tree=mapper.valueToTree(input);
        assertFalse(tree.has("followChoice"));assertEquals(9,tree.size());
        var follow=new CrmDtos.LeadFollowupInput("phone","合成沟通",null,1);
        assertEquals("{\"channel\":\"phone\",\"summary\":\"合成沟通\",\"nextFollowAt\":null,\"expectedVersion\":1}",
            mapper.writeValueAsString(follow));
        assertEquals(input,mapper.readValue(mapper.writeValueAsString(input),CrmDtos.LeadInput.class));
    }

    @Test void explicitChoicesParticipateInTheRetryBody() throws Exception {
        var input=mapper.readValue("{\"customerId\":\"customer\",\"source\":\"phone\",\"title\":\"合成咨询\",\"followChoice\":\"self\"}",CrmDtos.LeadInput.class);
        assertEquals("self",input.followChoice());
        var follow=new CrmDtos.LeadFollowupInput("phone","合成流失",null,1,"lost_cold");
        assertEquals("lost_cold",mapper.valueToTree(follow).path("disposition").asText());
        assertNotEquals(mapper.writeValueAsString(follow),mapper.writeValueAsString(
            new CrmDtos.LeadFollowupInput("phone","合成流失",null,1)));
    }
}
