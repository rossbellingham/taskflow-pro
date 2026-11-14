package com.taskflow.repository;

import com.taskflow.model.Task;
import com.taskflow.service.TaskSearch;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builds the database query for "tasks in this project that match these filters". */
public final class TaskSpecifications {

    private TaskSpecifications() {}

    public static Specification<Task> matching(Long projectId, TaskSearch search) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(cb.equal(root.get("project").get("id"), projectId));

            if (search.q() != null && !search.q().isBlank()) {
                String pattern = "%" + escapeLike(search.q().trim().toLowerCase()) + "%";
                conditions.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.<String>get("description")), pattern, '\\')));
            }
            if (search.status() != null) {
                conditions.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.priority() != null) {
                conditions.add(cb.equal(root.get("priority"), search.priority()));
            }
            if (search.assigneeId() != null) {
                conditions.add(cb.equal(root.get("assignee").get("id"), search.assigneeId()));
            }
            if (search.unassigned()) {
                conditions.add(cb.isNull(root.get("assignee")));
            }
            if (search.dueBefore() != null) {
                conditions.add(cb.lessThanOrEqualTo(root.<LocalDate>get("dueDate"), search.dueBefore()));
            }
            if (search.dueAfter() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("dueDate"), search.dueAfter()));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }

    /** Makes %, _ and \ in the user's text match themselves instead of acting as LIKE wildcards. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
