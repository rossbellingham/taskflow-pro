package com.taskflow.repository;

import com.taskflow.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTaskIdOrderById(Long taskId);

    /** The on-disk file names of every attachment in a project, so they can be deleted with it. */
    @Query("select a.storageKey from Attachment a where a.task.project.id = :projectId")
    List<String> findStorageKeysByProjectId(@Param("projectId") Long projectId);
}
