package com.taskflow.repository;

import com.taskflow.model.TaskComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    Page<TaskComment> findByTaskId(Long taskId, Pageable pageable);
}
