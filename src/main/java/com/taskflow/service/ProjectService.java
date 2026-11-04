package com.taskflow.service;

import com.taskflow.dto.ProjectDtos.CreateProjectRequest;
import com.taskflow.dto.ProjectDtos.ProjectResponse;
import com.taskflow.exception.BadRequestException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Project;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.ProjectRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final AccessService accessService;

    public ProjectService(ProjectRepository projectRepository,
                          UserRepository userRepository,
                          TaskRepository taskRepository,
                          AccessService accessService) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.accessService = accessService;
    }

    /** Callers must already be admin-only (enforced on the controller). */
    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        User manager = userRepository.findById(request.managerId())
                .orElseThrow(() -> new BadRequestException("Manager not found"));
        if (!manager.getRoles().contains(Role.ROLE_MANAGER)) {
            throw new BadRequestException("The selected manager does not have the manager role");
        }

        Project project = new Project();
        project.setName(request.name().trim());
        project.setDescription(request.description());
        project.setManager(manager);

        Set<Long> memberIds = request.memberIds() == null ? Set.of() : request.memberIds();
        List<User> members = userRepository.findAllById(memberIds);
        if (members.size() != new HashSet<>(memberIds).size()) {
            throw new BadRequestException("One or more member ids do not exist");
        }
        project.getMembers().addAll(members);

        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(Authentication auth) {
        User user = accessService.currentUser(auth);
        List<Project> projects = accessService.hasRole(auth, Role.ROLE_ADMIN)
                ? projectRepository.findAll(Sort.by("id"))
                : projectRepository.findAllForUser(user.getId());

        return projects.stream()
                .filter(p -> accessService.access(p, auth).canView())
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long projectId, Authentication auth) {
        Project project = findProject(projectId);
        accessService.requireView(project, auth);
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse addMember(Long projectId, Long userId, Authentication auth) {
        Project project = findProject(projectId);
        accessService.requireManage(project, auth);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found"));
        project.getMembers().add(user);
        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional
    public void removeMember(Long projectId, Long userId, Authentication auth) {
        Project project = findProject(projectId);
        accessService.requireManage(project, auth);

        boolean removed = project.getMembers().removeIf(m -> m.getId().equals(userId));
        if (!removed) {
            throw new NotFoundException("That user is not a member of this project");
        }
        // Flushes the membership change first, then unassigns that person's tasks in this project
        taskRepository.unassignUserInProject(projectId, userId);
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("Not found"));
    }
}
