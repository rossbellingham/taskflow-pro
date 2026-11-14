package com.taskflow.dto;

import com.taskflow.model.TaskComment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class CommentDtos {

    private CommentDtos() {}

    /** Used for both creating and editing a comment. */
    public record CommentRequest(
            @NotBlank @Size(max = 2000) String body
    ) {}

    public record CommentResponse(
            Long id,
            Long taskId,
            UserSummary author,
            String body,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CommentResponse from(TaskComment c) {
            return new CommentResponse(c.getId(), c.getTask().getId(), UserSummary.from(c.getAuthor()),
                    c.getBody(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
