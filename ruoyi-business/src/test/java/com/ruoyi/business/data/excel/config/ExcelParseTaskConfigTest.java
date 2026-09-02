package com.ruoyi.business.data.excel.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ExcelParseTaskConfigTest
{
    @Test
    void rejectsWorkWhenWorkerAndQueueAreBothFull() throws Exception
    {
        ThreadPoolTaskExecutor executor = new ExcelParseTaskConfig().excelParseTaskExecutor(1, 1, 1);
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            executor.execute(() -> {
                started.countDown();
                try
                {
                    release.await();
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.execute(() -> { });

            assertThrows(RuntimeException.class, () -> executor.execute(() -> { }));
        }
        finally
        {
            release.countDown();
            executor.shutdown();
        }
    }
}
