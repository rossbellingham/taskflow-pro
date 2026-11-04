package com.taskflow.dto;

import com.taskflow.model.Role;
import com.taskflow.model.User;

import java.util.Set;

/** What the admin API returns about a user. Deliberately has no password field. */
public record UserResponse(Long id, String username, String email, Set<Role> roles) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), Set.copyOf(user.getRoles()));
    }
}
