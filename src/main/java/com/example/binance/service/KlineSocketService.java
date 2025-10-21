package com.example.binance.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.example.binance.dto.BiasRegime;
import com.example.binance.dto.Candle;
import com.example.binance.enums.TimeFrame;
import com.example.binance.properties.DomainProperties;
import com.example.binance.ws.SymbolSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class KlineSocketService {
    private final DomainProperties domain;
    private final TriggerService triggerService;
    private final ObjectMapper om = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();

    private final Map<String, SymbolSession> sessions = new ConcurrentHashMap<>();

    public SymbolSession start(final String symbol, BiasRegime initial){
        if(sessions.containsKey(symbol)){
            return sessions.get(symbol);
        }

        SymbolSession sess = new SymbolSession(symbol, new AtomicReference<BiasRegime>(initial));

        sessions.put(symbol, sess);

        final String streams = new StringBuilder(symbol.toLowerCase())
                                            .append("@kline_15m/")
                                            .append(symbol.toLowerCase())
                                            .append("@kline_1h")
                                            .toString();
        
        final String url = new StringBuilder(domain.getWs())
                                            .append("?streams=")
                                            .append(streams)
                                            .toString();
                                            
        Request reqe = new Request.Builder().url(url).build();

        WebSocket ws = client.newWebSocket(reqe, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response){
                log.info("WS open : {}", symbol);
                sess.setWebSocket(webSocket);
            }

            public void onMessage(WebSocket webSocket, String text) {

                try {
                    JsonNode root = om.readTree(text);
                    JsonNode k = root.path("data").path("k");
                    if (k.isMissingNode()) return;

                    boolean isFinal = k.path("x").asBoolean();
                    if (!isFinal) return; // ✅ 마감봉만 처리

                    log.info("[RAW Message] {}", text);

                    String interval = k.path("i").asText(); // "15m" or "1h"
                    long openTime = k.path("t").asLong();
                    double o = k.path("o").asDouble();
                    double h = k.path("h").asDouble();
                    double l = k.path("l").asDouble();
                    double c = k.path("c").asDouble();
                    double v = k.path("v").asDouble();

                    TimeFrame tf;
                    if ("15m".equals(interval)) {
                        tf = TimeFrame.M15;
                    } else {
                        tf = TimeFrame.H1;
                    }

                    // 버퍼 업데이트
                    java.util.Deque<Candle> dq = sess.buffer(tf);
                    dq.addLast(new Candle(openTime, o, h, l, c, v));
                    while (dq.size() > 300) {
                        dq.removeFirst();
                    }

                    java.util.Deque<Candle> dq1h = sess.getBuffers().get(TimeFrame.H1);
                    java.util.Deque<Candle> dq15m = sess.getBuffers().get(TimeFrame.M15);

                    if (dq1h != null && dq15m != null && dq1h.size() >= 60 && dq15m.size() >= 60) {
                        triggerService.onCandleClosed(sess);
                    }

                } catch (Exception e) {
                    log.warn("WS parse error {}: {}", symbol, e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("WS failure {} {}", symbol, t.getMessage());
                close(symbol);
                start(symbol, sess.getBrRef().get());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log.info("WS closing {} {} {}", symbol, code, reason);
                close(symbol);
            }
        });

        sess.setWebSocket(ws);
        return sess;
    }

    public void close(String symbol){
        SymbolSession sess = sessions.remove(symbol);
        if(sess != null && sess.getWebSocket() != null){
            try {
                sess.getWebSocket().close(1000, "bye");
            } catch (Exception e) {}
        }
    }

    /** 외부 스케줄러(1D/4H 마감)에서 Bias/Regime 갱신 */
    public void updateBiasRegime(String symbol, BiasRegime br) {
        SymbolSession sess = sessions.get(symbol);
        if (sess != null) {
            sess.getBrRef().set(br);
        }
    }

    public Map<String, SymbolSession> sessions() {
        return sessions;
    }

    /* 현재 소켓의 세션상태 확인 */
    public String printSessionStates(){
        
        String state = null;

        for(Map.Entry<String, SymbolSession> entry : sessions.entrySet()){
            final String symbol = entry.getKey();
            SymbolSession sess = entry.getValue();
            boolean active = sess.getWebSocket() != null;

            state = new StringBuilder("Symbol : ")
                                    .append(symbol)
                                    .append(" | Active : ")
                                    .append(active)
                                    .append(" | Buffers : ")
                                    .append(sess.getBuffers().keySet())
                                    .toString();

        }

        return state;
    }
}
