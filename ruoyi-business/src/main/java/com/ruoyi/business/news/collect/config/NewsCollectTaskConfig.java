package com.ruoyi.business.news.collect.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class NewsCollectTaskConfig
{
    @Bean("newsCollectTaskExecutor")
    public ThreadPoolTaskExecutor newsCollectTaskExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("news-collect-"); executor.setCorePoolSize(2); executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20); executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize(); return executor;
    }
}


