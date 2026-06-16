package io.labs64.audit.config;

import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class PipelineExecutorConfig {

    /** Bounded pool for fanning a single event out across its matching pipelines. */
    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("pipeline-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator(ContextSnapshotFactory.builder().build()));
        executor.initialize();
        return executor;
    }
}
