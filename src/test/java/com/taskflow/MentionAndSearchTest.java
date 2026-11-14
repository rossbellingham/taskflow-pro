package com.taskflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @mentions in comments (with the "comments that mention me" inbox), and task search and sorting. */
class MentionAndSearchTest extends IntegrationTestBase {

    // =====================================================================================
    // Mentions
    // =====================================================================================

    @Test
    void mentions_tagOnlyParticipants_ignoreEveryoneElse_andFillTheInbox() throws Exception {
        Scenario s = newScenario();
        String outsider = unique("outsider");
        registerViaApi(outsider);
        Client outsiderClient = login(outsider);
        long taskId = createTask(s.sam(), s.projectId(), "Task with mentions");

        // Tags Jordan and Hannah. Ignored: an outsider, an email address, an unknown name, and Sam himself.
        // "Thanks @name." ends with a full stop that must not break the match.
        String body = "@%s can you check this? cc @%s, mail foo@example.com, ping @nobody-here, me @%s. Thanks @%s."
                .formatted(s.jordanName(), outsider, s.samName(), s.hannahName());
        MvcResult created = mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"%s\"}".formatted(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mentions.length()").value(2))
                .andExpect(jsonPath("$.mentions[?(@.username=='%s')]".formatted(s.jordanName())).exists())
                .andExpect(jsonPath("$.mentions[?(@.username=='%s')]".formatted(s.hannahName())).exists())
                .andExpect(jsonPath("$.mentions[?(@.username=='%s')]".formatted(outsider)).doesNotExist())
                .andExpect(jsonPath("$.mentions[?(@.username=='%s')]".formatted(s.samName())).doesNotExist())
                .andReturn();
        long commentId = jsonLong(created, "$.id");

        // The tagged people find it in their inbox; Sam and the outsider have nothing
        mvc.perform(get("/api/mentions").with(s.jordan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].commentId").value((int) commentId))
                .andExpect(jsonPath("$.content[0].taskTitle").value("Task with mentions"))
                .andExpect(jsonPath("$.content[0].projectId").value((int) s.projectId()))
                .andExpect(jsonPath("$.content[0].author.username").value(s.samName()));
        mvc.perform(get("/api/mentions").with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/mentions").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/mentions").with(outsiderClient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The inbox is newest first: Jordan and then Hannah each tag Sam
        String jordansPing = "@%s please review".formatted(s.samName());
        String hannahsPing = "@%s second ping".formatted(s.samName());
        mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.jordan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"%s\"}".formatted(jordansPing)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tasks/{id}/comments", taskId)
                        .with(s.hannah())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"%s\"}".formatted(hannahsPing)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/mentions").with(s.sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].body").value(hannahsPing))
                .andExpect(jsonPath("$.content[1].body").value(jordansPing));

        // Someone removed from the project stops seeing mentions from it
        mvc.perform(delete("/api/projects/{pid}/members/{uid}", s.projectId(), s.jordanId()).with(s.hannah()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/mentions").with(s.jordan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Editing the comment recalculates who is tagged: no tags left, so Hannah's inbox empties too
        mvc.perform(put("/api/comments/{id}", commentId)
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"No tags any more\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentions").isEmpty());
        mvc.perform(get("/api/mentions").with(s.hannah()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The inbox needs a login
        mvc.perform(get("/api/mentions")).andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // Search, filtering and sorting
    // =====================================================================================

    @Test
    void taskSearch_filtersCombine_andSortingIsByImportanceNotAlphabet() throws Exception {
        Scenario s = newScenario();

        // Four tasks, deliberately created in an order that matches none of the sort orders below
        createTaskJson(s, """
                {"title":"Fix login bug","description":"Users cannot sign in","priority":"HIGH",
                 "status":"TODO","dueDate":"2030-03-01","assigneeId":%d}""".formatted(s.samId()));
        createTaskJson(s, """
                {"title":"Write docs","priority":"LOW","status":"DONE",
                 "dueDate":"2030-01-01","assigneeId":%d}""".formatted(s.jordanId()));
        createTaskJson(s, """
                {"title":"Deploy to staging","description":"Needs the LOGIN fix first","priority":"URGENT",
                 "status":"IN_PROGRESS","dueDate":"2030-02-01"}""");
        createTaskJson(s, """
                {"title":"Refactor billing","priority":"MEDIUM","status":"IN_REVIEW",
                 "assigneeId":%d}""".formatted(s.samId()));

        // Default order is by id (creation order)
        titles(search(s, s.sam()), "Fix login bug", "Write docs", "Deploy to staging", "Refactor billing");

        // Text search: title or description, any case, matches anywhere
        titles(search(s, s.sam(), "q", "login"), "Fix login bug", "Deploy to staging");
        titles(search(s, s.sam(), "q", "BILLING"), "Refactor billing");
        // % and _ are literal characters, not wildcards
        search(s, s.sam(), "q", "%").andExpect(jsonPath("$.totalElements").value(0));
        search(s, s.sam(), "q", "_").andExpect(jsonPath("$.totalElements").value(0));

        // Single filters
        titles(search(s, s.sam(), "status", "DONE"), "Write docs");
        titles(search(s, s.sam(), "priority", "URGENT"), "Deploy to staging");
        titles(search(s, s.sam(), "assigneeId", String.valueOf(s.samId())), "Fix login bug", "Refactor billing");
        titles(search(s, s.sam(), "unassigned", "true"), "Deploy to staging");

        // Due dates are inclusive, and tasks with no due date never match a date filter
        titles(search(s, s.sam(), "dueBefore", "2030-02-01"), "Write docs", "Deploy to staging");
        titles(search(s, s.sam(), "dueAfter", "2030-02-01"), "Fix login bug", "Deploy to staging");
        titles(search(s, s.sam(), "dueAfter", "2030-01-15", "dueBefore", "2030-02-15"), "Deploy to staging");

        // Filters combine: everything given must match
        titles(search(s, s.sam(), "assigneeId", String.valueOf(s.samId()), "sort", "priority,desc"),
                "Fix login bug", "Refactor billing");
        search(s, s.sam(), "q", "login", "status", "DONE").andExpect(jsonPath("$.totalElements").value(0));

        // Priority and status sort by importance, not alphabetically (alphabetical would put LOW after HIGH)
        titles(search(s, s.sam(), "sort", "priority,desc"),
                "Deploy to staging", "Fix login bug", "Refactor billing", "Write docs");
        titles(search(s, s.sam(), "sort", "priority,asc"),
                "Write docs", "Refactor billing", "Fix login bug", "Deploy to staging");
        titles(search(s, s.sam(), "sort", "status,asc"),
                "Fix login bug", "Deploy to staging", "Refactor billing", "Write docs");

        // Other fields; with no due date, a task sorts last when ascending
        titles(search(s, s.sam(), "sort", "dueDate,asc"),
                "Write docs", "Deploy to staging", "Fix login bug", "Refactor billing");
        titles(search(s, s.sam(), "sort", "title,desc"),
                "Write docs", "Refactor billing", "Fix login bug", "Deploy to staging");

        // Sorting works together with pagination
        search(s, s.sam(), "sort", "priority,desc", "size", "2", "page", "1")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].title").value("Refactor billing"))
                .andExpect(jsonPath("$.content[1].title").value("Write docs"));

        // Bad input is a 400, not a crash or a hole: unknown sort field, bad direction, long text, bad date
        search(s, s.sam(), "sort", "password,asc").andExpect(status().isBadRequest());
        search(s, s.sam(), "sort", "title,sideways").andExpect(status().isBadRequest());
        search(s, s.sam(), "q", "x".repeat(101)).andExpect(status().isBadRequest());
        search(s, s.sam(), "dueBefore", "tomorrow").andExpect(status().isBadRequest());
    }

    // ------------------------------- helpers -------------------------------

    private long createTaskJson(Scenario s, String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects/{id}/tasks", s.projectId())
                        .with(s.sam())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "$.id");
    }

    /** GET the project's task list with extra query parameters given as name, value, name, value... */
    private ResultActions search(Scenario s, Client client, String... nameValuePairs) throws Exception {
        var request = get("/api/projects/{id}/tasks", s.projectId()).with(client);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request);
    }

    /** Asserts the page holds exactly these task titles, in this order. */
    private void titles(ResultActions result, String... expected) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(expected.length));
        for (int i = 0; i < expected.length; i++) {
            result.andExpect(jsonPath("$.content[%d].title".formatted(i)).value(expected[i]));
        }
    }
}
