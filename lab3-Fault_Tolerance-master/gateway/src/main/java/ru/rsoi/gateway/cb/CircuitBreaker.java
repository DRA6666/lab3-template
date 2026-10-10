package ru.rsoi.gateway.cb;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;

/** Network calls run outside the state lock; stale results cannot close a new circuit. */
public class CircuitBreaker {
    private final String name;
    private final CircuitBreakerConfig config;
    private final LongSupplier clock;
    private final Deque<Boolean> window = new ArrayDeque<>();
    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private long openedAt;
    private long generation;
    private int probes;
    private int successes;

    public CircuitBreaker(String name, CircuitBreakerConfig config) {
        this(name, config, () -> System.nanoTime() / 1_000_000);
    }
    public CircuitBreaker(String name, CircuitBreakerConfig config, LongSupplier clock) {
        this.name = name;
        this.config = config;
        this.clock = clock;
    }
    public synchronized CircuitBreakerState getState() { return state; }
    public String getName() { return name; }
    public synchronized int windowSize() { return window.size(); }
    public synchronized boolean isOpen() { return state == CircuitBreakerState.OPEN; }

    public <T> T execute(Supplier<T> action) {
        long token;
        synchronized (this) {
            if (state == CircuitBreakerState.OPEN && clock.getAsLong() - openedAt >= config.waitDurationInOpenStateMs()) {
                state = CircuitBreakerState.HALF_OPEN;
                probes = successes = 0;
                generation++;
            }
            if (state == CircuitBreakerState.OPEN ||
                    (state == CircuitBreakerState.HALF_OPEN && probes >= config.permittedNumberOfCallsInHalfOpenState())) {
                throw new CircuitBreakerOpenException(name);
            }
            if (state == CircuitBreakerState.HALF_OPEN) probes++;
            token = generation;
        }
        try {
            T result = action.get();
            complete(token, true);
            return result;
        } catch (HttpClientErrorException e) {
            // A business/client error is still a response from an available service.
            complete(token, true);
            throw e;
        } catch (RuntimeException e) {
            complete(token, false);
            throw e;
        }
    }

    private synchronized void complete(long token, boolean success) {
        if (token != generation) return;
        if (state == CircuitBreakerState.HALF_OPEN) {
            if (!success) open();
            else if (++successes == config.permittedNumberOfCallsInHalfOpenState()) {
                state = CircuitBreakerState.CLOSED;
                generation++;
                window.clear();
            }
            return;
        }
        window.addLast(success);
        while (window.size() > config.slidingWindowSize()) window.removeFirst();
        long failures = window.stream().filter(b -> !b).count();
        if (!success && window.size() >= config.minimumNumberOfCalls()
                && failures * 100f / window.size() >= config.failureRateThreshold()) open();
    }
    private void open() {
        state = CircuitBreakerState.OPEN;
        openedAt = clock.getAsLong();
        generation++;
        window.clear();
    }
}
