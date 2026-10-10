package ru.rsoi.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import ru.rsoi.gateway.cb.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {
    private final AtomicLong clock = new AtomicLong();
    private final CircuitBreaker cb = new CircuitBreaker("test", CircuitBreakerConfig.defaults(), clock::get);
    private void fail() {
        assertThrows(IllegalStateException.class, () -> cb.execute(() -> { throw new IllegalStateException(); }));
    }
    private void open() { fail(); fail(); fail(); }

    @Test void skipsRequestAfterThreeFailuresAndRecoversAfterTimeout() {
        open();
        assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> { org.junit.jupiter.api.Assertions.fail("Must not call backend"); return 0; }));
        clock.set(3000);
        assertEquals(42, cb.execute(() -> 42));
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
    }
    @Test void failedProbeOpensAgain() {
        open(); clock.set(3000); fail();
        assertTrue(cb.isOpen());
        assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> 42));
    }
    @Test void clientErrorsDoNotTripCircuit() {
        for (int i = 0; i < 5; i++) {
            assertThrows(HttpClientErrorException.class, () -> cb.execute(() -> {
                throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
            }));
        }
        assertFalse(cb.isOpen());
    }
    @Test void onlyOneConcurrentProbeAndRejectedCallsDoNotReopenIt() throws Exception {
        open(); clock.set(3000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> probe = executor.submit(() -> cb.execute(() -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return 42;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> 0));
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
            release.countDown();
            assertEquals(42, probe.get(5, TimeUnit.SECONDS));
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
