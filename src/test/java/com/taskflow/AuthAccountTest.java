package com.taskflow;

import com.taskflow.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login rate limiting and changing your own password. */
class AuthAccountTest extends IntegrationTestBase {

    @Test
    void tooManyFailedLogins_lockTheAccount_evenForTheCorrectPassword() throws Exception {
        String user = createUserInDatabase("locked", Role.ROLE_EMPLOYEE);
        Client client = new Client(fetchCsrfToken());
        // A fixed fake address, so this test's failures don't count against other tests' IP limit
        RequestPostProcessor fromOneAddress = request -> {
            request.setRemoteAddr("203.0.113.50");
            return request;
        };

        // Five wrong guesses are each a plain 401
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .with(client).with(fromOneAddress)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"%s\",\"password\":\"wrong-guess-%d\"}".formatted(user, i)))
                    .andExpect(status().isUnauthorized());
        }

        // The sixth attempt is refused (429) even though this time the password is right
        mvc.perform(post("/api/auth/login")
                        .with(client).with(fromOneAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void changePassword_needsTheCurrentPassword_andTheNewOneThenWorks() throws Exception {
        String user = createUserInDatabase("changer", Role.ROLE_EMPLOYEE);
        Client client = login(user);
        String newPassword = "a-brand-new-password-1";

        // Wrong current password: rejected
        mvc.perform(put("/api/auth/password")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-my-password\",\"newPassword\":\"%s\"}".formatted(newPassword)))
                .andExpect(status().isBadRequest());

        // Same password again: rejected
        mvc.perform(put("/api/auth/password")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, PASSWORD)))
                .andExpect(status().isBadRequest());

        // Too-short new password: rejected by validation
        mvc.perform(put("/api/auth/password")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(PASSWORD)))
                .andExpect(status().isBadRequest());

        // Correct request: accepted, and the session keeps working
        mvc.perform(put("/api/auth/password")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, newPassword)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").with(client)).andExpect(status().isOk());

        // The old password no longer logs in; the new one does
        Client fresh = new Client(fetchCsrfToken());
        mvc.perform(post("/api/auth/login")
                        .with(fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")
                        .with(fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, newPassword)))
                .andExpect(status().isOk());
    }
}
