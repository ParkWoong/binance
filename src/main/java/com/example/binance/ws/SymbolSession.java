package com.example.binance.ws;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.example.binance.dto.BiasRegime;
import com.example.binance.dto.Candle;
import com.example.binance.enums.TimeFrame;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import okhttp3.WebSocket;

@Getter
@RequiredArgsConstructor
public class SymbolSession {
    private final String symbol;
    private final AtomicReference<BiasRegime> brRef;
    private volatile WebSocket webSocket;

    private final Map<TimeFrame, Deque<Candle>> buffers = new ConcurrentHashMap<>();

    public Deque<Candle> buffer(TimeFrame tf){
        return buffers.computeIfAbsent(tf, t -> new ArrayDeque<>());
    }

    public void setWebSocket(WebSocket ws){ this.webSocket = ws; }
}
