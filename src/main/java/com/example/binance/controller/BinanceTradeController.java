package com.example.binance.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.example.binance.service.TradeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequiredArgsConstructor
@Slf4j
public class BinanceTradeController {

    private final TradeService tradeService;
    
    @GetMapping("/trade/test")
    public ResponseEntity<?> testTrade(@RequestParam final String coin){
        
        log.info("Trading Start : {}", coin);
        
        return tradeService.test();
    }
}
