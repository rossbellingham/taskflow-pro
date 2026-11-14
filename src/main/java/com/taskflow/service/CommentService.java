package com.taskflow.service;

import com.taskflow.dto.CommentDtos.CommentRequest;
import com.taskflow.dto.CommentDtos.CommentResponse;
import com.taskflow.dto.CommentDtos.MentionResponse;
import com.taskflow.dto.PageResponse;
import com.taskflow.exception.ForbiddenException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Project;
import com.taskflow.model.Role;
import com.taskflow.model.Task;
import com.taskflow.model.TaskComment;
import com.taskflow.model.User;
import com.taskflow.repository.TaskCommentRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.service.AccessService.Access;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comment rules:
 *  - anyone who can see the project (member, its manager, admin) can read and write comments on its tasks
 *  - only the author can edit a comment
 *  - the author, the project manager and admins can delete a comment
 *  - non-participants get 404
 *
 * Mentions: writing @username in a comment tags that person. Only the project's manager and members can
 * be tagged; anything else (unknown names, people outside the project, email addresses, yourself) is
 * left as plain text without an error. Mentions are recalculated whenever the comment is edited.
 * There is no email or push notification: tagged people find the comment in GET /api/mentions.
 */
@Service
public class CommentService {

    /** "@name" where the "@" isn't glued to a preceding word character, so foo@example.com is ignored. */
    private static final Pattern MENTION = Pattern.compile("(?<![\\w.@-])@([A-Za-z0-9_.-]{3,50})");

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

        String body = request.body().trim();
        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(access.user());
        comment.setBody(body);
        comment.getMentions().addAll(resolveMentions(task.getProject(), access.user(), body));
        return CommentResponse.from(commentRepository.saveAndFlush(comment));
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(Long taskId, Pageable pageable, Authentication auth) {
        Task task = findTask(taskId);
        accessService.requireView(task.getProject(), auth);
        return PageResponse.of(commentRepository.findByTaskId(taskId, pageable), CommentResponse::from);
    }

    /** Comments (newest first) that tag the current user, in projects they can still see. */
    @Transactional(readOnly = true)
    public PageResponse<MentionResponse> mentionsOfCurrentUser(Pageable pageable, Authentication auth) {
        User user = accessService.currentUser(auth);
        boolean includeManaged = accessService.hasRole(auth, Role.ROLE_MANAGER);
        return PageResponse.of(
                commentRepository.findMentionsOf(user.getId(), includeManaged, pageable),
                MentionResponse::from);
    }

    @Transactional
    public CommentResponse update(Long commentId, CommentRequest request, Authentication auth) {
        TaskComment comment = findComment(commentId);
        Access access = accessService.requireView(comment.getTask().getProject(), auth);

        if (!isAuthor(comment, access)) {
            throw new ForbiddenException("Only the author can edit a comment");
        }
        String body = request.body().trim();
        comment.setBody(body);
        comment.getMentions().clear();
        comment.getMentions().addAll(resolveMentions(comment.getTask().getProject(), access.user(), body));
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

    // ---- helpers ----

    /** Finds the project participants tagged in the body. Must run inside a transaction (lazy members). */
    private Set<User> resolveMentions(Project project, User author, String body) {
        Map<String, User> participants = new HashMap<>();
        participants.put(project.getManager().getUsername().toLowerCase(), project.getManager());
        for (User member : project.getMembers()) {
            participants.put(member.getUsername().toLowerCase(), member);
        }

        Set<User> found = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find()) {
            String candidate = matcher.group(1).toLowerCase();
            User user = participants.get(candidate);
            // "Thanks @sam." captures "sam." - drop trailing punctuation until a real username matches
            while (user == null && candidate.length() > 3 && (candidate.endsWith(".") || candidate.endsWith("-"))) {
                candidate = candidate.substring(0, candidate.length() - 1);
                user = participants.get(candidate);
            }
            if (user != null && !user.getId().equals(author.getId())) {
                found.add(user);
            }
        }
        return found;
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
