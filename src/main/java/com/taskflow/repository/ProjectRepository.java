package com.taskflow.repository;

import com.taskflow.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Projects the user manages or is a member of. */
    @Query("select distinct p from Project p left join p.members m "
            + "where p.manager.id = :userId or m.id = :userId order by p.id")
    List<Project> findAllForUser(@Param("userId") Long userId);
}
