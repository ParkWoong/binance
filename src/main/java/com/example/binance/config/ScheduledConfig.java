package com.example.binance.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.example.binance.properties.CoinProperties;
import com.example.binance.service.KlineSocketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Configuration
@Slf4j
public class ScheduledConfig {

    private final KlineSocketService klineSocketService;
    private final CoinProperties coinProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void checkProperties(){
        final String coin = coinProperties.getSymbol();
        klineSocketService.start(coin, null);
        log.info("WebSocket started for: {}", coin);
    }
}
