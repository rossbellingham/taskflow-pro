package com.taskflow.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of the admin password reset. Leave temporaryPassword out (send {}) and the server generates a
 * random one and returns it once.
 */
public record ResetPasswordRequest(

        @Size(min = 10, max = 72, message = "must be between 10 and 72 characters") // 72 = BCrypt limit
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String temporaryPassword
) {}
