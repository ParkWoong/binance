package com.example.binance.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.binance.dto.BiasRegime;
import com.example.binance.service.DirectionService;
import com.example.binance.service.KlineSocketService;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Configuration
@Slf4j
@EnableScheduling
public class ScheduledConfig {

    private final DirectionService directionService;
    private final KlineSocketService klineSocketService;
    private static final String coin = "ETHUSDT";

    @EventListener(ApplicationReadyEvent.class)
    public void checkProperties(){
    
        BiasRegime br = directionService.evaluate(coin);
        klineSocketService.start(coin, br);
        log.info("{} | {}", br.getBias(), br.getRegime());
    }

    @Scheduled(cron = "0 0 */4 * * *", zone = "Australia/Sydney")
    public void refreshRegime(){
        
        BiasRegime newBr = directionService.evaluate(coin);
        klineSocketService.updateBiasRegime(coin, newBr);

        log.info("[Refresh] {} | {}", newBr.getBias(), newBr.getRegime());
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Australia/Sydney")
    public void refreshBias(){
        
        BiasRegime newBr = directionService.evaluate(coin);
        klineSocketService.updateBiasRegime(coin, newBr);

        log.info("[Refresh] {} | {}", newBr.getBias(), newBr.getRegime());
    }
    
}
