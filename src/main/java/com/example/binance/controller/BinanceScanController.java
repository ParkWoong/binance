package com.example.binance.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.example.binance.dto.BiasRegime;
import com.example.binance.service.DirectionService;
import com.example.binance.service.TradeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequiredArgsConstructor
@Slf4j
public class BinanceScanController {

    private final TradeService tradeService;
    private final DirectionService directionService;
    
    @GetMapping("/trade/test")
    public String scan(@RequestParam final String coin){
        
        log.info("Trading Start : {}", coin);
        
        BiasRegime br = directionService.evaluate(coin);

        
        return "bias=" + br.getBias() + ", regime=" + br.getRegime();
    }


}
