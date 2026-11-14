package com.taskflow.service;

import com.taskflow.model.TaskPriority;
import com.taskflow.model.TaskStatus;

import java.time.LocalDate;

/**
 * Optional filters for listing a project's tasks. A null (or false) value means "don't filter on this".
 * All the filters that are set must match at once.
 *
 * @param q          text to look for in the title or description (case-insensitive, matches anywhere)
 * @param assigneeId only tasks assigned to this user
 * @param unassigned only tasks with nobody assigned
 * @param dueBefore  only tasks due on or before this date (tasks with no due date never match)
 * @param dueAfter   only tasks due on or after this date (tasks with no due date never match)
 */
public record TaskSearch(
        String q,
        TaskStatus status,
        TaskPriority priority,
        Long assigneeId,
        boolean unassigned,
        LocalDate dueBefore,
        LocalDate dueAfter
) {}
