package com.ruoyi.business.data.pdf.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;

/**
 * PDF/PPTX 原文件的私有存储。数据库只保存 private:// 逻辑引用，私有根目录不注册
 * ResourceHandler；读取必须经过服务端的受控调用链。
 */
@Service
public class DocumentPrivateStorage
{
    public static final String PRIVATE_PREFIX = "private://";
    private final Path root;

    public DocumentPrivateStorage(@Value("${business.document.private-root:D:/ruoyi/business-document-private}") String privateRoot)
    {
        this.root = Path.of(privateRoot).toAbsolutePath().normalize();
    }

    public StoredDocument store(MultipartFile file, String originalFileName) throws IOException
    {
        String extension = extension(originalFileName);
        Path target = nextTarget(extension);
        try (InputStream input = file.getInputStream())
        {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredDocument(reference(target), target);
    }

    /** 迁移既有 /profile 文档；成功后旧公开文件已不在 profile 目录。 */
    public StoredDocument moveLegacyProfileFile(String storedFilePath, String originalFileName) throws IOException
    {
        Path source = resolveLegacyProfileFile(storedFilePath);
        Path target = nextTarget(extension(originalFileName));
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredDocument(reference(target), target);
    }

    /** 兼容尚未迁移的历史记录；新上传记录只能使用 private:// 引用。 */
    public Path resolveForRead(String storedFilePath) throws IOException
    {
        if (isPrivateReference(storedFilePath)) return resolvePrivate(storedFilePath);
        return resolveLegacyProfileFile(storedFilePath);
    }

    public boolean isPrivateReference(String storedFilePath)
    {
        return storedFilePath != null && storedFilePath.startsWith(PRIVATE_PREFIX);
    }

    /** 仅允许删除私有根目录内、由本模块管理的原始文档。 */
    public void deletePrivate(String storedFilePath) throws IOException
    {
        if (!isPrivateReference(storedFilePath)) throw new IOException("文档不是私有存储文件");
        Files.deleteIfExists(resolvePrivate(storedFilePath));
    }

    public Path root()
    {
        return root;
    }

    private Path nextTarget(String extension) throws IOException
    {
        Path directory = root.resolve("documents").normalize();
        ensureInside(directory);
        Files.createDirectories(directory);
        return directory.resolve(UUID.randomUUID() + extension).normalize();
    }

    private String reference(Path target) throws IOException
    {
        Path realRoot = Files.createDirectories(root).toRealPath();
        Path normalized = target.toAbsolutePath().normalize();
        ensureInside(normalized);
        return PRIVATE_PREFIX + realRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private Path resolvePrivate(String storedFilePath) throws IOException
    {
        String relative = storedFilePath.substring(PRIVATE_PREFIX.length());
        if (relative.isBlank() || relative.contains("..") || Path.of(relative).isAbsolute())
            throw new IOException("私有文档路径无效");
        Path candidate = root.resolve(relative).normalize();
        ensureInside(candidate);
        if (!Files.isRegularFile(candidate)) throw new IOException("原文文件不存在");
        Path realRoot = Files.createDirectories(root).toRealPath();
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(realRoot)) throw new IOException("私有文档路径无效");
        return realFile;
    }

    private Path resolveLegacyProfileFile(String storedFilePath) throws IOException
    {
        String prefix = Constants.RESOURCE_PREFIX + "/";
        if (storedFilePath == null || !storedFilePath.startsWith(prefix)) throw new IOException("已保存文件路径无效");
        String relative = storedFilePath.substring(prefix.length());
        if (relative.isBlank() || relative.contains("..") || Path.of(relative).isAbsolute())
            throw new IOException("已保存文件路径无效");
        Path profile = Path.of(RuoYiConfig.getProfile()).toAbsolutePath().normalize();
        Path candidate = profile.resolve(relative).normalize();
        if (!candidate.startsWith(profile) || !Files.isRegularFile(candidate)) throw new IOException("原文文件不存在");
        Path realProfile = profile.toRealPath();
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(realProfile)) throw new IOException("已保存文件路径无效");
        return realFile;
    }

    private String extension(String originalFileName) throws IOException
    {
        if (originalFileName == null) throw new IOException("原始文件名无效");
        String lower = originalFileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return ".pdf";
        if (lower.endsWith(".pptx")) return ".pptx";
        throw new IOException("仅支持 PDF 或 PPTX 文件");
    }

    private void ensureInside(Path value) throws IOException
    {
        if (!value.toAbsolutePath().normalize().startsWith(root)) throw new IOException("私有文档路径无效");
    }

    public record StoredDocument(String reference, Path file) {}
}

