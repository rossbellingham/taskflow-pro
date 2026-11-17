package com.taskflow;

import com.jayway.jsonpath.JsonPath;
import com.taskflow.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin password reset (password and lockout only: sessions and forced change are not part of this version). */
class AdminPasswordResetTest extends IntegrationTestBase {

    private static final String TEMP_PASSWORD = "temporary-pass-1";

    @Test
    void adminReset_replacesThePassword_andClearsLockout() throws Exception {
        Client admin = login(createUserInDatabase("admin", Role.ROLE_ADMIN));
        String victim = createUserInDatabase("forgetful", Role.ROLE_EMPLOYEE);

        RequestPostProcessor fromOneAddress = request -> {
            request.setRemoteAddr("203.0.113.61"); // keeps these failures off other tests' IP counters
            return request;
        };
        Client guesser = new Client(fetchCsrfToken());
        for (int i = 0; i < 5; i++) {
            loginAttempt(guesser, fromOneAddress, victim, "wrong-guess-" + i).andExpect(status().isUnauthorized());
        }
        loginAttempt(guesser, fromOneAddress, victim, PASSWORD).andExpect(status().isTooManyRequests());

        mvc.perform(put("/api/admin/users/{id}/password", idOf(victim))
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"%s\"}".formatted(TEMP_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(victim))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist()); // only returned when generated

        // Lockout cleared: the old password is simply wrong now, and the new one works
        loginAttempt(guesser, fromOneAddress, victim, PASSWORD).andExpect(status().isUnauthorized());
        loginAttempt(new Client(fetchCsrfToken()), fromOneAddress, victim, TEMP_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void adminReset_canGenerateThePassword() throws Exception {
        Client admin = login(createUserInDatabase("admin", Role.ROLE_ADMIN));
        String victim = createUserInDatabase("forgetful", Role.ROLE_EMPLOYEE);

        MvcResult result = mvc.perform(put("/api/admin/users/{id}/password", idOf(victim))
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();
        String generated = JsonPath.read(result.getResponse().getContentAsString(), "$.temporaryPassword");

        mvc.perform(post("/api/auth/login")
                        .with(new Client(fetchCsrfToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(victim, generated)))
                .andExpect(status().isOk());
    }

    @Test
    void adminReset_guards() throws Exception {
        String adminName = createUserInDatabase("admin", Role.ROLE_ADMIN);
        Client admin = login(adminName);
        Client employee = login(createUserInDatabase("worker", Role.ROLE_EMPLOYEE));
        long targetId = idOf(createUserInDatabase("target", Role.ROLE_EMPLOYEE));
        String body = "{\"temporaryPassword\":\"%s\"}".formatted(TEMP_PASSWORD);

        // Only admins
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .with(employee).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .with(new Client(fetchCsrfToken())) // a valid CSRF token but no login
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // Not your own account
        mvc.perform(put("/api/admin/users/{id}/password", idOf(adminName))
                        .with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // Unknown user, and passwords that are too short or blank
        mvc.perform(put("/api/admin/users/{id}/password", 999_999_999L)
                        .with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"temporaryPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"temporaryPassword\":\"            \"}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions loginAttempt(Client client, RequestPostProcessor extra, String username, String password)
            throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(client).with(extra)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }
}
