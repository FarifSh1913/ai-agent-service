package ru.lordfarif.aiagent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.lordfarif.aiagent.service.ImageStorageService;

@RestController
public class ImageController {
    private final ImageStorageService storage;

    public ImageController(ImageStorageService storage) { this.storage = storage; }

    @GetMapping("/api/v1/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String imageId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").body(storage.load(imageId));
    }
}
