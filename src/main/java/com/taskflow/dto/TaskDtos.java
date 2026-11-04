package com.taskflow.dto;

import com.taskflow.model.Attachment;
import com.taskflow.model.Task;
import com.taskflow.model.TaskLink;
import com.taskflow.model.TaskLinkType;
import com.taskflow.model.TaskPriority;
import com.taskflow.model.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TaskDtos {

    private TaskDtos() {}

    /**
     * Used for both create and update (PUT). On create, a null status/priority means TODO/MEDIUM.
     * On update, a null status/priority keeps the current value; a null assigneeId unassigns the task.
     */
    public record TaskRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            TaskStatus status,
            TaskPriority priority,
            LocalDate dueDate,
            Long assigneeId
    ) {}

    public record TaskResponse(
            Long id,
            Long projectId,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority,
            LocalDate dueDate,
            UserSummary assignee,
            UserSummary createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static TaskResponse from(Task task) {
            return new TaskResponse(task.getId(), task.getProject().getId(), task.getTitle(),
                    task.getDescription(), task.getStatus(), task.getPriority(), task.getDueDate(),
                    UserSummary.from(task.getAssignee()), UserSummary.from(task.getCreatedBy()),
                    task.getCreatedAt(), task.getUpdatedAt());
        }
    }

    public record LinkRequest(
            @NotNull Long targetTaskId,
            @NotNull TaskLinkType type
    ) {}

    /**
     * direction is OUTGOING when this task is the link's source ("this BLOCKS other"),
     * INCOMING when it is the target ("other BLOCKS this", i.e. this task is blocked by it).
     */
    public record LinkResponse(
            Long id,
            TaskLinkType type,
            String direction,
            Long otherTaskId,
            String otherTaskTitle
    ) {
        public static LinkResponse from(TaskLink link, Long thisTaskId) {
            boolean outgoing = link.getSource().getId().equals(thisTaskId);
            Task other = outgoing ? link.getTarget() : link.getSource();
            return new LinkResponse(link.getId(), link.getType(), outgoing ? "OUTGOING" : "INCOMING",
                    other.getId(), other.getTitle());
        }
    }

    public record TaskDetailResponse(TaskResponse task, List<LinkResponse> links) {}

    public record AttachmentResponse(
            Long id,
            Long taskId,
            String filename,
            String contentType,
            long sizeBytes,
            UserSummary uploadedBy,
            LocalDateTime uploadedAt
    ) {
        public static AttachmentResponse from(Attachment a) {
            return new AttachmentResponse(a.getId(), a.getTask().getId(), a.getOriginalFilename(),
                    a.getContentType(), a.getSizeBytes(), UserSummary.from(a.getUploadedBy()), a.getUploadedAt());
        }
    }
}
