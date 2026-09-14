package com.ruoyi.business.knowledge.service;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** POC启动时幂等补齐问答审计表，避免部署时遗漏增量SQL导致问答不可用。 */
@Component
public class KnowledgeQaSchemaInitializer implements InitializingBean
{
    private final DataSource dataSource;
    private final boolean enabled;

    public KnowledgeQaSchemaInitializer(DataSource dataSource,
        @Value("${business.knowledge.qa-schema-auto-init:true}") boolean enabled)
    {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet()
    {
        if (!enabled) return;
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("db/knowledge-qa-audit.sql"));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }
}
