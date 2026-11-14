package com.taskflow.controller;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.ProjectDtos.CreateProjectRequest;
import com.taskflow.dto.ProjectDtos.ProjectResponse;
import com.taskflow.dto.ProjectDtos.UpdateProjectRequest;
import com.taskflow.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** Admin only: creates a project and assigns its manager (who must hold ROLE_MANAGER). */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(request));
    }

    /**
     * Admins see every project; everyone else sees projects they manage or belong to.
     * Paginated: ?page=0&size=20
     */
    @GetMapping
    public PageResponse<ProjectResponse> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size,
                                              Authentication auth) {
        return projectService.list(auth, PageResponse.pageable(page, size));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId, Authentication auth) {
        return projectService.get(projectId, auth);
    }

    /** Project manager or admin can rename; only an admin can change the manager. */
    @PutMapping("/{projectId}")
    public ProjectResponse update(@PathVariable Long projectId,
                                  @Valid @RequestBody UpdateProjectRequest request,
                                  Authentication auth) {
        return projectService.update(projectId, request, auth);
    }

    /** Admin only. Permanently deletes the project, its tasks, comments and attached files. */
    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long projectId) {
        projectService.delete(projectId);
        return ResponseEntity.noContent().build();
    }

    /** Project manager or admin. */
    @PutMapping("/{projectId}/members/{userId}")
    public ProjectResponse addMember(@PathVariable Long projectId, @PathVariable Long userId, Authentication auth) {
        return projectService.addMember(projectId, userId, auth);
    }

    /** Project manager or admin. The removed member's tasks in this project become unassigned. */
    @DeleteMapping("/{projectId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long projectId, @PathVariable Long userId,
                                             Authentication auth) {
        projectService.removeMember(projectId, userId, auth);
        return ResponseEntity.noContent().build();
    }
}
