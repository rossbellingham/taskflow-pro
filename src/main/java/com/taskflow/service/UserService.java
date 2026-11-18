package com.taskflow.service;

import com.taskflow.dto.RegisterRequest;
import com.taskflow.exception.BadRequestException;
import com.taskflow.exception.ConflictException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.model.UserSecurity;
import com.taskflow.repository.UserRepository;
import com.taskflow.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Set;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // No look-alike characters (0/O, 1/l/I), so a temporary password can be read out or typed without mistakes
    private static final String TEMP_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 16;

    private final UserRepository userRepository;
    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository userRepository,
                       UserSecurityRepository userSecurityRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())
                || userRepository.existsByEmailIgnoreCase(request.email())) {
            // Deliberately vague so the endpoint can't be used to enumerate accounts
            throw new ConflictException("Username or email is already in use");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email().toLowerCase());
        user.setPassword(passwordEncoder.encode(request.password()));
        // Self-registration only ever grants the lowest role; ADMIN/MANAGER are assigned by an admin
        user.getRoles().add(Role.ROLE_EMPLOYEE);

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous registrations can both pass the exists check; the DB constraint catches it
            throw new ConflictException("Username or email is already in use");
        }
    }

    /**
     * Changes the password if currentPassword is correct.
     *
     * @return false when currentPassword is wrong (the caller counts that as a failed attempt)
     * @throws BadRequestException when the new password is the same as the current one
     */
    @Transactional
    public boolean changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return false;
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BadRequestException("The new password must be different from the current one");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Clears a forced-change flag, and ends the user's other sessions (the caller keeps theirs)
        UserSecurity security = securityOf(user.getId());
        security.passwordChangedByUser();
        userSecurityRepository.save(security);
        return true;
    }

    /** What an admin password reset produced. generatedPassword is null when the admin chose the password. */
    public record PasswordReset(String username, String generatedPassword) {}

    /**
     * An admin sets a temporary password for another user. The user is logged out everywhere straight away
     * and, after logging in with the temporary password, can only change it (see FreshAuthorityFilter).
     *
     * @param requestedPassword the temporary password, or null to have one generated
     * @throws ConflictException when the admin targets their own account: that must go through the
     *         change-password endpoint, which asks for the current password
     */
    @Transactional
    public PasswordReset resetPasswordByAdmin(Long userId, String requestedPassword, String actingUsername) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (target.getUsername().equalsIgnoreCase(actingUsername)) {
            throw new ConflictException("Use PUT /api/auth/password to change your own password");
        }

        String generated = requestedPassword == null ? generatePassword() : null;
        target.setPassword(passwordEncoder.encode(generated != null ? generated : requestedPassword));
        userRepository.save(target);

        UserSecurity security = securityOf(target.getId());
        security.passwordResetByAdmin();
        userSecurityRepository.save(security);

        // Audit trail. Never log the password itself.
        log.info("Admin '{}' reset the password of user '{}' (id {}); their sessions were ended",
                actingUsername, target.getUsername(), target.getId());
        return new PasswordReset(target.getUsername(), generated);
    }

    /** True while the user has a temporary password they must replace. */
    @Transactional(readOnly = true)
    public boolean isPasswordChangeRequired(String username) {
        return userSecurityRepository.findByUsername(username)
                .map(UserSecurity::isMustChangePassword)
                .orElse(false);
    }

    private UserSecurity securityOf(Long userId) {
        return userSecurityRepository.findById(userId).orElseGet(() -> new UserSecurity(userId));
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(random.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Page<User> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    /** Replaces the user's roles with exactly the given set. */
    @Transactional
    public User updateRoles(Long userId, Set<Role> newRoles, String actingUsername) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        // Stops an admin locking themselves out. It also guarantees at least one admin always
        // remains, because only an admin can change roles and they can't remove their own.
        boolean isSelf = target.getUsername().equalsIgnoreCase(actingUsername);
        if (isSelf && !newRoles.contains(Role.ROLE_ADMIN)) {
            throw new ConflictException("You cannot remove your own admin role");
        }

        target.getRoles().clear();
        target.getRoles().addAll(newRoles);
        return userRepository.save(target);
    }
}
