package com.example.binance.ws;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.example.binance.dto.Candle;
import com.example.binance.dto.BootStrapEnv;
import com.example.binance.dto.TradeScenario;
import com.example.binance.enums.TimeFrame;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import okhttp3.WebSocket;

@Getter
@RequiredArgsConstructor
public class SymbolSession {
    private final String symbol;
    private final AtomicReference<BootStrapEnv> h1EnvRef = new AtomicReference<BootStrapEnv>();
    private volatile WebSocket webSocket;

    private final Map<TimeFrame, Deque<Candle>> buffers = new ConcurrentHashMap<>();
    private final Map<TimeFrame, TradeScenario> activeScenarios = new ConcurrentHashMap<>();

    // 5분봉 활성화 상태 관리
    private volatile boolean m5Active = false;
    private volatile String lastM15TriggerSide = null; // "LONG" or "SHORT"
    private volatile long lastM15TriggerTime = 0L; // 마지막 15분봉 트리거 시간

    public Deque<Candle> buffer(TimeFrame tf){
        return buffers.computeIfAbsent(tf, t -> new ArrayDeque<>());
    }

    public void setWebSocket(WebSocket ws){ this.webSocket = ws; }

    public void activateM5(String side, long triggerTime) {
        this.m5Active = true;
        this.lastM15TriggerSide = side;
        this.lastM15TriggerTime = triggerTime;
    }

    public void deactivateM5() {
        this.m5Active = false;
        this.lastM15TriggerSide = null;
        this.lastM15TriggerTime = 0L;
    }

    public TradeScenario getScenario(TimeFrame tf) {
        return activeScenarios.get(tf);
    }

    public void setScenario(TimeFrame tf, TradeScenario scenario) {
        if (scenario == null) {
            activeScenarios.remove(tf);
        } else {
            activeScenarios.put(tf, scenario);
        }
    }
}
