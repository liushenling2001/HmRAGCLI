package com.hmrag.backend.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Component
public class IngestTaskRegistry {

    private final Map<UUID, RunningTask> running = new ConcurrentHashMap<>();

    public boolean register(UUID jobId, UUID dataSourceId) {
        return running.putIfAbsent(jobId, new RunningTask(dataSourceId)) == null;
    }

    public void attachFuture(UUID jobId, Future<?> future) {
        RunningTask task = running.get(jobId);
        if (task != null) {
            task.future = future;
        }
    }

    public void complete(UUID jobId) {
        running.remove(jobId);
    }

    public boolean isRunning(UUID jobId) {
        return running.containsKey(jobId);
    }

    public void cancelByDataSource(UUID dataSourceId) {
        for (Map.Entry<UUID, RunningTask> entry : running.entrySet()) {
            RunningTask task = entry.getValue();
            if (dataSourceId.equals(task.dataSourceId) && task.future != null) {
                task.future.cancel(true);
            }
        }
    }

    private static final class RunningTask {
        private final UUID dataSourceId;
        private volatile Future<?> future;

        private RunningTask(UUID dataSourceId) {
            this.dataSourceId = dataSourceId;
        }
    }
}
