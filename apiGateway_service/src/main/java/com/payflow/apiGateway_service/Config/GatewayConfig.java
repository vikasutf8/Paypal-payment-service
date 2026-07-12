package com.payflow.apiGateway_service.Config;



import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class GatewayConfig {

    // Rate Limiter: Limits requests per IP address (or user token)
    @Bean
    public org.springframework.cloud.gateway.filter.ratelimit.KeyResolver userKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null ?
                        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "anonymous"
        );
    }

    // Tracing Pre-Filter: Generates a Trace ID if not provided by client and adds to headers
    @Bean
    public GlobalFilter traceIdFilter() {
        return (exchange, chain) -> {
            String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString();
            }
            // Add trace id to request going to downstream service
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-Trace-Id", traceId)
                    .build();

            // Add trace id to response coming back to client
            exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }
}
