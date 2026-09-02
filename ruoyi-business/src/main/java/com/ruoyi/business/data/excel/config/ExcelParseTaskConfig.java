package com.ruoyi.business.data.excel.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Excel解析任务的独立受限线程池。
 */
@Configuration
public class ExcelParseTaskConfig
{
    @Bean(name = "excelParseTaskExecutor")
    public ThreadPoolTaskExecutor excelParseTaskExecutor(
            @Value("${business.excel.executor.core-pool-size:2}") int corePoolSize,
            @Value("${business.excel.executor.max-pool-size:2}") int maxPoolSize,
            @Value("${business.excel.executor.queue-capacity:20}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("excel-parse-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
