package com.taskflow.controller;

import com.taskflow.dto.LoginRequest;
import com.taskflow.dto.RegisterRequest;
import com.taskflow.model.User;
import com.taskflow.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Logout is handled by Spring Security itself: POST /api/auth/logout (see SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(UserService userService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User created = userService.register(request);
        // Never return the entity itself: it contains the password hash
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", created.getId(),
                "username", created.getUsername(),
                "email", created.getEmail()
        ));
    }

    /**
     * Checks the credentials and starts a session. The response sets a JSESSIONID cookie, and the
     * client sends that cookie back on later calls (browsers and Swagger UI do this automatically).
     */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        // Throws BadCredentialsException on failure; GlobalExceptionHandler turns that into a generic 401
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));

        // Session fixation defense: never keep a session id that existed before login
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return describe(auth);
    }

    /** Returns who the current session belongs to. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return describe(authentication);
    }

    /**
     * Does nothing except make sure the XSRF-TOKEN cookie has been issued. Scripts and tools that
     * haven't loaded any page yet can call this first, then send the cookie's value back in the
     * X-XSRF-TOKEN header on POST/PUT/DELETE requests.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> describe(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
        return Map.of("username", authentication.getName(), "roles", roles);
    }
}
