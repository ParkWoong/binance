package com.example.binance.controller;

import org.springframework.web.bind.annotation.RestController;

import com.example.binance.service.KlineSocketService;
import com.example.binance.utils.Notifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequiredArgsConstructor
@Slf4j
public class BinanceScanController {
    
    private final KlineSocketService klineSocketService;
    private final Notifier notifier;

    @GetMapping("/trade/test")
    public String scan(@RequestParam final String coin){
        
        log.info("Trading Start : {}", coin);
        
        klineSocketService.start(coin, null);
        
        return "WebSocket started for: " + coin;
    }

    @GetMapping("/session/state")
    public String getSessionState() {
        return klineSocketService.printSessionStates();
    }

    @GetMapping("/session/close")
    public void closeSession(@RequestParam String symbol) {
        klineSocketService.close(symbol);
    }

    @GetMapping("/telegram")
    public void sendMessage(@RequestParam String message) {
        notifier.send(message);
    }
}
