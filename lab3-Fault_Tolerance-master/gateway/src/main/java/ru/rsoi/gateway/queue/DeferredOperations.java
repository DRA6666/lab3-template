package ru.rsoi.gateway.queue;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.*;

/** In-memory retry queue. Each successful step is removed before the next runs. */
@Component
public class DeferredOperations {
    private static final Logger log = LoggerFactory.getLogger(DeferredOperations.class);
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker;

    public DeferredOperations() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deferred-operations");
        t.setDaemon(true);
        return t;
        }));
    }

    DeferredOperations(ScheduledExecutorService worker) { this.worker = worker; }

    public void execute(String key, Runnable... steps) {
        if (submitted.add(key)) attempt(key, steps, 0);
    }

    private void attempt(String key, Runnable[] steps, int first) {
        for (int i = first; i < steps.length; i++) {
            try {
                steps[i].run();
            } catch (RuntimeException e) {
                int next = i;
                log.warn("Operation {} step {} failed; retry in 10 seconds: {}", key, i, e.getMessage());
                worker.schedule(() -> attempt(key, steps, next), 10, TimeUnit.SECONDS);
                return;
            }
        }
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
