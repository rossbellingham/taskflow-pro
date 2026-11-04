package com.taskflow.dto;

import com.taskflow.model.User;

/** Minimal public view of a user: never includes email or password. */
public record UserSummary(Long id, String username) {

    public static UserSummary from(User user) {
        return user == null ? null : new UserSummary(user.getId(), user.getUsername());
    }
}
