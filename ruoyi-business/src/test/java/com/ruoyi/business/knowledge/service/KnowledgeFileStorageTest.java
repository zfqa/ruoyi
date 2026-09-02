package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeFileStorageTest
{
    Path tempDir;

    @BeforeEach
    void createWorkspaceTemp() throws IOException
    {
        tempDir = Files.createTempDirectory(Path.of("target"), "knowledge-storage-test-");
    }

    @AfterEach
    void cleanWorkspaceTemp() throws IOException
    {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var paths = Files.walk(tempDir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void savesPdfInsideControlledKnowledgeDirectory() throws Exception
    {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf",
            "%PDF-1.7\nminimal".getBytes(StandardCharsets.US_ASCII));

        KnowledgeFileStorage.StoredFile stored = storage.savePdf(file);

        assertTrue(Files.isRegularFile(stored.path()));
        assertTrue(stored.path().startsWith(tempDir.toAbsolutePath().normalize().resolve("knowledge")));
        assertEquals("report.pdf", stored.originalName());
        assertEquals(64, stored.sha256().length());
    }

    @Test
    void rejectsRenamedNonPdfContent()
    {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf",
            "not a pdf".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> storage.savePdf(file));
        assertTrue(error.getMessage().contains("有效PDF"));
    }

    @Test
    void newsSnapshotUsesContentHashAsStableName() throws Exception
    {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(tempDir.toString());
        String text = "固定新闻正文";
        String hash = storage.sha256(text);

        Path first = storage.saveNewsSnapshot(text, hash);
        Path second = storage.saveNewsSnapshot(text, hash);

        assertEquals(first, second);
        assertEquals(text, Files.readString(first, StandardCharsets.UTF_8));
    }
}
