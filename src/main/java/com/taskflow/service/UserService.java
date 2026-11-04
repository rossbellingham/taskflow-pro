package com.taskflow.service;

import com.taskflow.dto.RegisterRequest;
import com.taskflow.exception.ConflictException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Role;
import com.taskflow.model.User;
import com.taskflow.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
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

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll(Sort.by("id"));
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
