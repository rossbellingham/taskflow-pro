package com.taskflow.service;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.ProjectDtos.CreateProjectRequest;
import com.taskflow.dto.ProjectDtos.ProjectResponse;
import com.taskflow.dto.ProjectDtos.UpdateProjectRequest;
import com.taskflow.exception.BadRequestException;
import com.taskflow.exception.ForbiddenException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Project;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.AttachmentRepository;
import com.taskflow.repository.ProjectRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.repository.UserRepository;
import com.taskflow.service.AccessService.Access;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final AttachmentRepository attachmentRepository;
    private final AccessService accessService;
    private final FileStorageService fileStorage;

    public ProjectService(ProjectRepository projectRepository,
                          UserRepository userRepository,
                          TaskRepository taskRepository,
                          AttachmentRepository attachmentRepository,
                          AccessService accessService,
                          FileStorageService fileStorage) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.attachmentRepository = attachmentRepository;
        this.accessService = accessService;
        this.fileStorage = fileStorage;
    }

    /** Callers must already be admin-only (enforced on the controller). */
    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        User manager = requireManagerUser(request.managerId());

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
    public PageResponse<ProjectResponse> list(Authentication auth, Pageable pageable) {
        Page<Project> page;
        if (accessService.hasRole(auth, Role.ROLE_ADMIN)) {
            page = projectRepository.findAll(pageable);
        } else {
            User user = accessService.currentUser(auth);
            boolean includeManaged = accessService.hasRole(auth, Role.ROLE_MANAGER);
            page = projectRepository.findAllForUser(user.getId(), includeManaged, pageable);
        }
        return PageResponse.of(page, ProjectResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long projectId, Authentication auth) {
        Project project = findProject(projectId);
        accessService.requireView(project, auth);
        return ProjectResponse.from(project);
    }

    /** Project manager or admin can rename it; only an admin can change the manager. */
    @Transactional
    public ProjectResponse update(Long projectId, UpdateProjectRequest request, Authentication auth) {
        Project project = findProject(projectId);
        Access access = accessService.requireManage(project, auth);

        project.setName(request.name().trim());
        project.setDescription(request.description());

        boolean changingManager = request.managerId() != null
                && !request.managerId().equals(project.getManager().getId());
        if (changingManager) {
            if (!access.admin()) {
                throw new ForbiddenException("Only an admin can change a project's manager");
            }
            project.setManager(requireManagerUser(request.managerId()));
        }
        return ProjectResponse.from(projectRepository.save(project));
    }

    /**
     * Deletes the project with all its tasks, links, comments and attachments (the database cascades the
     * rows), then removes the attachment files from disk. Callers must already be admin-only.
     */
    @Transactional
    public void delete(Long projectId) {
        Project project = findProject(projectId);
        List<String> storageKeys = attachmentRepository.findStorageKeysByProjectId(projectId);

        projectRepository.delete(project);
        projectRepository.flush();
        storageKeys.forEach(fileStorage::delete);
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

    private User requireManagerUser(Long managerId) {
        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new BadRequestException("Manager not found"));
        if (!manager.getRoles().contains(Role.ROLE_MANAGER)) {
            throw new BadRequestException("The selected manager does not have the manager role");
        }
        return manager;
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("Not found"));
    }
}
