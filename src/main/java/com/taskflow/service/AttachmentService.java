package com.taskflow.service;

import com.taskflow.dto.TaskDtos.AttachmentResponse;
import com.taskflow.exception.BadRequestException;
import com.taskflow.exception.NotFoundException;
import com.taskflow.model.Attachment;
import com.taskflow.model.Task;
import com.taskflow.repository.AttachmentRepository;
import com.taskflow.repository.TaskRepository;
import com.taskflow.service.AccessService.Access;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Attachment rules:
 *  - any project participant can upload to a task they can see
 *  - an employee can list, download and delete only their OWN uploads
 *  - the project's manager and admins can list, download and delete ALL attachments in the project
 *  - anyone else gets 404
 */
@Service
public class AttachmentService {

    public record FileDownload(String filename, long sizeBytes, Resource resource) {}

    private final AttachmentRepository attachmentRepository;
    private final TaskRepository taskRepository;
    private final AccessService accessService;
    private final FileStorageService fileStorage;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             TaskRepository taskRepository,
                             AccessService accessService,
                             FileStorageService fileStorage) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.accessService = accessService;
        this.fileStorage = fileStorage;
    }

    @Transactional
    public AttachmentResponse upload(Long taskId, MultipartFile file, Authentication auth) {
        Task task = findTask(taskId);
        Access access = accessService.requireView(task.getProject(), auth);

        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is empty");
        }

        String storageKey = fileStorage.store(file);
        try {
            Attachment attachment = new Attachment();
            attachment.setTask(task);
            attachment.setUploadedBy(access.user());
            attachment.setOriginalFilename(cleanFilename(file.getOriginalFilename()));
            attachment.setContentType(truncate(file.getContentType(), 100));
            attachment.setSizeBytes(file.getSize());
            attachment.setStorageKey(storageKey);
            return AttachmentResponse.from(attachmentRepository.saveAndFlush(attachment));
        } catch (RuntimeException e) {
            fileStorage.delete(storageKey); // don't leave an orphan file if the database insert failed
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long taskId, Authentication auth) {
        Task task = findTask(taskId);
        Access access = accessService.requireView(task.getProject(), auth);

        return attachmentRepository.findByTaskIdOrderById(taskId).stream()
                .filter(a -> access.canManage() || isUploader(a, access))
                .map(AttachmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FileDownload download(Long attachmentId, Authentication auth) {
        Attachment attachment = findAccessible(attachmentId, auth);
        Resource resource = fileStorage.load(attachment.getStorageKey())
                .orElseThrow(() -> new NotFoundException("Not found"));
        return new FileDownload(attachment.getOriginalFilename(), attachment.getSizeBytes(), resource);
    }

    @Transactional
    public void delete(Long attachmentId, Authentication auth) {
        Attachment attachment = findAccessible(attachmentId, auth);
        String storageKey = attachment.getStorageKey();

        attachmentRepository.delete(attachment);
        attachmentRepository.flush();
        fileStorage.delete(storageKey);
    }

    // ---- helpers ----

    private Task findTask(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new NotFoundException("Not found"));
    }

    /** Loads the attachment only if the caller is its uploader (and still in the project), or the manager/admin. */
    private Attachment findAccessible(Long attachmentId, Authentication auth) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        Access access = accessService.requireView(attachment.getTask().getProject(), auth);
        if (!(access.canManage() || isUploader(attachment, access))) {
            throw new NotFoundException("Not found");
        }
        return attachment;
    }

    private boolean isUploader(Attachment attachment, Access access) {
        return attachment.getUploadedBy().getId().equals(access.user().getId());
    }

    /** Keeps only the last path segment and strips control characters. Used for display and downloads only. */
    private String cleanFilename(String original) {
        if (original == null) {
            return "file";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            return "file";
        }
        return truncate(name, 255);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
