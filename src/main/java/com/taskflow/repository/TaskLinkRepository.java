package com.taskflow.repository;

import com.taskflow.model.TaskLink;
import com.taskflow.model.TaskLinkType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskLinkRepository extends JpaRepository<TaskLink, Long> {

    List<TaskLink> findBySourceIdOrTargetId(Long sourceId, Long targetId);

    boolean existsBySourceIdAndTargetIdAndType(Long sourceId, Long targetId, TaskLinkType type);
}
