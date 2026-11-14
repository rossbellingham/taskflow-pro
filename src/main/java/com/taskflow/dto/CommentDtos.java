package com.taskflow.dto;

import com.taskflow.model.TaskComment;
import com.taskflow.model.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

public final class CommentDtos {

    private CommentDtos() {}

    /** Used for both creating and editing a comment. Write @username to mention a project participant. */
    public record CommentRequest(
            @NotBlank @Size(max = 2000) String body
    ) {}

    public record CommentResponse(
            Long id,
            Long taskId,
            UserSummary author,
            String body,
            List<UserSummary> mentions,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CommentResponse from(TaskComment c) {
            List<UserSummary> mentions = c.getMentions().stream()
                    .sorted(Comparator.comparing(User::getId))
                    .map(UserSummary::from)
                    .toList();
            return new CommentResponse(c.getId(), c.getTask().getId(), UserSummary.from(c.getAuthor()),
                    c.getBody(), mentions, c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** One entry in a user's "comments that mention me" inbox. */
    public record MentionResponse(
            Long commentId,
            Long taskId,
            String taskTitle,
            Long projectId,
            UserSummary author,
            String body,
            LocalDateTime createdAt
    ) {
        public static MentionResponse from(TaskComment c) {
            return new MentionResponse(c.getId(), c.getTask().getId(), c.getTask().getTitle(),
                    c.getTask().getProject().getId(), UserSummary.from(c.getAuthor()),
                    c.getBody(), c.getCreatedAt());
        }
    }
}
