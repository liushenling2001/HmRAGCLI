package com.hmrag.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class QueryExecutionConfig {

    @Bean(name = "queryTaskExecutor")
    public AsyncTaskExecutor queryTaskExecutor(AppProperties appProperties) {
        AppProperties.Query query = appProperties.query();
        int threads = Math.max(2, query.executorThreads());
        int queueCapacity = Math.max(20, query.executorQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hmrag-query-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "maintenanceTaskExecutor")
    public AsyncTaskExecutor maintenanceTaskExecutor(AppProperties appProperties) {
        AppProperties.Maintenance maintenance = appProperties.maintenance();
        int threads = Math.max(1, maintenance.executorThreads());
        int queueCapacity = Math.max(4, maintenance.executorQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hmrag-maint-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "ingestTaskExecutor")
    public AsyncTaskExecutor ingestTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hmrag-ingest-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "domainKnowledgeTaskExecutor")
    public AsyncTaskExecutor domainKnowledgeTaskExecutor(AppProperties appProperties) {
        AppProperties.DomainKnowledge domainKnowledge = appProperties.domainKnowledge();
        int threads = Math.max(1, domainKnowledge.executorThreads());
        int queueCapacity = Math.max(4, domainKnowledge.executorQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hmrag-domain-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "knowledgeGraphTaskExecutor")
    public AsyncTaskExecutor knowledgeGraphTaskExecutor(AppProperties appProperties) {
        AppProperties.KnowledgeGraph knowledgeGraph = appProperties.knowledgeGraph();
        int threads = Math.max(1, knowledgeGraph == null ? 1 : knowledgeGraph.batchSize());
        int queueCapacity = Math.max(4, threads * 8);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hmrag-kg-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
