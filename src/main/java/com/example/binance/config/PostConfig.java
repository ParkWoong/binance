package com.example.binance.config;

import org.springframework.context.annotation.Configuration;

import com.example.binance.dto.BiasRegime;
import com.example.binance.properties.DomainProperties;
import com.example.binance.service.DirectionService;
import com.example.binance.service.TradeService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Configuration
@Slf4j
public class PostConfig {

    private final DomainProperties domainProperties;
    private final TradeService tradeService;
    private final DirectionService directionService;

    @PostConstruct
    public void checkProperties(){
        //log.info("Domain : {}, {}", domainProperties.getMain(), domainProperties.getGet().getTime());
        //log.info("Connect Test : {}", tradeService.test().getBody());
        BiasRegime br = directionService.evaluate("BTCUSDT");
        log.info("{} | {}", br.getBias(), br.getRegime());
    }
    
}
