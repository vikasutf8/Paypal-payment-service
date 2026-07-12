package com.payflow.user_service.CircuitBreaker.Controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/userService")
    public Mono<Map<String, String>> userServiceFallback() {
        return Mono.just(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "message", "User Service is currently down. Please try again later."
        ));
    }
}
