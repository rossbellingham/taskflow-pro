package com.taskflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may edit and delete tasks, how task links behave, and what happens when a member is removed. */
class TaskRulesTest extends IntegrationTestBase {

    @Test
    void taskEditAndDeleteRules() throws Exception {
        Scenario s = newScenario();
        long taskId = createTask(s.sam(), s.projectId(), "Original title");

        // Jordan is a member but neither the creator nor the assignee: can't edit
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());

        // Sam (the creator) can edit
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited by Sam\",\"status\":\"IN_PROGRESS\",\"priority\":\"URGENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited by Sam"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.priority").value("URGENT"));

        // Hannah (project manager) can edit too
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited by Hannah\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited by Hannah"));

        // An assignee who didn't create the task can edit it: assign it to Jordan, then he may
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited by Hannah\",\"assigneeId\":%d}".formatted(s.jordanId())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited by Jordan\",\"assigneeId\":%d}".formatted(s.jordanId())))
                .andExpect(status().isOk());

        // Assigning someone outside the project is refused
        mvc.perform(put("/api/tasks/{id}", taskId)
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"assigneeId\":999999999}"))
                .andExpect(status().isBadRequest());

        // Deleting is manager/admin only: the creator Sam is refused, Hannah succeeds
        mvc.perform(delete("/api/tasks/{id}", taskId).with(s.sam())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tasks/{id}", taskId).with(s.hannah())).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/{id}", taskId).with(s.sam())).andExpect(status().isNotFound());
    }

    @Test
    void taskLinks_areTyped_directional_andValidated() throws Exception {
        Scenario s = newScenario();
        long taskA = createTask(s.sam(), s.projectId(), "Task A");
        long taskB = createTask(s.sam(), s.projectId(), "Task B");

        // A BLOCKS B
        MvcResult link = mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"BLOCKS\"}".formatted(taskB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BLOCKS"))
                .andExpect(jsonPath("$.direction").value("OUTGOING"))
                .andExpect(jsonPath("$.otherTaskId").value((int) taskB))
                .andReturn();
        long linkId = jsonLong(link, "$.id");

        // Seen from B, the same link is INCOMING (B is blocked by A)
        mvc.perform(get("/api/tasks/{id}", taskB).with(s.jordan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links[0].type").value("BLOCKS"))
                .andExpect(jsonPath("$.links[0].direction").value("INCOMING"))
                .andExpect(jsonPath("$.links[0].otherTaskId").value((int) taskA));

        // The exact same link again is a conflict
        mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"BLOCKS\"}".formatted(taskB)))
                .andExpect(status().isConflict());

        // RELATES_TO counts as two-way: A->B is accepted, then B->A is a duplicate
        mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"RELATES_TO\"}".formatted(taskB)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tasks/{id}/links", taskB)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"RELATES_TO\"}".formatted(taskA)))
                .andExpect(status().isConflict());

        // A task can't link to itself
        mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"RELATES_TO\"}".formatted(taskA)))
                .andExpect(status().isBadRequest());

        // Nor to a task in a different project
        long otherProject = jsonLong(mvc.perform(post("/api/projects")
                        .with(s.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other project\",\"managerId\":%d}".formatted(s.hannahId())))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
        long foreignTask = createTask(s.hannah(), otherProject, "Foreign task");
        mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"DUPLICATES\"}".formatted(foreignTask)))
                .andExpect(status().isBadRequest());

        // Jordan didn't create or get assigned task A, so he can't change its links
        mvc.perform(post("/api/tasks/{id}/links", taskA)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTaskId\":%d,\"type\":\"DUPLICATES\"}".formatted(taskB)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tasks/{id}/links/{linkId}", taskA, linkId).with(s.jordan()))
                .andExpect(status().isForbidden());

        // Sam removes the BLOCKS link; only the RELATES_TO link is left on task B
        mvc.perform(delete("/api/tasks/{id}/links/{linkId}", taskA, linkId).with(s.sam()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/{id}", taskB).with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].type").value("RELATES_TO"));
    }

    @Test
    void removingAMember_unassignsTheirTasks_andEndsTheirAccess() throws Exception {
        Scenario s = newScenario();

        // Sam creates two tasks: one assigned to Jordan, one assigned to himself
        long jordansTask = jsonLong(mvc.perform(post("/api/projects/{id}/tasks", s.projectId())
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"For Jordan\",\"assigneeId\":%d}".formatted(s.jordanId())))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
        long samsTask = jsonLong(mvc.perform(post("/api/projects/{id}/tasks", s.projectId())
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"For Sam\",\"assigneeId\":%d}".formatted(s.samId())))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");

        // A member can't remove another member; the manager can
        mvc.perform(delete("/api/projects/{pid}/members/{uid}", s.projectId(), s.jordanId()).with(s.sam()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/projects/{pid}/members/{uid}", s.projectId(), s.jordanId()).with(s.hannah()))
                .andExpect(status().isNoContent());

        // Jordan's task is now unassigned; Sam's task is untouched
        mvc.perform(get("/api/tasks/{id}", jordansTask).with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.assignee").doesNotExist());
        mvc.perform(get("/api/tasks/{id}", samsTask).with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.assignee.username").value(s.samName()));

        // Jordan can no longer see the project or its tasks
        mvc.perform(get("/api/projects/{id}", s.projectId()).with(s.jordan())).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/{id}", jordansTask).with(s.jordan())).andExpect(status().isNotFound());

        // Removing someone who isn't a member is a 404
        mvc.perform(delete("/api/projects/{pid}/members/{uid}", s.projectId(), s.jordanId()).with(s.hannah()))
                .andExpect(status().isNotFound());
    }
}
