package com.ruoyi.business.knowledge.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class KnowledgeTaskConfig
{
    @Bean(name = "knowledgeTaskExecutor")
    public ThreadPoolTaskExecutor knowledgeTaskExecutor(
        @Value("${business.knowledge.executor.core-pool-size:2}") int core,
        @Value("${business.knowledge.executor.max-pool-size:2}") int max,
        @Value("${business.knowledge.executor.queue-capacity:20}") int capacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("knowledge-ingest-");
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
