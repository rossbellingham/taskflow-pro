package com.taskflow.controller;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.ResetPasswordRequest;
import com.taskflow.dto.UpdateRolesRequest;
import com.taskflow.dto.UserResponse;
import com.taskflow.security.LoginAttemptService;
import com.taskflow.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only user management. SecurityConfig already restricts /api/admin/** to ROLE_ADMIN;
 * @PreAuthorize repeats that on the controller itself as a second layer.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final LoginAttemptService loginAttempts;

    public AdminUserController(UserService userService, LoginAttemptService loginAttempts) {
        this.userService = userService;
        this.loginAttempts = loginAttempts;
    }

    /** Paginated: ?page=0&size=20 */
    @GetMapping
    public PageResponse<UserResponse> listUsers(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return PageResponse.of(userService.listUsers(PageResponse.pageable(page, size)), UserResponse::from);
    }

    @PutMapping("/{id}/roles")
    public UserResponse updateRoles(@PathVariable Long id,
                                    @Valid @RequestBody UpdateRolesRequest request,
                                    Authentication authentication) {
        return UserResponse.from(userService.updateRoles(id, request.roles(), authentication.getName()));
    }

    /**
     * Resets someone else's password (for example after they forgot it). Send {"temporaryPassword":"..."}
     * (10 to 72 characters), or {} to have the server generate one, which is returned once in the response.
     *
     * Effects: the user is logged out everywhere immediately; their login lockout, if any, is cleared;
     * and when they log in with the temporary password (login returns passwordChangeRequired: true) every
     * endpoint except PUT /api/auth/password answers 403 PASSWORD_CHANGE_REQUIRED until they pick a new one.
     * There is no email, so the admin must pass the temporary password to the user themselves.
     * Resetting your own password here is refused; use PUT /api/auth/password.
     */
    @PutMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable Long id,
                                             @Valid @RequestBody ResetPasswordRequest request,
                                             Authentication authentication) {
        UserService.PasswordReset reset =
                userService.resetPasswordByAdmin(id, request.temporaryPassword(), authentication.getName());

        // The user may be locked out after failed guesses, which is a likely reason for the reset
        String key = reset.username().toLowerCase();
        loginAttempts.recordLoginSuccess(key);
        loginAttempts.recordPasswordChangeSuccess(key);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("username", reset.username());
        body.put("passwordChangeRequired", true);
        if (reset.generatedPassword() != null) {
            body.put("temporaryPassword", reset.generatedPassword());
        }
        return body;
    }
}
