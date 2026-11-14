package com.taskflow.repository;

import com.taskflow.model.Task;
import com.taskflow.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Page<Task> findByProjectId(Long projectId, Pageable pageable);

    Page<Task> findByProjectIdAndStatus(Long projectId, TaskStatus status, Pageable pageable);

    /** Used when someone is removed from a project: their tasks there become unassigned. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Task t set t.assignee = null where t.project.id = :projectId and t.assignee.id = :userId")
    int unassignUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
