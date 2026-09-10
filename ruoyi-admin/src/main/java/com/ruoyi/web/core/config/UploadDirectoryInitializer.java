package com.ruoyi.web.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.ruoyi.common.config.RuoYiConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在接收请求前验证上传目录可创建、可写，避免上传时才暴露权限问题。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadDirectoryInitializer implements ApplicationRunner
{
    @Override
    public void run(ApplicationArguments args)
    {
        Path root = Path.of(RuoYiConfig.getProfile()).toAbsolutePath().normalize();
        try
        {
            for (String child : List.of("import", "upload", "avatar", "download"))
                Files.createDirectories(root.resolve(child));
            Path probe = Files.createTempFile(root, ".write-probe-", ".tmp");
            try
            {
                Files.writeString(probe, "ok", StandardCharsets.US_ASCII);
            }
            finally
            {
                Files.deleteIfExists(probe);
            }
        }
        catch (IOException | SecurityException ex)
        {
            throw new IllegalStateException("上传根目录不可写，服务拒绝启动：" + root
                + "。请授权当前运行账户，或通过 RUOYI_PROFILE 指定可写目录。", ex);
        }
    }
}
