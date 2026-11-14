package com.taskflow.repository;

import com.taskflow.model.TaskComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    Page<TaskComment> findByTaskId(Long taskId, Pageable pageable);

    /**
     * Comments that mention the user, limited to projects they can still see: as a member, or as the
     * manager (only when includeManaged is true, i.e. they still hold ROLE_MANAGER, matching AccessService).
     * Someone removed from a project stops seeing its mentions.
     */
    @Query(value = "select c from TaskComment c join c.mentions mention "
            + "where mention.id = :userId and ("
            + "(c.task.project.manager.id = :userId and :includeManaged = true) "
            + "or exists (select 1 from Project p join p.members pm "
            + "where p.id = c.task.project.id and pm.id = :userId))",
            countQuery = "select count(c) from TaskComment c join c.mentions mention "
                    + "where mention.id = :userId and ("
                    + "(c.task.project.manager.id = :userId and :includeManaged = true) "
                    + "or exists (select 1 from Project p join p.members pm "
                    + "where p.id = c.task.project.id and pm.id = :userId))")
    Page<TaskComment> findMentionsOf(@Param("userId") Long userId,
                                     @Param("includeManaged") boolean includeManaged,
                                     Pageable pageable);
}
