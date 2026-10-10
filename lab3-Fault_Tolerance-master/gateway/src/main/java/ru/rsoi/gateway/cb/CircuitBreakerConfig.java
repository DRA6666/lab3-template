package ru.rsoi.gateway.cb;
public record CircuitBreakerConfig(int slidingWindowSize, int minimumNumberOfCalls,
        float failureRateThreshold, long waitDurationInOpenStateMs,
        int permittedNumberOfCallsInHalfOpenState) {
    public CircuitBreakerConfig {
        if (slidingWindowSize < 1 || minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize
                || failureRateThreshold <= 0 || failureRateThreshold > 100
                || waitDurationInOpenStateMs < 0 || permittedNumberOfCallsInHalfOpenState < 1)
            throw new IllegalArgumentException("Invalid circuit breaker configuration");
    }
    public static CircuitBreakerConfig defaults() { return new CircuitBreakerConfig(3, 3, 100f, 3000, 1); }
}
