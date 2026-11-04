package com.taskflow.dto;

import com.taskflow.model.Role;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record UpdateRolesRequest(
        @NotEmpty(message = "must contain at least one role")
        Set<@NotNull Role> roles
) {}
