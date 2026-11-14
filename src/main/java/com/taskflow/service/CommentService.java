package com.taskflow.service;

import com.taskflow.dto.CommentDtos.CommentRequest;
import com.taskflow.dto.CommentDtos.CommentResponse;
import com.taskflow.dto.PageResponse;
import com.taskflow.exception.ForbiddenException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Task;
import com.taskflow.model.TaskComment;
import com.taskflow.repository.TaskCommentRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.service.AccessService.Access;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comment rules:
 *  - anyone who can see the project (member, its manager, admin) can read and write comments on its tasks
 *  - only the author can edit a comment
 *  - the author, the project manager and admins can delete a comment
 *  - non-participants get 404
 */
@Service
public class CommentService {

    private final TaskCommentRepository commentRepository;
    private final TaskRepository taskRepository;
    private final AccessService accessService;

    public CommentService(TaskCommentRepository commentRepository,
                          TaskRepository taskRepository,
                          AccessService accessService) {
        this.commentRepository = commentRepository;
        this.taskRepository = taskRepository;
        this.accessService = accessService;
    }

    @Transactional
    public CommentResponse create(Long taskId, CommentRequest request, Authentication auth) {
        Task task = findTask(taskId);
        Access access = accessService.requireView(task.getProject(), auth);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(access.user());
        comment.setBody(request.body().trim());
        return CommentResponse.from(commentRepository.saveAndFlush(comment));
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(Long taskId, Pageable pageable, Authentication auth) {
        Task task = findTask(taskId);
        accessService.requireView(task.getProject(), auth);
        return PageResponse.of(commentRepository.findByTaskId(taskId, pageable), CommentResponse::from);
    }

    @Transactional
    public CommentResponse update(Long commentId, CommentRequest request, Authentication auth) {
        TaskComment comment = findComment(commentId);
        Access access = accessService.requireView(comment.getTask().getProject(), auth);

        if (!isAuthor(comment, access)) {
            throw new ForbiddenException("Only the author can edit a comment");
        }
        comment.setBody(request.body().trim());
        return CommentResponse.from(commentRepository.saveAndFlush(comment));
    }

    @Transactional
    public void delete(Long commentId, Authentication auth) {
        TaskComment comment = findComment(commentId);
        Access access = accessService.requireView(comment.getTask().getProject(), auth);

        if (!(isAuthor(comment, access) || access.canManage())) {
            throw new ForbiddenException("Only the author, the project manager or an admin can delete a comment");
        }
        commentRepository.delete(comment);
    }

    private Task findTask(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new NotFoundException("Not found"));
    }

    private TaskComment findComment(Long commentId) {
        return commentRepository.findById(commentId).orElseThrow(() -> new NotFoundException("Not found"));
    }

    private boolean isAuthor(TaskComment comment, Access access) {
        return comment.getAuthor().getId().equals(access.user().getId());
    }
}
