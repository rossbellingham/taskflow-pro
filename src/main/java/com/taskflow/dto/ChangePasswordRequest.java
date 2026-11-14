package com.taskflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        @NotBlank
        @Size(max = 72)
        String currentPassword,

        @NotBlank
        @Size(min = 10, max = 72, message = "must be between 10 and 72 characters") // 72 = BCrypt limit
        String newPassword
) {}
