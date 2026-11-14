package com.taskflow;

import com.taskflow.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing and deleting projects, task comments, and pagination. */
class ProjectAndCommentTest extends IntegrationTestBase {

    @Test
    void projectEditing_managerRenames_onlyAdminChangesManager() throws Exception {
        Scenario s = newScenario();
        String secondManager = createUserInDatabase("manager2", Role.ROLE_MANAGER);
        long secondManagerId = idOf(secondManager);

        // The project manager can rename it
        mvc.perform(put("/api/projects/{id}", s.projectId())
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"description\":\"New description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.manager.username").value(s.hannahName()));

        // A plain member can't
        mvc.perform(put("/api/projects/{id}", s.projectId())
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sam was here\"}"))
                .andExpect(status().isForbidden());

        // The manager can't hand the project to someone else...
        mvc.perform(put("/api/projects/{id}", s.projectId())
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"managerId\":%d}".formatted(secondManagerId)))
                .andExpect(status().isForbidden());

        // ...and the admin can't pick someone without the manager role
        mvc.perform(put("/api/projects/{id}", s.projectId())
                        .with(s.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"managerId\":%d}".formatted(s.samId())))
                .andExpect(status().isBadRequest());

        // The admin can pick another manager; Hannah then loses access altogether
        mvc.perform(put("/api/projects/{id}", s.projectId())
                        .with(s.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"managerId\":%d}".formatted(secondManagerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.username").value(secondManager));
        mvc.perform(get("/api/projects/{id}", s.projectId()).with(s.hannah()))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectDeletion_isAdminOnly_andRemovesTasksAndFiles() throws Exception {
        Scenario s = newScenario();
        long taskId = createTask(s.sam(), s.projectId(), "Task that will vanish");

        long filesBefore = storedFileCount();
        mvc.perform(multipart("/api/tasks/{id}/attachments", taskId)
                        .file(new MockMultipartFile("file", "doomed.txt", "text/plain",
                                "bye".getBytes(StandardCharsets.UTF_8)))
                        .with(s.sam()))
                .andExpect(status().isCreated());
        assertEquals(filesBefore + 1, storedFileCount(), "upload should create one file on disk");

        // Even the project's own manager can't delete it
        mvc.perform(delete("/api/projects/{id}", s.projectId()).with(s.hannah()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/projects/{id}", s.projectId()).with(s.hannah())).andExpect(status().isOk());

        // The admin can: the project, its tasks and the file on disk are all gone
        mvc.perform(delete("/api/projects/{id}", s.projectId()).with(s.admin()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/{id}", s.projectId()).with(s.admin())).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/{id}", taskId).with(s.sam())).andExpect(status().isNotFound());
        assertEquals(filesBefore, storedFileCount(), "the attachment file should be deleted from disk");
    }

    @Test
    void comments_participantsWrite_authorEdits_managerOrAuthorDeletes() throws Exception {
        Scenario s = newScenario();
        String outsider = unique("outsider");
        registerViaApi(outsider);
        Client outsiderClient = login(outsider);
        long taskId = createTask(s.sam(), s.projectId(), "Discussed task");

        // Two members each leave a comment
        long samsComment = jsonLong(mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Sam's comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.username").value(s.samName()))
                .andExpect(jsonPath("$.body").value("Sam's comment"))
                .andReturn(), "$.id");
        long jordansComment = jsonLong(mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Jordan's comment\"}"))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");

        // A blank comment is rejected; an outsider can neither read nor write
        mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/{id}/comments", taskId).with(outsiderClient)).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(outsiderClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Let me in\"}"))
                .andExpect(status().isNotFound());

        // Hannah sees both, oldest first
        mvc.perform(get("/api/tasks/{id}/comments", taskId).with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].body").value("Sam's comment"))
                .andExpect(jsonPath("$.content[1].body").value("Jordan's comment"));

        // Only the author can edit (not another member, not even the manager)
        mvc.perform(put("/api/comments/{id}", samsComment)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Jordan rewrote this\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/comments/{id}", samsComment)
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hannah rewrote this\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/comments/{id}", samsComment)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Sam's edited comment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Sam's edited comment"));

        // Delete: another member can't; the manager can delete anyone's; an author can delete their own
        mvc.perform(delete("/api/comments/{id}", samsComment).with(s.jordan())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/comments/{id}", samsComment).with(s.hannah())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/comments/{id}", jordansComment).with(s.jordan())).andExpect(status().isNoContent());

        mvc.perform(get("/api/tasks/{id}/comments", taskId).with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void listEndpointsArePaginated() throws Exception {
        Scenario s = newScenario();
        for (int i = 1; i <= 3; i++) {
            createTask(s.sam(), s.projectId(), "Task " + i);
        }

        // Page 0 of size 2: two items, with totals describing the whole list
        mvc.perform(get("/api/projects/{id}/tasks", s.projectId()).param("size", "2").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Task 1"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Page 1 holds the remaining item
        mvc.perform(get("/api/projects/{id}/tasks", s.projectId())
                        .param("size", "2").param("page", "1").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Task 3"));

        // An oversized page request is capped at 100, and the status filter still works
        mvc.perform(get("/api/projects/{id}/tasks", s.projectId()).param("size", "1000").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/api/projects/{id}/tasks", s.projectId()).param("status", "DONE").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Projects and admin user lists use the same shape
        mvc.perform(get("/api/projects").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value((int) s.projectId()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/users").param("size", "2").with(s.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").isNumber());
    }
}
