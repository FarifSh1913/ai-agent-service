package ru.lordfarif.aiagent.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lordfarif.aiagent.service.ImageStorageService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageControllerTest {
    @TempDir Path directory;
    ImageStorageService storage;
    MockMvc mvc;

    @BeforeEach void setUp() {
        storage = new ImageStorageService(directory.resolve("generated-images"));
        mvc = MockMvcBuilders.standaloneSetup(new ImageController(storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void savesPngAndServesItAfterStorageRecreation() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();
        var image = storage.save(png);
        assertThat(UUID.fromString(image.id()).toString()).isEqualTo(image.id());
        assertThat(Files.readAllBytes(directory.resolve("generated-images").resolve(image.id() + ".png"))).isEqualTo(png);
        mvc = MockMvcBuilders.standaloneSetup(new ImageController(new ImageStorageService(directory.resolve("generated-images"))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get(image.url())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(png))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @ParameterizedTest @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "..", "550e8400-e29b-41d4-a716-446655440000.png"})
    void rejectsNonUuidIds(String id) throws Exception {
        mvc.perform(get("/api/v1/images/{id}", id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_ID"));
    }

    @Test void preventsPathTraversalAtStorageBoundary() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.load("../../private-file"))
                .isInstanceOf(ru.lordfarif.aiagent.service.AgentException.class);
    }

    @Test void missingUuidReturns404() throws Exception {
        mvc.perform(get("/api/v1/images/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test void doesNotServeSymlinks() throws Exception {
        Files.createDirectories(directory.resolve("generated-images"));
        Path outside = Files.writeString(directory.resolve("secret"), "secret");
        String id = UUID.randomUUID().toString();
        Files.createSymbolicLink(directory.resolve("generated-images").resolve(id + ".png"), outside);
        mvc.perform(get("/api/v1/images/{id}", id)).andExpect(status().isNotFound());
    }
}
