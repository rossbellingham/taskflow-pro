package com.taskflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores uploaded bytes in a folder on disk under a random UUID name.
 * The user-supplied filename is never used on disk, which rules out path traversal and name clashes.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path root;

    public FileStorageService(@Value("${app.storage.directory:./data/uploads}") String directory) throws IOException {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /** Saves the file and returns its storage key. */
    public String store(MultipartFile file) {
        String key = UUID.randomUUID().toString();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
        return key;
    }

    public Optional<Resource> load(String key) {
        Path path = resolve(key);
        if (!Files.isReadable(path)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(path));
    }

    /** Best effort: a missing file is not an error. */
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
