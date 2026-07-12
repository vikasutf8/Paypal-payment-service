package com.payflow.apiGateway_service.Filter;


import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class IdempotencyFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;

    public IdempotencyFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) { // CORRECTED TYPE
        ServerHttpRequest request = exchange.getRequest();

        // Only apply to POST, PUT, PATCH (mutating actions)
        if (request.getMethod() == null || !request.getMethod().matches("POST|PUT|PATCH")) {
            return chain.filter(exchange);
        }

        String idempotencyKey = request.getHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null) {
            // If key is missing, let it pass (or enforce it by returning 400 Bad Request)
            return chain.filter(exchange);
        }

        // Try to save the key in Redis. If it already exists, it's a duplicate request.
        return redisTemplate.opsForValue()
                .setIfAbsent("idempotency:" + idempotencyKey, "PROCESSING", Duration.ofHours(24))
                .flatMap(isNewKey -> {
                    if (Boolean.FALSE.equals(isNewKey)) {
                        // Key already exists in Redis -> Duplicate request!
                        exchange.getResponse().setStatusCode(HttpStatus.CONFLICT);
                        return exchange.getResponse().setComplete();
                    }
                    // Key is new, proceed to downstream service
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -1; // Run before routing filters
    }
}