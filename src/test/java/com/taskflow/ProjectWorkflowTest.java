package com.taskflow;

import com.jayway.jsonpath.JsonPath;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * running). Flyway builds the schema from your real migrations, so these tests also prove they apply
 * cleanly and match the entities. Your dev database is never touched.
 *
 * Authentication works like a browser: log in, keep the session cookie, and send the XSRF-TOKEN cookie
 * back in the X-XSRF-TOKEN header on every request. See the Client class below.
 *
 * Every test makes its own uniquely named users, so tests can't interfere with each other.
 */
@SpringBootTest
class ProjectWorkflowTest {

    private static final String PASSWORD = "test-password-123";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

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

    /**
     * Stands in for a browser: holds the session cookie and the CSRF token, and attaches both to every
     * request it is used with, e.g. mvc.perform(post("/api/projects").with(client)...).
     */
    private static final class Client implements RequestPostProcessor {
        private final String csrfToken;
        private MockHttpSession session; // set after login

        private Client(String csrfToken) {
            this.csrfToken = csrfToken;
        }

        @Override
        public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
            request.addHeader(CSRF_HEADER, csrfToken);
            request.setCookies(new Cookie(CSRF_COOKIE, csrfToken));
            if (session != null) {
                request.setSession(session);
            }
            return request;
        }
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

        Client adminClient = login(admin);
        Client hannahClient = login(hannah);
        Client samClient = login(sam);
        Client jordanClient = login(jordan);

        // Arrange: project managed by Hannah, with Sam and Jordan as members, and a task created by Sam
        MvcResult project = mvc.perform(post("/api/projects")
                        .with(adminClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Files project\",\"managerId\":%d}".formatted(idOf(hannah))))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = jsonLong(project, "$.id");

        for (long userId : new long[]{samId, jordanId}) {
            mvc.perform(put("/api/projects/{pid}/members/{uid}", projectId, userId).with(hannahClient))
                    .andExpect(status().isOk());
        }

        MvcResult task = mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .with(samClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Task with a file\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = jsonLong(task, "$.id");

        // Act: Sam uploads a file
        byte[] content = "quarterly numbers, confidential".getBytes(StandardCharsets.UTF_8);
        MvcResult upload = mvc.perform(multipart("/api/tasks/{id}/attachments", taskId)
                        .file(new MockMultipartFile("file", "numbers.txt", "text/plain", content))
                        .with(samClient))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("numbers.txt"))
                .andExpect(jsonPath("$.uploadedBy.username").value(sam))
                .andReturn();
        long attachmentId = jsonLong(upload, "$.id");

        // Assert: Jordan (another member) sees nothing and can't download it
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).with(jordanClient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/attachments/{id}", attachmentId).with(jordanClient))
                .andExpect(status().isNotFound());

        // Assert: Hannah (project manager) sees it and downloads the exact bytes
        mvc.perform(get("/api/tasks/{id}/attachments", taskId).with(hannahClient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("numbers.txt"))
                .andExpect(jsonPath("$[0].uploadedBy.username").value(sam));
        byte[] downloadedByHannah = mvc.perform(get("/api/attachments/{id}", attachmentId).with(hannahClient))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedByHannah);

        // Assert: Sam (the uploader) can still get his own file
        byte[] downloadedBySam = mvc.perform(get("/api/attachments/{id}", attachmentId).with(samClient))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloadedBySam);

        // Assert: Jordan also can't delete it (and it survives the attempt)
        mvc.perform(delete("/api/attachments/{id}", attachmentId).with(jordanClient))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/{id}", attachmentId).with(hannahClient))
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

    /** Asks the server for a CSRF token, exactly as a script would before its first write. */
    private String fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertNotNull(cookie, "server should issue an XSRF-TOKEN cookie");
        return cookie.getValue();
    }

    /** Logs in through the real endpoint and returns a client that carries the new session. */
    private Client login(String username) throws Exception {
        Client client = new Client(fetchCsrfToken());
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();
        client.session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(client.session, "login should start a session");
        return client;
    }

    private static long jsonLong(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }
}
