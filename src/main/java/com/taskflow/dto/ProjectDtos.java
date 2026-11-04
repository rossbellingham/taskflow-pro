package com.taskflow.dto;

import com.taskflow.model.Project;
import com.taskflow.model.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ProjectDtos {

    private ProjectDtos() {}

    public record CreateProjectRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String description,
            @NotNull Long managerId,
            Set<Long> memberIds
    ) {}

    public record ProjectResponse(
            Long id,
            String name,
            String description,
            UserSummary manager,
            List<UserSummary> members,
            LocalDateTime createdAt
    ) {
        public static ProjectResponse from(Project project) {
            List<UserSummary> members = project.getMembers().stream()
                    .sorted(Comparator.comparing(User::getId))
                    .map(UserSummary::from)
                    .toList();
            return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                    UserSummary.from(project.getManager()), members, project.getCreatedAt());
        }
    }
}
