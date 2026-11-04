package com.taskflow;

import com.jayway.jsonpath.JsonPath;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests against a real, throwaway PostgreSQL started by Testcontainers (Docker must be
 * running). Flyway builds the schema from your real migrations, so these tests also prove V1-V3 apply
 * cleanly and match the entities. Your dev database is never touched.
 *
 * Every test makes its own uniquely named users, so tests can't interfere with each other.
 */
@SpringBootTest(properties = {
        // 48 bytes, base64. Test-only key.
        "app.jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9uLTEyMzQ1Ng=="
})
class ProjectWorkflowTest {

    private static final String PASSWORD = "test-password-123";
    private static final String AUTH = "Authorization";

    private static final JdbcDatabaseContainer<?> POSTGRES = new PostgreSQLContainer("postgres:16");
    private static final Path UPLOAD_DIR;

    static {
        POSTGRES.start();
        try {
            UPLOAD_DIR = Files.createTempDirectory("taskflow-test-uploads");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.directory", UPLOAD_DIR::toString);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

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

        String adminToken = login(admin);
        String hannahToken = login(hannah);
        String samToken = login(sam);

        // Admin creates the project and names Hannah as manager
        MvcResult created = mvc.perform(post("/api/projects")
                        .header(AUTH, bearer(adminToken))
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
                        .header(AUTH, bearer(samToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Too early\"}"))
                .andExpect(status().isNotFound());

        // Hannah (the project manager) adds Sam
        mvc.perform(put("/api/projects/{pid}/members/{uid}", projectId, samId)
                        .header(AUTH, bearer(hannahToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].username").value(sam));

        // Now Sam can create a task
        MvcResult task = mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .header(AUTH, bearer(samToken))
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
        for (String token : new String[]{samToken, hannahToken}) {
            mvc.perform(get("/api/projects/{id}/tasks", projectId).header(AUTH, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value((int) taskId));
        }
    }

    // =====================================================================================
    // Test 2: Sam uploads a file; a second member can't see it, Hannah can
    // =====================================================================================

    @Test
    void attachmentIsVisibleToUploaderAndManager_butNotToOtherMembers() throws Exception {
        String hannah = createUserInDatabase("hannah", Role.ROLE_MANAGER);
        String admin = createUserInDatabase("admin", Role.ROLE_ADMIN);

        String sam = unique("sam");
        String jordan = unique("jordan");
        long samId = registerViaApi(sam);
        long jordanId = registerViaApi(jordan);

        String adminToken = login(admin);
        String hannahToken = login(hannah);
        String samToken = login(sam);
        String jordanToken = login(jordan);

        // Arrange: project managed by Hannah, with Sam and Jordan as members, and a task created by Sam
        MvcResult project = mvc.perform(post("/api/projects")
                        .header(AUTH, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Files project\",\"managerId\":%d}".formatted(idOf(hannah))))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = jsonLong(project, "$.id");

        for (long userId : new long[]{samId, jordanId}) {
            mvc.perform(put("/api/projects/{pid}/members/{uid}", projectId, userId)
                            .header(AUTH, bearer(hannahToken)))
                    .andExpect(status().isOk());
        }

        MvcResult task = mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .header(AUTH, bearer(samToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Task with a file\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = jsonLong(task, "$.id");

        // Act: Sam uploads a file
        byte[] content = "quarterly numbers, confidential".getBytes(StandardCharsets.UTF_8);
        MvcResult upload = mvc.perform(multipart("/api/tasks/{id}/attachments", taskId)
                        .file(new MockMultipartFile("file", "numbers.txt", "text/plain", content))
                        .header(AUTH, bearer(samToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("numbers.txt"))
                .andExpect(jsonPath("$.uploadedBy.username").value(sam))
                .andReturn();
        long attachmentId = jsonLong(upload, "$.id");

        // Assert: Jordan (another member) sees nothing and can't download it
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).header(AUTH, bearer(jordanToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/attachments/{id}", attachmentId).header(AUTH, bearer(jordanToken)))
                .andExpect(status().isNotFound());

        // Assert: Hannah (project manager) sees it and downloads the exact bytes
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).header(AUTH, bearer(hannahToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("numbers.txt"))
                .andExpect(jsonPath("$[0].uploadedBy.username").value(sam));
        byte[] downloadedByHannah = mvc.perform(get("/api/attachments/{id}", attachmentId)
                        .header(AUTH, bearer(hannahToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedByHannah);

        // Assert: Sam (the uploader) can still get his own file
        byte[] downloadedBySam = mvc.perform(get("/api/attachments/{id}", attachmentId)
                        .header(AUTH, bearer(samToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedBySam);

        // Assert: Jordan also can't delete it (and it survives the attempt)
        mvc.perform(delete("/api/attachments/{id}", attachmentId).header(AUTH, bearer(jordanToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/{id}", attachmentId).header(AUTH, bearer(hannahToken)))
                .andExpect(status().isOk());
    }

    // ------------------------------- helpers -------------------------------

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a user straight in the database with the given role (registration can only make employees). */
    private String createUserInDatabase(String prefix, Role role) {
        String username = unique(prefix);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.getRoles().add(role);
        userRepository.saveAndFlush(user);
        return username;
    }

    private long idOf(String username) {
        return userRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    /** Registers through the real endpoint and returns the new user's id. */
    private long registerViaApi(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.test","password":"%s"}
                                """.formatted(username, username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "$.id");
    }

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        assertEquals("Bearer", JsonPath.<String>read(result.getResponse().getContentAsString(), "$.tokenType"));
        return token;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static long jsonLong(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }
}