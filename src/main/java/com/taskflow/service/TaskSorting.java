package com.taskflow.service;

import com.taskflow.exception.BadRequestException;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

/**
 * Turns the ?sort=field,direction parameter into a Spring Sort, accepting only known fields.
 * Letting clients name any column would let them probe the schema or crash the query.
 *
 * "priority" and "status" sort by importance (LOW to URGENT, TODO to DONE) through the calculated
 * rank columns added in V5, not alphabetically. Ties are always broken by id so paging is stable.
 * Tasks with no due date sort last when ascending and first when descending (PostgreSQL's default).
 */
final class TaskSorting {

    static final String ALLOWED_FIELDS = "id, title, status, priority, dueDate, createdAt, updatedAt";

    /** Public field name -> entity property */
    private static final Map<String, String> FIELDS = Map.of(
            "id", "id",
            "title", "title",
            "status", "statusRank",
            "priority", "priorityRank",
            "dueDate", "dueDate",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private TaskSorting() {}

    /** Accepts "field" or "field,asc" or "field,desc". Blank means id ascending. */
    static Sort parse(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "id");
        }

        String[] parts = sort.split(",", -1);
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (parts.length > 2) {
            throw new BadRequestException("Use sort=field or sort=field,asc or sort=field,desc");
        }

        String property = FIELDS.get(field);
        if (property == null) {
            throw new BadRequestException("Unknown sort field. Allowed: " + ALLOWED_FIELDS);
        }

        Sort.Direction dir;
        if (direction.equals("asc")) {
            dir = Sort.Direction.ASC;
        } else if (direction.equals("desc")) {
            dir = Sort.Direction.DESC;
        } else {
            throw new BadRequestException("Sort direction must be asc or desc");
        }

        Sort result = Sort.by(dir, property);
        return property.equals("id") ? result : result.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
