package com.example.binance.service;

import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.example.binance.dto.BiasRegime;
import com.example.binance.dto.Candle;
import com.example.binance.dto.H1Env;
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
    // private final TriggerService triggerService;
    private final IndicatorService ind;
    private final BInanceRestService bInanceRestService;
    private final OnlyH1Service onlyH1Service;

    private final ObjectMapper om = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();

    private final Map<String, SymbolSession> sessions = new ConcurrentHashMap<>();

    public SymbolSession start(final String symbol, BiasRegime initial) {

        if (sessions.containsKey(symbol)) {
            return sessions.get(symbol);
        }

        SymbolSession sess = new SymbolSession(symbol, new AtomicReference<BiasRegime>(initial));

        sessions.put(symbol, sess);

        bootStrapH1Env(symbol, sess);

        bootstrapM15Buffer(symbol, sess, 200);

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
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("WS open : {}", symbol);
                sess.setWebSocket(webSocket);
            }

            public void onMessage(WebSocket webSocket, String text) {

                try {
                    JsonNode root = om.readTree(text);
                    JsonNode k = root.path("data").path("k");
                    if (k.isMissingNode())
                        return;

                    boolean isFinal = k.path("x").asBoolean();
                    if (!isFinal)
                        return; // ✅ 마감봉만 처리

                    log.info("[RAW Message] {}", text);

                    String interval = k.path("i").asText(); // "15m" or "1h"
                    long openTime = k.path("t").asLong();
                    long closeTime = k.path("T").asLong();
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
                    Deque<Candle> dq = sess.buffer(tf);

                    if(!dq.isEmpty()){
                        Candle last = dq.peekLast();
                        if(last.getCloseTime() == closeTime) return;
                        if(last.getCloseTime() > closeTime) return;
                    }

                    dq.addLast(new Candle(openTime, closeTime, o, h, l, c, v));
                    while (dq.size() > 300) {
                        dq.removeFirst();
                    }

                    // ✅ 2-a) H1 마감 들어오면 환경 갱신 (다음 한 시간 유효)
                    if (tf == TimeFrame.H1) {
                        refreshH1EnvFromBuffers(sess);
                    }

                    // ✅ 2-b) M15 마감 들어오면 H1Env를 게이트로 트리거 평가
                    if (tf == TimeFrame.M15) {
                        log.info("===== Get Trigger =====");
                        // triggerService.onCandleClosedWithH1Env(sess);
                        onlyH1Service.onCandleClosed_H1M15(sess);
                    }

                    // java.util.Deque<Candle> dq1h = sess.getBuffers().get(TimeFrame.H1);
                    // java.util.Deque<Candle> dq15m = sess.getBuffers().get(TimeFrame.M15);

                    // if (dq1h != null && dq15m != null && dq1h.size() >= 60 && dq15m.size() >= 60)
                    // {
                    // triggerService.onCandleClosed(sess);
                    // }

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

    public void close(String symbol) {
        SymbolSession sess = sessions.remove(symbol);
        if (sess != null && sess.getWebSocket() != null) {
            try {
                sess.getWebSocket().close(1000, "bye");
            } catch (Exception e) {
            }
        }
    }

    /** 외부 스케줄러(1D/4H 마감)에서 Bias/Regime 갱신 */
    public void updateBiasRegime(String symbol, BiasRegime br) {

        SymbolSession sess = sessions.get(symbol);

        // final String time = getNowMinute();

        if (sess != null) {
            sess.getBrRef().set(br);
            // log.info("[Refresh] {} | {} | {}", time, sess.getBrRef().get().getBias(),
            // sess.getBrRef().get().getRegime());
        }
    }

    public Map<String, SymbolSession> sessions() {
        return sessions;
    }

    /* 현재 소켓의 세션상태 확인 */
    public String printSessionStates() {

        String state = null;

        for (Map.Entry<String, SymbolSession> entry : sessions.entrySet()) {
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

    private void bootStrapH1Env(String symbol, SymbolSession session) {
        List<Candle> h1 = bInanceRestService.klines(symbol, TimeFrame.H1, 200);
        if (h1 == null || h1.size() < 60) {
            log.warn("H1 is not enough candles : {}", symbol);
            return;
        }

        Deque<Candle> dq = session.buffer(TimeFrame.H1);
        dq.clear();
        for (Candle c : h1) {
            dq.addLast(c);
        }

        H1Env env = computeH1Env(h1);
        session.getH1EnvRef().set(env);

        log.info("H1 Value from first api : {}, longOk = {}, shortOk = {}", symbol, env.isLongOk(), env.isShortOk());
    }

    private void bootstrapM15Buffer(final String symbol, SymbolSession sess, int limit) {
        try {
            List<Candle> m15 = bInanceRestService.klines(symbol, TimeFrame.M15, limit);
            if (m15 == null || m15.isEmpty()) {
                log.warn("M15 bootstrap is empty : {}", symbol);
                return;
            }

            m15.sort(Comparator.comparingLong(Candle::getCloseTime));

            Deque<Candle> dq = sess.buffer(TimeFrame.M15);
            dq.clear();

            long prevClose = -1L;
            for (Candle cd : m15) {
                long ct = cd.getCloseTime();
                if (ct <= prevClose) continue; // 중복 또는 역순 드롭
                dq.addLast(cd);
                prevClose = ct;
                // 상한(메모리 캡)
                while (dq.size() > 300)
                    dq.removeFirst();
            }

            log.info("Bootstrap M15 {}: loaded={}, buffered={}, lastClose={}",
                symbol, m15.size(), dq.size(), prevClose);

        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    private void refreshH1EnvFromBuffers(SymbolSession sess) {
        java.util.Deque<Candle> dq = sess.getBuffers().get(TimeFrame.H1);
        if (dq == null || dq.size() < 60)
            return;
        java.util.List<Candle> h1List = new java.util.ArrayList<Candle>(dq);
        H1Env env = computeH1Env(h1List);
        sess.getH1EnvRef().set(env);
        log.info("Refresh H1Env {}: hourKey={}, longOk={}, shortOk={}",
                sess.getSymbol(), env.getHourKey(), env.isLongOk(), env.isShortOk());
    }

    private H1Env computeH1Env(List<Candle> h1) {
        // 지표 계산
        double ema21 = ind.ema(h1, TimeFrame.H1, 21);
        double ema50 = ind.ema(h1, TimeFrame.H1, 50);
        double rsi = ind.rsi(h1, TimeFrame.H1, 14);
        double[] bb = ind.bollinger(h1, TimeFrame.H1, 20, 2);

        Candle last = h1.get(h1.size() - 1);
        long hourKey = last.getOpenTime() + 60L * 60L * 1000L; // H1 endTime(=closetime) 추정

        int longScore = 0;
        if (ema21 > ema50)
            longScore++;
        if (rsi >= 48.0)
            longScore++; // 약간 완화 (기존 50 → 48)
        if (last.getClose() >= bb[0])
            longScore++;

        int shortScore = 0;
        if (ema21 < ema50)
            shortScore++;
        if (rsi <= 52.0)
            shortScore++;
        if (last.getClose() <= bb[0])
            shortScore++;

        boolean longOk = longScore >= 2;
        boolean shortOk = shortScore >= 2;

        return H1Env.builder()
                .hourKey(hourKey)
                .longOk(longOk)
                .shortOk(shortOk)
                .bbMid(bb[0])
                .bbUp(bb[1])
                .bbUp(bb[2])
                .build();
    }
}
