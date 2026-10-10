package ru.rsoi.rating.controller;

import org.springframework.web.bind.annotation.*;
import ru.rsoi.rating.service.RatingService;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rating")
public class RatingController {
    private final RatingService service;
    private final ru.rsoi.rating.repo.AppliedOperationRepository operations;
    public RatingController(RatingService service, ru.rsoi.rating.repo.AppliedOperationRepository operations) {
        this.service = service;
        this.operations = operations;
    }

    @GetMapping
    public Map<String, Integer> get(@RequestHeader("X-User-Name") String username) {
        return Map.of("stars", service.getOrCreate(username).getStars());
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public synchronized Map<String, Integer> change(@RequestHeader("X-User-Name") String username,
                                       @RequestParam("delta") int delta,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String scopedKey = username + ":" + key;
        if (key != null && operations.existsById(scopedKey)) return get(username);
        if (key != null) operations.save(new ru.rsoi.rating.entity.AppliedOperation(scopedKey));
        return Map.of("stars", service.change(username, delta).getStars());
    }
}