package com.taskflow;

import com.taskflow.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The core workflow: projects, membership, tasks, attachments, plus the session and role rules. */
class ProjectWorkflowTest extends IntegrationTestBase {

    // =====================================================================================
    // Test 1: admin creates a project with Hannah as manager; Hannah adds Sam; Sam creates a task
    // =====================================================================================

    @Test
    void adminCreatesProject_managerAddsMember_memberCreatesTask() throws Exception {
        String hannah = createUserInDatabase("hannah", Role.ROLE_MANAGER);
        String admin = createUserInDatabase("admin", Role.ROLE_ADMIN);
        long hannahId = idOf(hannah);

        // Sam signs up through the real API (so he starts as ROLE_EMPLOYEE)
        String sam = unique("sam");
        long samId = registerViaApi(sam);

        Client adminClient = login(admin);
        Client hannahClient = login(hannah);
        Client samClient = login(sam);

        // Admin creates the project and names Hannah as manager
        MvcResult created = mvc.perform(post("/api/projects")
                        .with(adminClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Website relaunch","description":"Q4 project","managerId":%d}
                                """.formatted(hannahId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manager.username").value(hannah))
                .andExpect(jsonPath("$.members").isEmpty())
                .andReturn();
        long projectId = jsonLong(created, "$.id");

        // Sam is not in the project yet, so he can't even see it (404, not 403)
        mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .with(samClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Too early\"}"))
                .andExpect(status().isNotFound());

        // Hannah (the project manager) adds Sam
        mvc.perform(put("/api/projects/{pid}/members/{uid}", projectId, samId).with(hannahClient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].username").value(sam));

        // Now Sam can create a task
        MvcResult task = mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .with(samClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Draft homepage copy","description":"First pass",
                                 "priority":"HIGH","dueDate":"2030-01-31","assigneeId":%d}
                                """.formatted(samId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Draft homepage copy"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.dueDate").value("2030-01-31"))
                .andExpect(jsonPath("$.createdBy.username").value(sam))
                .andExpect(jsonPath("$.assignee.username").value(sam))
                .andReturn();
        long taskId = jsonLong(task, "$.id");

        // And it appears in the project's task list for Sam and for Hannah
        for (Client client : new Client[]{samClient, hannahClient}) {
            mvc.perform(get("/api/projects/{id}/tasks", projectId).with(client))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value((int) taskId))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    // =====================================================================================
    // Test 2: Sam uploads a file; a second member can't see it, Hannah can
    // =====================================================================================

    @Test
    void attachmentIsVisibleToUploaderAndManager_butNotToOtherMembers() throws Exception {
        Scenario s = newScenario();
        long taskId = createTask(s.sam(), s.projectId(), "Task with a file");

        // Act: Sam uploads a file
        byte[] content = "quarterly numbers, confidential".getBytes(StandardCharsets.UTF_8);
        MvcResult upload = mvc.perform(multipart("/api/tasks/{id}/attachments", taskId)
                        .file(new MockMultipartFile("file", "numbers.txt", "text/plain", content))
                        .with(s.sam()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("numbers.txt"))
                .andExpect(jsonPath("$.uploadedBy.username").value(s.samName()))
                .andReturn();
        long attachmentId = jsonLong(upload, "$.id");

        // Assert: Jordan (another member) sees nothing and can't download it
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).with(s.jordan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/attachments/{id}", attachmentId).with(s.jordan()))
                .andExpect(status().isNotFound());

        // Assert: Hannah (project manager) sees it and downloads the exact bytes
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("numbers.txt"))
                .andExpect(jsonPath("$[0].uploadedBy.username").value(s.samName()));
        byte[] downloadedByHannah = mvc.perform(get("/api/attachments/{id}", attachmentId).with(s.hannah()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedByHannah);

        // Assert: Sam (the uploader) can still get his own file
        byte[] downloadedBySam = mvc.perform(get("/api/attachments/{id}", attachmentId).with(s.sam()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedBySam);

        // Assert: Jordan also can't delete it (and it survives the attempt)
        mvc.perform(delete("/api/attachments/{id}", attachmentId).with(s.jordan()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/{id}", attachmentId).with(s.hannah()))
                .andExpect(status().isOk());
    }

    // =====================================================================================
    // Test 3: the session and CSRF rules themselves
    // =====================================================================================

    @Test
    void sessionRules_loginRequired_csrfRequiredForWrites_logoutEndsSession() throws Exception {
        String user = createUserInDatabase("admin", Role.ROLE_ADMIN);
        Client client = login(user);

        // Without logging in, protected endpoints answer 401
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());

        // A logged-in session with NO CSRF token can read but is refused on writes (403)
        mvc.perform(get("/api/projects").session(client.session)).andExpect(status().isOk());
        mvc.perform(post("/api/projects")
                        .session(client.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No token\",\"managerId\":1}"))
                .andExpect(status().isForbidden());

        // Wrong password is a generic 401
        mvc.perform(post("/api/auth/login")
                        .with(new Client(fetchCsrfToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"wrong-password-1\"}".formatted(user)))
                .andExpect(status().isUnauthorized());

        // Logout ends the session: the same cookie no longer works
        mvc.perform(post("/api/auth/logout").with(client)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(client)).andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // Test 4: changing someone's role applies immediately, even to a session that already exists
    // =====================================================================================

    @Test
    void demotedAdminLosesAccessWithoutLoggingInAgain() throws Exception {
        String boss = createUserInDatabase("admin", Role.ROLE_ADMIN);
        String other = createUserInDatabase("admin", Role.ROLE_ADMIN);
        Client bossClient = login(boss);
        Client otherClient = login(other);

        mvc.perform(get("/api/admin/users").with(otherClient)).andExpect(status().isOk());

        mvc.perform(put("/api/admin/users/{id}/roles", idOf(other))
                        .with(bossClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ROLE_EMPLOYEE\"]}"))
                .andExpect(status().isOk());

        // Same session, no new login: the demotion is already in force
        mvc.perform(get("/api/admin/users").with(otherClient)).andExpect(status().isForbidden());
    }
}
