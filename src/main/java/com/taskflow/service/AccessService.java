package com.taskflow.service;

import com.taskflow.exception.ForbiddenException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Project;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * One place for "who may do what in this project".
 *
 *   admin    : ROLE_ADMIN, sees and manages every project
 *   manager  : the project's manager AND currently holds ROLE_MANAGER
 *   member   : listed in the project's members
 *
 * Callers must be inside a transaction (project.getMembers() is lazy).
 */
@Service
public class AccessService {

    public record Access(User user, boolean admin, boolean manager, boolean member) {
        public boolean canView() {
            return admin || manager || member;
        }

        public boolean canManage() {
            return admin || manager;
        }
    }

    private final UserRepository userRepository;

    public AccessService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User currentUser(Authentication auth) {
        return userRepository.findByUsernameIgnoreCase(auth.getName())
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    public boolean hasRole(Authentication auth, Role role) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role.name()));
    }

    public Access access(Project project, Authentication auth) {
        User user = currentUser(auth);
        boolean admin = hasRole(auth, Role.ROLE_ADMIN);
        boolean manager = hasRole(auth, Role.ROLE_MANAGER)
                && project.getManager().getId().equals(user.getId());
        boolean member = project.getMembers().stream().anyMatch(m -> m.getId().equals(user.getId()));
        return new Access(user, admin, manager, member);
    }

    /** Non-participants get 404, not 403, so project and task ids can't be probed. */
    public Access requireView(Project project, Authentication auth) {
        Access access = access(project, auth);
        if (!access.canView()) {
            throw new NotFoundException("Not found");
        }
        return access;
    }

    public Access requireManage(Project project, Authentication auth) {
        Access access = requireView(project, auth);
        if (!access.canManage()) {
            throw new ForbiddenException("Only the project manager or an admin can do this");
        }
        return access;
    }
}
