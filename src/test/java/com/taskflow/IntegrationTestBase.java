package com.taskflow;

import com.jayway.jsonpath.JsonPath;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for the end-to-end tests: a real, throwaway PostgreSQL started by Testcontainers (Docker
 * must be running), the real Flyway migrations, and helpers that behave like a browser (log in, keep the
 * session cookie, echo the CSRF token).
 *
 * The container starts once and is shared by every test class. Each test makes its own uniquely named
 * users and projects, so tests can't interfere with each other. Your dev database is never touched.
 */
@SpringBootTest
abstract class IntegrationTestBase {

    protected static final String PASSWORD = "test-password-123";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final JdbcDatabaseContainer<?> POSTGRES = new PostgreSQLContainer("postgres:16");
    protected static final Path UPLOAD_DIR;

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
    protected UserRepository userRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * Stands in for a browser: holds the session cookie and the CSRF token, and attaches both to every
     * request it is used with, e.g. mvc.perform(post("/api/projects").with(client)...).
     */
    protected static final class Client implements RequestPostProcessor {
        final String csrfToken;
        MockHttpSession session; // set after login

        Client(String csrfToken) {
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

    /**
     * A ready-made project: Hannah (manager) runs it, Sam and Jordan (employees) are members, all logged in.
     * Hannah and the admin are created straight in the database; Sam and Jordan register through the API.
     */
    protected record Scenario(Client admin, Client hannah, Client sam, Client jordan,
                              long projectId, long hannahId, long samId, long jordanId,
                              String hannahName, String samName, String jordanName) {}

    protected Scenario newScenario() throws Exception {
        String adminName = createUserInDatabase("admin", Role.ROLE_ADMIN);
        String hannahName = createUserInDatabase("hannah", Role.ROLE_MANAGER);
        String samName = unique("sam");
        String jordanName = unique("jordan");
        long samId = registerViaApi(samName);
        long jordanId = registerViaApi(jordanName);
        long hannahId = idOf(hannahName);

        Client admin = login(adminName);
        Client hannah = login(hannahName);
        Client sam = login(samName);
        Client jordan = login(jordanName);

        MvcResult project = mvc.perform(post("/api/projects")
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Scenario project\",\"managerId\":%d}".formatted(hannahId)))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = jsonLong(project, "$.id");

        for (long userId : new long[]{samId, jordanId}) {
            mvc.perform(put("/api/projects/{pid}/members/{uid}", projectId, userId).with(hannah))
                    .andExpect(status().isOk());
        }
        return new Scenario(admin, hannah, sam, jordan, projectId, hannahId, samId, jordanId,
                hannahName, samName, jordanName);
    }

    /** Creates a task with just a title and returns its id. */
    protected long createTask(Client client, long projectId, String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects/{id}/tasks", projectId)
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn();
        return jsonLong(result, "$.id");
    }

    /** How many attachment files are currently stored on disk. */
    protected long storedFileCount() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.count();
        }
    }

    // ------------------------------- users and login -------------------------------

    protected String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a user straight in the database with the given role (registration can only make employees). */
    protected String createUserInDatabase(String prefix, Role role) {
        String username = unique(prefix);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.getRoles().add(role);
        userRepository.saveAndFlush(user);
        return username;
    }

    protected long idOf(String username) {
        return userRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    /** Registers through the real endpoint and returns the new user's id. */
    protected long registerViaApi(String username) throws Exception {
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
    protected String fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertNotNull(cookie, "server should issue an XSRF-TOKEN cookie");
        return cookie.getValue();
    }

    /** Logs in through the real endpoint and returns a client that carries the new session. */
    protected Client login(String username) throws Exception {
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

    protected static long jsonLong(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }
}
