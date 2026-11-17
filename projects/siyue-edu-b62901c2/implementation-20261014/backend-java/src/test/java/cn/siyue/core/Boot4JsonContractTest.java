package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Verifies the real MVC converter preserves pre-upgrade JSON and authentication contracts. */
@WebMvcTest(AcademicController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class Boot4JsonContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerAdapter adapter;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AccessRepository access;
    @MockitoBean AcademicService service;
    private static final String PATH = "/api/v2/academic/classes/class-one/enrollments";
    private static final String BODY = "{\"studentId\":\"student-one\",\"orderId\":\"order-one\"}";

    @Test void actualMvcSelectsJackson2AndRejectsUnknownFields() throws Exception {
        var converter = adapter.getMessageConverters().stream()
            .filter(item -> item.canRead(AcademicDtos.EnrollmentInput.class, MediaType.APPLICATION_JSON))
            .findFirst().orElseThrow();
        assertInstanceOf(MappingJackson2HttpMessageConverter.class, converter);
        mvc.perform(post(PATH).with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))).header("Origin", "https://crm.example.com")
            .header("Idempotency-Key", "json-contract-one").contentType(MediaType.APPLICATION_JSON)
            .content(BODY.substring(0, BODY.length() - 1) + ",\"actorId\":\"forged\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(access).credentialGenerationObsolete("https://issuer.example", "subject", null);
        verifyNoInteractions(service);
    }

    @Test void httpReceiptAndCanonicalRequestKeepExactJsonBytes() throws Exception {
        var actor = new Actor(7, "mapped", "assigned", Set.of(), Set.of("enrollment.create", "crm.order.read"));
        var input = new AcademicDtos.EnrollmentInput("student-one", "order-one");
        var receipt = new AcademicDtos.EnrollmentReceipt("enrollment-one", "student-one", "class-one",
            "active", Instant.parse("2026-10-17T01:02:03Z"));
        String frozen = "{\"id\":\"enrollment-one\",\"studentId\":\"student-one\",\"classId\":\"class-one\","
            + "\"status\":\"active\",\"activatedAt\":\"2026-10-17T01:02:03Z\"}";
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        when(service.enrollStudent(actor, "class-one", "json-contract-one", input)).thenReturn(receipt);
        String response = mvc.perform(post(PATH).with(jwt().jwt(token -> token
            .issuer("https://issuer.example").subject("subject"))).header("Origin", "https://crm.example.com")
            .header("Idempotency-Key", "json-contract-one").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.activatedAt").value("2026-10-17T01:02:03Z"))
            .andReturn().getResponse().getContentAsString();
        assertEquals(frozen, mapper.writeValueAsString(receipt));
        assertTrue(response.contains("\"data\":" + frozen));
        assertEquals("UTC", mapper.getSerializationConfig().getTimeZone().getID());
        assertEquals("[\"class-one\"," + BODY + "]", mapper.writeValueAsString(List.of("class-one", input)));
        assertEquals(receipt, mapper.readValue(frozen, AcademicDtos.EnrollmentReceipt.class));
        verify(service).enrollStudent(actor, "class-one", "json-contract-one", input);
    }

    @Test void unchangedSecurityChainRejectsAnonymousWrites() throws Exception {
        mvc.perform(post(PATH).header("Origin", "https://crm.example.com")
            .header("Idempotency-Key", "json-contract-one").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(access, service);
    }
}
