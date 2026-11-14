package com.taskflow.controller;

import com.taskflow.dto.CommentDtos.CommentRequest;
import com.taskflow.dto.CommentDtos.CommentResponse;
import com.taskflow.dto.PageResponse;
import com.taskflow.service.CommentService;
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
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping("/tasks/{taskId}/comments")
    public ResponseEntity<CommentResponse> create(@PathVariable Long taskId,
                                                  @Valid @RequestBody CommentRequest request,
                                                  Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commentService.create(taskId, request, auth));
    }

    /** Oldest first. Paginated: ?page=0&size=20 */
    @GetMapping("/tasks/{taskId}/comments")
    public PageResponse<CommentResponse> list(@PathVariable Long taskId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size,
                                              Authentication auth) {
        return commentService.list(taskId, PageResponse.pageable(page, size), auth);
    }

    /** Author only. */
    @PutMapping("/comments/{commentId}")
    public CommentResponse update(@PathVariable Long commentId,
                                  @Valid @RequestBody CommentRequest request,
                                  Authentication auth) {
        return commentService.update(commentId, request, auth);
    }

    /** Author, project manager or admin. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable Long commentId, Authentication auth) {
        commentService.delete(commentId, auth);
        return ResponseEntity.noContent().build();
    }
}
