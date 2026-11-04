package com.taskflow.controller;

import com.taskflow.dto.TaskDtos.AttachmentResponse;
import com.taskflow.service.AttachmentService;
import com.taskflow.service.AttachmentService.FileDownload;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /** multipart/form-data with a single part named "file". */
    @PostMapping(path = "/tasks/{taskId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentResponse> upload(@PathVariable Long taskId,
                                                     @RequestParam("file") MultipartFile file,
                                                     Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.upload(taskId, file, auth));
    }

    /** Employees see only their own uploads; the project manager and admins see all of them. */
    @GetMapping("/tasks/{taskId}/attachments")
    public List<AttachmentResponse> list(@PathVariable Long taskId, Authentication auth) {
        return attachmentService.list(taskId, auth);
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable Long attachmentId, Authentication auth) {
        FileDownload file = attachmentService.download(attachmentId, auth);
        // Always served as a download with a neutral type, so an uploaded HTML/SVG file can never run in the browser
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Long attachmentId, Authentication auth) {
        attachmentService.delete(attachmentId, auth);
        return ResponseEntity.noContent().build();
    }
}
