package com.dataextraction.saas;

import com.dataextraction.saas.document.DocumentStore;
import com.dataextraction.saas.extraction.ExtractionStore;
import com.dataextraction.saas.extraction.ExtractionResult;
import com.dataextraction.saas.extraction.ReviewStatus;
import com.dataextraction.saas.extraction.ValidationIssue;
import com.dataextraction.saas.processing.ExtractionJob;
import com.dataextraction.saas.processing.ExtractionJobStore;
import com.dataextraction.saas.processing.JobStatus;
import com.dataextraction.saas.document.DocumentRecord;
import com.dataextraction.saas.document.DocumentStatus;
import com.dataextraction.saas.export.ExportStore;
import com.dataextraction.saas.audit.AuditEvent;
import com.dataextraction.saas.audit.AuditStore;
import com.dataextraction.saas.security.AccountStore;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.security.seed-demo-users=true",
        "spring.datasource.url=jdbc:h2:mem:extractiq-tests;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private DocumentStore documentStore;
    @Autowired private ExtractionJobStore jobStore;
    @Autowired private ExtractionStore extractionStore;
    @Autowired private AccountStore accountStore;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrf;

    @BeforeEach
    void initialize() throws Exception {
        documentStore.clear(); jobStore.clear(); extractionStore.clear();
        var tokenResponse = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        csrfCookie = tokenResponse.getCookie("XSRF-TOKEN");
        csrf = csrfCookie.getValue();
        var login = mockMvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@extractiq.local\",\"password\":\"User@ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn();
        sessionCookie = login.getResponse().getCookie("EXTRACTIQ_SESSION");
    }

    @Test
    void authenticatedCsvWorkflowSupportsReviewApprovalAndExport() throws Exception {
        byte[] csv = "id,total\n1,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String uploadResponse = mockMvc.perform(secured(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"records.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":14}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String documentId = value(uploadResponse, "documentId");

        mockMvc.perform(secured(put("/api/v1/documents/{id}/content", documentId).contentType("text/csv").content(csv)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("UPLOADED")));
        mockMvc.perform(secured(get("/api/v1/documents")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id", is(documentId)));

        String jobResponse = mockMvc.perform(secured(post("/api/v1/documents/{id}/extractions", documentId)
                .header("Idempotency-Key", "integration-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"TABLE\",\"outputFormat\":\"STRUCTURED_JSON\"}")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = value(jobResponse, "jobId");
        awaitTerminal(jobId);
        String completed = mockMvc.perform(secured(get("/api/v1/jobs/{id}", jobId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")))
                .andReturn().getResponse().getContentAsString();
        String extractionId = value(completed, "extractionId");

        mockMvc.perform(secured(get("/api/v1/extractions/{id}/results", extractionId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recordCount", is(1)));
        mockMvc.perform(secured(post("/api/v1/extractions/{id}/approve", extractionId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus", is("APPROVED")));
        mockMvc.perform(secured(get("/api/v1/records").param("q", "10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(1)));

        String export = mockMvc.perform(secured(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"CSV\"}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status", is("COMPLETED")))
                .andReturn().getResponse().getContentAsString();
        String exportId = value(export, "exportId");
        mockMvc.perform(secured(get("/api/v1/exports/{id}/download", exportId)))
                .andExpect(status().isOk()).andExpect(content().contentType("text/csv"));

        mockMvc.perform(secured(get("/api/v1/analytics/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount", is(1)));
        mockMvc.perform(secured(delete("/api/v1/documents/{id}", documentId))).andExpect(status().isNoContent());
        mockMvc.perform(secured(get("/api/v1/documents/{id}", documentId))).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnsupportedFileTypeWithStructuredError() throws Exception {
        mockMvc.perform(secured(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"archive.zip\",\"contentType\":\"application/zip\",\"sizeBytes\":10}")))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code", is("UNSUPPORTED_FILE_TYPE")));
    }

    @Test
    void unauthenticatedApiAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
    }

    @Test
    void publicApiRootProvidesServiceInformation() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", is("ExtractIQ Spring Boot API")))
                .andExpect(jsonPath("$.apiBasePath", is("/api/v1")));
    }

    @Test
    void savesApplicationDataInH2AndCanReloadItThroughFreshStores() {
        assertNotNull(accountStore.findByEmail("admin@extractiq.local").orElse(null));

        var documentId = java.util.UUID.randomUUID();
        var organizationId = java.util.UUID.randomUUID();
        var userId = java.util.UUID.randomUUID();
        var now = java.time.Instant.ofEpochMilli(java.time.Instant.now().toEpochMilli());
        var document = new DocumentRecord(documentId, organizationId, userId, "persist.csv", "text/csv", 3,
                DocumentStatus.UPLOADED, now, now.plusSeconds(300), now);
        documentStore.save(document);
        documentStore.saveContent(documentId, new byte[]{1, 2, 3});

        var jobId = java.util.UUID.randomUUID();
        var extractionId = java.util.UUID.randomUUID();
        var job = new ExtractionJob(jobId, documentId, organizationId, "TABLE", "STRUCTURED_JSON", JobStatus.COMPLETED,
                100, 1, now, now, extractionId, null);
        jobStore.save(job);
        jobStore.rememberIdempotencyKey(documentId + ":stable-key", jobId);
        var result = new ExtractionResult(extractionId, jobId, documentId, organizationId, "TABLE", "STRUCTURED_JSON",
                java.util.Map.of("summary", "Persisted report", "records", java.util.List.of(java.util.Map.of("value", 42))),
                ReviewStatus.APPROVED, 2, java.util.List.of(new ValidationIssue("value", "CHECKED", "Validated")), now, now, now);
        extractionStore.save(result);
        var export = new ExportStore.ExportFile(java.util.UUID.randomUUID(), organizationId, java.util.Set.of(documentId),
                "COMPLETED", "report.csv", new byte[]{4, 5}, now);
        var exportStore = new ExportStore(jdbc, json);
        exportStore.save(export);
        var event = new AuditEvent(java.util.UUID.randomUUID(), userId, organizationId, "POST", "/api/v1/documents", 201, now);
        var auditStore = new AuditStore(jdbc);
        auditStore.append(event);

        var reopenedDocuments = new DocumentStore(jdbc);
        var reopenedJobs = new ExtractionJobStore(jdbc);
        var reopenedResults = new ExtractionStore(jdbc, json);
        assertEquals(document, reopenedDocuments.find(documentId));
        assertEquals(3, reopenedDocuments.getContent(documentId).length);
        assertEquals(job, reopenedJobs.find(jobId));
        assertEquals(jobId, reopenedJobs.findByIdempotencyKey(documentId + ":stable-key"));
        assertEquals(result, reopenedResults.find(extractionId));
        assertEquals(export.filename(), exportStore.find(export.id()).filename());
        assertEquals(event.eventId(), auditStore.latest(1).getFirst().eventId());
    }

    @Test
    void adminCanManageUserRoleAndDisableExistingSession() throws Exception {
        Cookie adminCookie = loginAdmin();
        var user = accountStore.findByEmail("user@extractiq.local").orElseThrow();
        String accessUrl = "/api/v1/admin/users/" + user.id() + "/access";
        try {
            mockMvc.perform(patch(accessUrl).cookie(adminCookie, csrfCookie).header("X-XSRF-TOKEN", csrf)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role", is("ADMIN")));
            mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie))
                    .andExpect(status().isOk()); // A role change is refreshed on the user's existing JDBC session.
            mockMvc.perform(patch(accessUrl).cookie(adminCookie, csrfCookie).header("X-XSRF-TOKEN", csrf)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled", is(false)));
            mockMvc.perform(get("/api/v1/users/me").cookie(sessionCookie))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("ACCOUNT_DISABLED")));
        } finally {
            accountStore.save(new com.dataextraction.saas.security.UserAccount(user.id(), user.organizationId(), user.email(),
                    user.passwordHash(), user.displayName(), "USER", true, user.createdAt()));
        }
    }

    @Test
    void regularUserCannotAccessAdminUserManagement() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Cookie loginAdmin() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@extractiq.local\",\"password\":\"Admin@ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("EXTRACTIQ_SESSION");
    }

    @Test
    void organizationCannotReadAnotherOrganizationsDocument() throws Exception {
        String response = mockMvc.perform(secured(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"private.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":4}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String documentId = value(response, "documentId");
        var adminLogin = mockMvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@extractiq.local\",\"password\":\"Admin@ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie adminSessionCookie = adminLogin.getResponse().getCookie("EXTRACTIQ_SESSION");
        mockMvc.perform(get("/api/v1/documents/{id}", documentId).cookie(adminSessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("DOCUMENT_NOT_FOUND")));
    }

    @Test
    void csrfProtectionRejectsMutationWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/documents").cookie(sessionCookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"private.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesTheAuthenticatedSession() throws Exception {
        mockMvc.perform(secured(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder secured(MockHttpServletRequestBuilder builder) {
        return builder.cookie(sessionCookie, csrfCookie).header("X-XSRF-TOKEN", csrf);
    }
    private void awaitTerminal(String jobId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            String body = mockMvc.perform(secured(get("/api/v1/jobs/{id}", jobId))).andReturn().getResponse().getContentAsString();
            String currentStatus = value(body, "status");
            if ("COMPLETED".equals(currentStatus) || "FAILED".equals(currentStatus)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Extraction did not reach a terminal status");
    }
    private String value(String json, String name) {
        return com.jayway.jsonpath.JsonPath.read(json, "$." + name).toString();
    }
}

