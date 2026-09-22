package ru.lordfarif.aiagent.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lordfarif.aiagent.dto.AgentImage;

@Service
public class ImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);
    private final Path directory;

    public ImageStorageService() { this(Path.of("generated-images")); }

    public ImageStorageService(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public AgentImage save(byte[] png) {
        Path file = null;
        try {
            log.info("Image storage: userDir={}, directory={}", System.getProperty("user.dir"), directory);
            if (png == null || png.length == 0) throw new IOException("Empty image");
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Invalid storage directory");
            String id = UUID.randomUUID().toString();
            Path candidate = directory.resolve(id + ".png");
            try (var output = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file = candidate;
                output.write(png);
            }
            if (!Files.exists(file) || Files.size(file) <= 0) throw new IOException("Image was not written");
            log.info("Image storage success: imageId={}, absolutePath={}, sizeBytes={}", id, file, Files.size(file));
            return new AgentImage(id, "/api/v1/images/" + id);
        } catch (IOException | SecurityException exception) {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (IOException | SecurityException ignored) { }
            }
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORAGE_FAILED", "Could not save generated image.");
        }
    }

    public byte[] load(String imageId) {
        if (imageId == null || !imageId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_ID", "imageId must be a UUID.");
        }
        Path file = directory.resolve(UUID.fromString(imageId) + ".png");
        try {
            if (Files.isSymbolicLink(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "Image was not found.");
            }
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return input.readAllBytes();
            }
        } catch (IOException | SecurityException exception) {
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORAGE_FAILED", "Could not read generated image.");
        }
    }
}
