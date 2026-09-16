package com.ruoyi.business.vehicle.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class VehicleCollectTaskConfig
{
    @Bean("vehicleCollectTaskExecutor")
    public ThreadPoolTaskExecutor vehicleCollectTaskExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("vehicle-collect-"); executor.setCorePoolSize(1); executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10); executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize(); return executor;
    }
}
