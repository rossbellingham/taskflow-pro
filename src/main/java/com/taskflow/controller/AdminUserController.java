package com.taskflow.controller;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.UpdateRolesRequest;
import com.taskflow.dto.UserResponse;
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

/**
 * Admin-only user management. SecurityConfig already restricts /api/admin/** to ROLE_ADMIN;
 * @PreAuthorize repeats that on the controller itself as a second layer.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
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
}
