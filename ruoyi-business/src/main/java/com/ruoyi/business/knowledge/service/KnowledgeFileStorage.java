package com.ruoyi.business.knowledge.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class KnowledgeFileStorage
{
    private final Path root;

    public KnowledgeFileStorage(@Value("${ruoyi.profile}") String profile)
    {
        this.root = Path.of(profile).toAbsolutePath().normalize().resolve("knowledge");
    }

    public StoredFile savePdf(MultipartFile file) throws IOException
    {
        String original = file.getOriginalFilename() == null ? "document.pdf" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".pdf"))
        {
            throw new IOException("仅支持PDF文件");
        }
        byte[] signature = new byte[5];
        try (InputStream input = file.getInputStream())
        {
            if (input.read(signature) != 5 || !"%PDF-".equals(new String(signature, java.nio.charset.StandardCharsets.US_ASCII)))
            {
                throw new IOException("文件内容不是有效PDF");
            }
        }
        LocalDate today = LocalDate.now();
        Path dir = root.resolve("pdf").resolve(String.valueOf(today.getYear()))
            .resolve(String.format("%02d", today.getMonthValue())).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + ".pdf").normalize();
        ensureControlled(target);
        try (InputStream input = file.getInputStream())
        {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(target, sha256(target), original);
    }

    public Path saveNewsSnapshot(String content, String hash) throws IOException
    {
        Path dir = root.resolve("news").resolve(hash.substring(0, 2)).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(hash + ".txt").normalize();
        ensureControlled(target);
        Files.writeString(target, content, java.nio.charset.StandardCharsets.UTF_8);
        return target;
    }

    public void delete(Path path)
    {
        if (path == null) return;
        try
        {
            ensureControlled(path);
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // 数据库去重结果优先，临时文件清理失败不覆盖业务异常。
        }
    }

    public String sha256(Path path) throws IOException
    {
        try (InputStream input = Files.newInputStream(path))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public String sha256(String text)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private void ensureControlled(Path path) throws IOException
    {
        if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IOException("知识库文件路径越界");
    }

    public record StoredFile(Path path, String sha256, String originalName) {}
}
