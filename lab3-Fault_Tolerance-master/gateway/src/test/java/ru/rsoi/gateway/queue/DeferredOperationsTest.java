package ru.rsoi.gateway.queue;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

class DeferredOperationsTest {
    @Test void retriesFailedStepAfterTenSecondsBeforeContinuing() {
        ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        DeferredOperations queue = new DeferredOperations(worker);
        Runnable library = mock(Runnable.class), rating = mock(Runnable.class);
        doThrow(new IllegalStateException()).doNothing().when(library).run();
        queue.execute("return", library, rating);
        verifyNoInteractions(rating);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(worker).schedule(retry.capture(), eq(10L), eq(TimeUnit.SECONDS));
        retry.getValue().run();
        var order = inOrder(library, rating);
        order.verify(library, times(2)).run();
        order.verify(rating).run();
        queue.execute("return", library, rating);
        verify(library, times(2)).run();
        verify(rating, times(1)).run();
    }
    @Test void ratingRetryDoesNotRepeatSuccessfulInventoryUpdate() {
        ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        DeferredOperations queue = new DeferredOperations(worker);
        Runnable library = mock(Runnable.class), rating = mock(Runnable.class);
        doThrow(new IllegalStateException()).doNothing().when(rating).run();
        queue.execute("return", library, rating);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(worker).schedule(retry.capture(), eq(10L), eq(TimeUnit.SECONDS));
        retry.getValue().run();
        verify(library, times(1)).run();
        verify(rating, times(2)).run();
    }
}
