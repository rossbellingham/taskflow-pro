package com.taskflow.repository;

import com.taskflow.model.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Projects the user belongs to as a member, plus (when includeManaged is true) projects they manage.
     * includeManaged is false for users who no longer hold ROLE_MANAGER, matching AccessService.
     */
    @Query(value = "select distinct p from Project p left join p.members m "
            + "where m.id = :userId or (:includeManaged = true and p.manager.id = :userId)",
            countQuery = "select count(distinct p) from Project p left join p.members m "
                    + "where m.id = :userId or (:includeManaged = true and p.manager.id = :userId)")
    Page<Project> findAllForUser(@Param("userId") Long userId,
                                 @Param("includeManaged") boolean includeManaged,
                                 Pageable pageable);
}
