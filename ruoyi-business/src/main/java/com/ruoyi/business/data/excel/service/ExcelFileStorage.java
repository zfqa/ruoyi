package com.ruoyi.business.data.excel.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.business.data.excel.domain.ExcelImport;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;

/**
 * 解析文件路径解析与清理。所有用户可控路径都必须留在 profile/import 下。
 */
@Component
public class ExcelFileStorage
{
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xlsx", "xlsm", "csv");

    private final Path profileRoot;
    private final Path importRoot;
    private final Path resultRoot;
    private final boolean includeFormerResultLocation;

    @Autowired
    public ExcelFileStorage(@Value("${ruoyi.profile}") String profileRoot)
    {
        this(Path.of(profileRoot), true);
    }

    ExcelFileStorage(Path profileRoot)
    {
        this(profileRoot, false);
    }

    private ExcelFileStorage(Path profileRoot, boolean includeFormerResultLocation)
    {
        this.profileRoot = profileRoot.toAbsolutePath().normalize();
        this.importRoot = this.profileRoot.resolve("import").normalize();
        this.resultRoot = this.profileRoot.resolve("excel-results").normalize();
        this.includeFormerResultLocation = includeFormerResultLocation;
    }

    public Path resolveUploadedFile(String resourcePath) throws IOException
    {
        if (StringUtils.isEmpty(resourcePath) || !resourcePath.startsWith(Constants.RESOURCE_PREFIX + "/"))
        {
            throw new IOException("上传文件路径无效");
        }
        String relativePath = resourcePath.substring(Constants.RESOURCE_PREFIX.length());
        while (relativePath.startsWith("/") || relativePath.startsWith("\\"))
        {
            relativePath = relativePath.substring(1);
        }
        return validateInputPath(profileRoot.resolve(relativePath));
    }

    public Path resolveLocalImportFile(String filePath) throws IOException
    {
        if (StringUtils.isEmpty(filePath))
        {
            throw new IOException("filePath不能为空");
        }
        Path supplied = Path.of(filePath);
        Path candidate = supplied.isAbsolute() ? supplied : importRoot.resolve(supplied);
        return validateInputPath(candidate);
    }

    public Path legacyResultFile(Long taskId)
    {
        return resultRoot.resolve("excel-task-" + taskId + ".json").normalize();
    }

    public Path findLegacyResultFile(Long taskId)
    {
        for (Path candidate : legacyResultFiles(taskId))
        {
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
        }
        return legacyResultFile(taskId);
    }

    public void deleteTaskFiles(ExcelImport task)
    {
        if (task == null)
        {
            return;
        }
        deleteIfControlled(task.getFilePath());
        for (Path resultFile : legacyResultFiles(task.getId()))
        {
            try
            {
                Files.deleteIfExists(resultFile);
            }
            catch (IOException ignored)
            {
                // 数据库记录删除不应因遗留缓存文件清理失败而回滚。
            }
        }
    }

    private List<Path> legacyResultFiles(Long taskId)
    {
        if (!includeFormerResultLocation)
        {
            return List.of(legacyResultFile(taskId));
        }
        Path formerRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path formerParent = formerRoot.getParent();
        Path formerResult = (formerParent == null ? formerRoot : formerParent)
                .resolve("excel-results/excel-task-" + taskId + ".json").normalize();
        return formerResult.equals(legacyResultFile(taskId))
                ? List.of(legacyResultFile(taskId))
                : List.of(legacyResultFile(taskId), formerResult);
    }

    private Path validateInputPath(Path inputPath) throws IOException
    {
        Path normalized = inputPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(importRoot))
        {
            throw new IOException("只允许解析上传目录中的文件");
        }
        String extension = extension(normalized);
        if (!ALLOWED_EXTENSIONS.contains(extension))
        {
            throw new IOException("仅支持 .xlsx、.xlsm 和 .csv 文件");
        }
        if (!Files.isRegularFile(normalized))
        {
            throw new IOException("文件不存在");
        }
        return normalized;
    }

    private void deleteIfControlled(String storedPath)
    {
        if (StringUtils.isEmpty(storedPath))
        {
            return;
        }
        try
        {
            Path input = storedPath.startsWith(Constants.RESOURCE_PREFIX + "/")
                    ? resolveUploadedFile(storedPath)
                    : resolveLocalImportFile(storedPath);
            Files.deleteIfExists(input);
        }
        catch (IOException | RuntimeException ignored)
        {
            // 拒绝清理受控目录以外的路径，也不影响数据库删除结果。
        }
    }

    private String extension(Path path)
    {
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
