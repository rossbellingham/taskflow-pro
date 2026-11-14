package com.taskflow.service;

import com.taskflow.dto.PageResponse;
import com.taskflow.dto.TaskDtos.LinkRequest;
import com.taskflow.dto.TaskDtos.LinkResponse;
import com.taskflow.dto.TaskDtos.TaskDetailResponse;
import com.taskflow.dto.TaskDtos.TaskRequest;
import com.taskflow.dto.TaskDtos.TaskResponse;
import com.taskflow.exception.BadRequestException;
import com.taskflow.exception.ConflictException;
import com.taskflow.exception.ForbiddenException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Attachment;
import com.taskflow.model.Project;
import com.taskflow.model.Task;
import com.taskflow.model.TaskLink;
import com.taskflow.model.TaskLinkType;
import com.taskflow.model.TaskStatus;
import com.taskflow.model.User;
import com.taskflow.repository.AttachmentRepository;
import com.taskflow.repository.ProjectRepository;
import com.taskflow.repository.TaskLinkRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.repository.UserRepository;
import com.taskflow.service.AccessService.Access;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Task rules:
 *  - anyone who can view a project (member, its manager, admin) can see all its tasks and create tasks
 *  - a task can be edited by the project manager, an admin, its assignee or its creator
 *  - only the project manager or an admin can delete a task
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskLinkRepository taskLinkRepository;
    private final ProjectRepository projectRepository;
    private final AttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final AccessService accessService;
    private final FileStorageService fileStorage;

    public TaskService(TaskRepository taskRepository,
                       TaskLinkRepository taskLinkRepository,
                       ProjectRepository projectRepository,
                       AttachmentRepository attachmentRepository,
                       UserRepository userRepository,
                       AccessService accessService,
                       FileStorageService fileStorage) {
        this.taskRepository = taskRepository;
        this.taskLinkRepository = taskLinkRepository;
        this.projectRepository = projectRepository;
        this.attachmentRepository = attachmentRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.fileStorage = fileStorage;
    }

    @Transactional
    public TaskResponse create(Long projectId, TaskRequest request, Authentication auth) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        Access access = accessService.requireView(project, auth);

        Task task = new Task();
        task.setProject(project);
        task.setCreatedBy(access.user());
        apply(task, request, project);
        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(Long projectId, TaskStatus statusFilter, Pageable pageable,
                                           Authentication auth) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        accessService.requireView(project, auth);

        Page<Task> page = statusFilter == null
                ? taskRepository.findByProjectId(projectId, pageable)
                : taskRepository.findByProjectIdAndStatus(projectId, statusFilter, pageable);
        return PageResponse.of(page, TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(Long taskId, Authentication auth) {
        Task task = findTask(taskId);
        accessService.requireView(task.getProject(), auth);

        List<LinkResponse> links = taskLinkRepository.findBySourceIdOrTargetId(taskId, taskId).stream()
                .map(link -> LinkResponse.from(link, taskId))
                .toList();
        return new TaskDetailResponse(TaskResponse.from(task), links);
    }

    @Transactional
    public TaskResponse update(Long taskId, TaskRequest request, Authentication auth) {
        Task task = findTask(taskId);
        Access access = accessService.requireView(task.getProject(), auth);
        requireCanEdit(task, access);

        apply(task, request, task.getProject());
        // Flush so updated_at is refreshed before the response is built
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    @Transactional
    public void delete(Long taskId, Authentication auth) {
        Task task = findTask(taskId);
        accessService.requireManage(task.getProject(), auth);

        List<String> storageKeys = attachmentRepository.findByTaskIdOrderById(taskId).stream()
                .map(Attachment::getStorageKey)
                .toList();

        // Attachment and link rows are removed by the database (ON DELETE CASCADE)
        taskRepository.delete(task);
        taskRepository.flush();
        storageKeys.forEach(fileStorage::delete);
    }

    // ---- links ----

    @Transactional
    public LinkResponse addLink(Long taskId, LinkRequest request, Authentication auth) {
        Task source = findTask(taskId);
        Access access = accessService.requireView(source.getProject(), auth);
        requireCanEdit(source, access);

        // Same message whether the target is missing or in another project, so ids can't be probed
        Task target = taskRepository.findById(request.targetTaskId())
                .filter(t -> t.getProject().getId().equals(source.getProject().getId()))
                .orElseThrow(() -> new BadRequestException("Target task not found in this project"));

        if (target.getId().equals(source.getId())) {
            throw new BadRequestException("A task cannot be linked to itself");
        }

        TaskLinkType type = request.type();
        boolean exists = taskLinkRepository.existsBySourceIdAndTargetIdAndType(source.getId(), target.getId(), type)
                || (type == TaskLinkType.RELATES_TO
                && taskLinkRepository.existsBySourceIdAndTargetIdAndType(target.getId(), source.getId(), type));
        if (exists) {
            throw new ConflictException("These tasks are already linked this way");
        }

        TaskLink link = new TaskLink();
        link.setSource(source);
        link.setTarget(target);
        link.setType(type);
        return LinkResponse.from(taskLinkRepository.save(link), taskId);
    }

    @Transactional
    public void removeLink(Long taskId, Long linkId, Authentication auth) {
        Task task = findTask(taskId);
        Access access = accessService.requireView(task.getProject(), auth);
        requireCanEdit(task, access);

        TaskLink link = taskLinkRepository.findById(linkId)
                .filter(l -> l.getSource().getId().equals(taskId) || l.getTarget().getId().equals(taskId))
                .orElseThrow(() -> new NotFoundException("Not found"));
        taskLinkRepository.delete(link);
    }

    // ---- helpers ----

    private Task findTask(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new NotFoundException("Not found"));
    }

    private void requireCanEdit(Task task, Access access) {
        Long userId = access.user().getId();
        boolean isAssignee = task.getAssignee() != null && task.getAssignee().getId().equals(userId);
        boolean isCreator = task.getCreatedBy().getId().equals(userId);
        if (!(access.canManage() || isAssignee || isCreator)) {
            throw new ForbiddenException("You do not have permission to change this task");
        }
    }

    private void apply(Task task, TaskRequest request, Project project) {
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setDueDate(request.dueDate());
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        task.setAssignee(resolveAssignee(request.assigneeId(), project));
    }

    private User resolveAssignee(Long assigneeId, Project project) {
        if (assigneeId == null) {
            return null;
        }
        String message = "Assignee must be the manager or a member of this project";
        User user = userRepository.findById(assigneeId).orElseThrow(() -> new BadRequestException(message));
        boolean allowed = project.getManager().getId().equals(assigneeId)
                || project.getMembers().stream().anyMatch(m -> m.getId().equals(assigneeId));
        if (!allowed) {
            throw new BadRequestException(message);
        }
        return user;
    }
}
