package com.taskflow;

import com.jayway.jsonpath.JsonPath;
import com.taskflow.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Admin password reset, the forced change that follows it, and ending sessions when a password changes. */
class AdminPasswordResetTest extends IntegrationTestBase {

    private static final String TEMP_PASSWORD = "temporary-pass-1";
    private static final String NEW_PASSWORD = "my-own-new-password-1";

    @Test
    void adminReset_endsTheUsersSessions_clearsLockout_andForcesAPasswordChange() throws Exception {
        Client admin = login(createUserInDatabase("admin", Role.ROLE_ADMIN));
        String victim = createUserInDatabase("forgetful", Role.ROLE_EMPLOYEE);
        long victimId = idOf(victim);

        // The user is logged in somewhere, and has also been locked out by wrong guesses elsewhere
        Client oldSession = login(victim);
        mvc.perform(get("/api/auth/me").with(oldSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        RequestPostProcessor fromOneAddress = request -> {
            request.setRemoteAddr("203.0.113.61"); // keeps these failures off other tests' IP counters
            return request;
        };
        Client guesser = new Client(fetchCsrfToken());
        for (int i = 0; i < 5; i++) {
            loginAttempt(guesser, fromOneAddress, victim, "wrong-guess-" + i).andExpect(status().isUnauthorized());
        }
        loginAttempt(guesser, fromOneAddress, victim, PASSWORD).andExpect(status().isTooManyRequests());

        // The admin resets it
        mvc.perform(put("/api/admin/users/{id}/password", victimId)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"%s\"}".formatted(TEMP_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(victim))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist()); // only returned when generated

        // The user is logged out immediately: their existing session no longer works
        mvc.perform(get("/api/auth/me").with(oldSession)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects").with(oldSession)).andExpect(status().isUnauthorized());

        // The old password is gone. The lockout was cleared, so the temporary password gets straight in
        loginAttempt(guesser, fromOneAddress, victim, PASSWORD).andExpect(status().isUnauthorized());
        Client session = loginWith(victim, TEMP_PASSWORD, true);

        // Until the password is changed, almost everything is refused with a clear, machine-readable code
        mvc.perform(get("/api/projects").with(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PUT /api/auth/password")));
        mvc.perform(post("/api/tasks/1/comments")
                        .with(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // ...but they can see who they are, and that a change is needed
        mvc.perform(get("/api/auth/me").with(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        // The temporary password can't be kept
        mvc.perform(put("/api/auth/password")
                        .with(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TEMP_PASSWORD, TEMP_PASSWORD)))
                .andExpect(status().isBadRequest());

        // Choosing a new password lifts the restriction on that same session
        mvc.perform(put("/api/auth/password")
                        .with(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TEMP_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/projects").with(session)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        // And the next login is normal
        loginWith(victim, NEW_PASSWORD, false);
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

        loginWith(victim, generated, true);
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

        // Not your own account (that needs the current password, via PUT /api/auth/password)
        mvc.perform(put("/api/admin/users/{id}/password", idOf(adminName))
                        .with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/auth/me").with(admin)).andExpect(status().isOk()); // still logged in

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

    @Test
    void changingYourOwnPassword_endsYourOtherSessions_butNotThisOne() throws Exception {
        String user = createUserInDatabase("twodevices", Role.ROLE_EMPLOYEE);
        Client laptop = login(user);
        Client phone = login(user);

        mvc.perform(put("/api/auth/password")
                        .with(laptop)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").with(laptop)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(phone)).andExpect(status().isUnauthorized());
    }

    // ------------------------------- helpers -------------------------------

    private org.springframework.test.web.servlet.ResultActions loginAttempt(
            Client client, RequestPostProcessor extra, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(client).with(extra)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    /** Logs in with a specific password and checks what the response says about a required change. */
    private Client loginWith(String username, String password, boolean changeRequired) throws Exception {
        Client client = new Client(fetchCsrfToken());
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(changeRequired))
                .andReturn();
        client.session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(client.session);
        return client;
    }
}
