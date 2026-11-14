package com.taskflow.controller;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.TaskDtos.LinkRequest;
import com.taskflow.dto.TaskDtos.LinkResponse;
import com.taskflow.dto.TaskDtos.TaskDetailResponse;
import com.taskflow.dto.TaskDtos.TaskRequest;
import com.taskflow.dto.TaskDtos.TaskResponse;
import com.taskflow.model.TaskStatus;
import com.taskflow.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/projects/{projectId}/tasks")
    public ResponseEntity<TaskResponse> create(@PathVariable Long projectId,
                                               @Valid @RequestBody TaskRequest request,
                                               Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(projectId, request, auth));
    }

    /**
     * Optional filter: ?status=TODO | IN_PROGRESS | IN_REVIEW | DONE
     * Paginated: ?page=0&size=20 (size is capped at 100)
     */
    @GetMapping("/projects/{projectId}/tasks")
    public PageResponse<TaskResponse> list(@PathVariable Long projectId,
                                           @RequestParam(required = false) TaskStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size,
                                           Authentication auth) {
        return taskService.list(projectId, status, PageResponse.pageable(page, size), auth);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskDetailResponse get(@PathVariable Long taskId, Authentication auth) {
        return taskService.get(taskId, auth);
    }

    @PutMapping("/tasks/{taskId}")
    public TaskResponse update(@PathVariable Long taskId,
                               @Valid @RequestBody TaskRequest request,
                               Authentication auth) {
        return taskService.update(taskId, request, auth);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable Long taskId, Authentication auth) {
        taskService.delete(taskId, auth);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/links")
    public ResponseEntity<LinkResponse> addLink(@PathVariable Long taskId,
                                                @Valid @RequestBody LinkRequest request,
                                                Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.addLink(taskId, request, auth));
    }

    @DeleteMapping("/tasks/{taskId}/links/{linkId}")
    public ResponseEntity<Void> removeLink(@PathVariable Long taskId,
                                           @PathVariable Long linkId,
                                           Authentication auth) {
        taskService.removeLink(taskId, linkId, auth);
        return ResponseEntity.noContent().build();
    }
}
