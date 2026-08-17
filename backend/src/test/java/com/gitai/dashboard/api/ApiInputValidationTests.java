package com.gitai.dashboard.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api_input_validation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class ApiInputValidationTests {
    @Autowired private MockMvc mockMvc;

    @Test
    void rejectsAReversedDashboardDateRange() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", token("superadmin"))
                        .param("from", "2026-08-16").param("to", "2026-08-15"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForMissingSyncResourcesInsteadOfInternalServerError() throws Exception {
        String token = token("superadmin");
        mockMvc.perform(get("/api/sync-jobs/999999").header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/repositories/999999/sync-jobs").header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sync-jobs/999999/cancel").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedDashboardDates() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", token("superadmin")).param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boundsTheSyncJobListLimit() throws Exception {
        String token = token("viewer");
        mockMvc.perform(get("/api/sync-jobs").header("Authorization", token).param("limit", "-100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/sync-jobs").header("Authorization", token).param("limit", "999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void exposesAndValidatesTheAutomaticSyncSchedule() throws Exception {
        String superAdmin = token("superadmin");
        mockMvc.perform(get("/api/sync-schedule").header("Authorization", superAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.intervalMinutes").value(60));
        mockMvc.perform(put("/api/sync-schedule").header("Authorization", superAdmin).contentType("application/json")
                        .content("{\"enabled\":true,\"intervalMinutes\":15}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.intervalMinutes").value(15));
        mockMvc.perform(put("/api/sync-schedule").header("Authorization", superAdmin).contentType("application/json")
                        .content("{\"enabled\":true,\"intervalMinutes\":1}"))
                .andExpect(status().isBadRequest());
    }

    private String token(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"placeholder\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String marker = "\"token\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf('\"', start);
        return "Bearer " + body.substring(start, end);
    }
}
