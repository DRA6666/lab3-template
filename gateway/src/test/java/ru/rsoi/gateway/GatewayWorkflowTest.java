package ru.rsoi.gateway;

import org.junit.jupiter.api.*;
import ru.rsoi.gateway.client.*;
import ru.rsoi.gateway.controller.GatewayController;
import ru.rsoi.gateway.dto.*;
import ru.rsoi.gateway.exception.ServiceUnavailableException;
import ru.rsoi.gateway.queue.DeferredOperations;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayWorkflowTest {
    private final LibraryClient library = mock(LibraryClient.class);
    private final RatingClient rating = mock(RatingClient.class);
    private final ReservationClient reservation = mock(ReservationClient.class);
    private final DeferredOperations queue = new DeferredOperations();
    private final GatewayController gateway = new GatewayController(rating, library, reservation, queue);
    private final UUID book = UUID.randomUUID(), lib = UUID.randomUUID();
    private final LocalDate till = LocalDate.of(2026, 10, 10);
    @AfterEach void close() { queue.close(); }

    private Map<String, Object> row(String status) {
        return Map.of("reservationUid", "r1", "bookUid", book.toString(), "libraryUid", lib.toString(),
                "status", status, "startDate", till.toString(), "tillDate", till.toString(), "condition", "EXCELLENT");
    }
    @Test void compensatesReservationWhenInventoryFails() {
        when(rating.getRating("u")).thenReturn(Map.of("stars", 5));
        when(reservation.countActive("u")).thenReturn(0L);
        when(library.getLibraryBook(lib.toString(), book.toString())).thenReturn(Map.of("condition", "EXCELLENT", "availableCount", 1));
        when(reservation.create(eq("u"), any())).thenReturn(row("RENTED"));
        doThrow(new IllegalStateException()).when(library).reserve(lib.toString(), book.toString());
        assertThrows(ServiceUnavailableException.class, () -> gateway.takeBook("u", new TakeBookRequest(book, lib, till)));
        var order = inOrder(reservation, library);
        order.verify(reservation).create(eq("u"), any());
        order.verify(library).reserve(lib.toString(), book.toString());
        order.verify(reservation).cancel("u", "r1");
    }
    @Test void libraryOutageDefersRatingAndStillAcceptsReturn() {
        when(reservation.list("u")).thenReturn(List.of(row("RENTED")));
        doThrow(new IllegalStateException()).when(library).returnBook(anyString(), anyString(), anyString(), anyString());
        assertEquals(204, gateway.returnBook("u", "r1", new ReturnBookRequest("EXCELLENT", till)).getStatusCode().value());
        verifyNoInteractions(rating);
    }
    @Test void appliesBothPenaltiesAfterInventoryUpdate() {
        when(reservation.list("u")).thenReturn(List.of(row("RENTED")));
        gateway.returnBook("u", "r1", new ReturnBookRequest("BAD", till.plusDays(1)));
        var order = inOrder(library, rating);
        order.verify(library).returnBook(lib.toString(), book.toString(), "BAD", "r1:return");
        order.verify(rating).changeRating("u", -20, "r1:return");
    }
    @Test void repeatedReturnDoesNotChangeInventoryOrRating() {
        when(reservation.list("u")).thenReturn(List.of(row("RETURNED")));
        assertEquals(204, gateway.returnBook("u", "r1", new ReturnBookRequest("EXCELLENT", till)).getStatusCode().value());
        verifyNoInteractions(library, rating);
    }
    @Test void missingLibraryOnlyProducesUidFallbacks() {
        when(reservation.list("u")).thenReturn(List.of(row("RENTED")));
        when(library.getBook(anyString())).thenThrow(new IllegalStateException());
        when(library.getLibrary(anyString())).thenThrow(new IllegalStateException());
        var body = gateway.listReservations("u").getBody().get(0);
        assertEquals(Map.of("bookUid", book.toString()), body.get("book"));
        assertEquals(Map.of("libraryUid", lib.toString()), body.get("library"));
    }
}
