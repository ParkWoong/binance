package com.example.binance.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.example.binance.dto.BiasRegime;
import com.example.binance.service.DirectionService;
import com.example.binance.service.KlineSocketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Configuration
@Slf4j
public class PostConfig {

    private final DirectionService directionService;
    private final KlineSocketService klineSocketService;

    @EventListener(ApplicationReadyEvent.class)
    public void checkProperties(){
        final String coin = "BTCUSDT";

        BiasRegime br = directionService.evaluate(coin);
        klineSocketService.start(coin, br);
        log.info("{} | {}", br.getBias(), br.getRegime());
    }
    
}
