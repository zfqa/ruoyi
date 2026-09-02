package com.ruoyi.business.data.excel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.business.data.excel.domain.ExcelImport;

class ExcelFileStorageTest
{
    Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException
    {
        tempDir = Path.of("target", "test-work", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(tempDir);
    }

    @AfterEach
    void deleteWorkspace() throws IOException
    {
        if (!Files.exists(tempDir))
        {
            return;
        }
        try (var paths = Files.walk(tempDir))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void resolvesOnlyFilesInsideImportDirectory() throws Exception
    {
        Path input = tempDir.resolve("import/2026/sample.csv");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "year,qty\n2026,1\n");
        ExcelFileStorage storage = new ExcelFileStorage(tempDir);

        assertEquals(input.toAbsolutePath().normalize(), storage.resolveUploadedFile("/profile/import/2026/sample.csv"));
        assertThrows(IOException.class, () -> storage.resolveUploadedFile("/profile/import/../../secret.xlsx"));
        assertThrows(IOException.class, () -> storage.resolveLocalImportFile(tempDir.resolve("secret.xlsx").toString()));
    }

    @Test
    void rejectsUnsupportedExtensions() throws Exception
    {
        Path input = tempDir.resolve("import/sample.txt");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "not a workbook");
        ExcelFileStorage storage = new ExcelFileStorage(tempDir);

        assertThrows(IOException.class, () -> storage.resolveLocalImportFile(input.toString()));
    }

    @Test
    void deletesControlledSourceAndLegacyResult() throws Exception
    {
        Path input = tempDir.resolve("import/2026/sample.xlsx");
        Path result = tempDir.resolve("excel-results/excel-task-7.json");
        Files.createDirectories(input.getParent());
        Files.createDirectories(result.getParent());
        Files.writeString(input, "fixture");
        Files.writeString(result, "{}");
        ExcelFileStorage storage = new ExcelFileStorage(tempDir);
        ExcelImport task = new ExcelImport();
        task.setId(7L);
        task.setFilePath("/profile/import/2026/sample.xlsx");

        assertTrue(Files.exists(input));
        storage.deleteTaskFiles(task);
        assertFalse(Files.exists(input));
        assertFalse(Files.exists(result));
    }
}
