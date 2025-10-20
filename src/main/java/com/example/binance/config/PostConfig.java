package com.example.binance.config;

import org.springframework.context.annotation.Configuration;

import com.example.binance.properties.DomainProperties;
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

    @PostConstruct
    public void checkProperties(){
        //log.info("Domain : {}, {}", domainProperties.getMain(), domainProperties.getGet().getTime());
        log.info("Test : {}", tradeService.test().getBody());
    }
    
}
