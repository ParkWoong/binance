package com.example.binance.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.binance.dto.Candle;
import com.example.binance.dto.BootStrapEnv;
import com.example.binance.dto.TradeScenario;
import com.example.binance.enums.TimeFrame;
import com.example.binance.utils.Notifier;
import com.example.binance.ws.SymbolSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.example.binance.utils.DateParser.getHour;

@Service
@RequiredArgsConstructor
@Slf4j
public class CalculateService {

    private final IndicatorService ind;
    private final Notifier notifier;
    private final Set<String> sentKeys = Collections.synchronizedSet(new HashSet<>());

    public void onCandleClosed_H1M15(SymbolSession sess) {

        // 1) 버퍼 체크
        Deque<Candle> h1 = sess.getBuffers().get(TimeFrame.H1);
        Deque<Candle> m15 = sess.getBuffers().get(TimeFrame.M15);
        
        if(h1 == null ) 
            { log.warn("H1 NPE"); return; } 
        if(m15 == null) 
            { log.warn("M15 NPE"); return; } 

        log.info("H1 Size : {} | M15 Size : {}", h1.size(), m15.size());
        
        if (h1.size() < 60 || m15.size() < 60) 
            { log.warn("Invalid Buffers"); return; }

        // 2) H1 환경 (부트스트랩/갱신된 값)
        BootStrapEnv env = sess.getH1EnvRef().get();
        if (env == null) { log.warn("Invalid H1"); return;}

        // 3) 동일 시간대 매핑: 해당 15m 봉이 env.hourKey 내에 있어야 함
        List<Candle> m15List = new ArrayList<Candle>(m15);
        Candle last = m15List.get(m15List.size() - 1);
        Candle prev = m15List.get(m15List.size() - 2);
        long hourKeyOfM15 = floorToHourEnd(last.getOpenTime()); // 15:45~16:00 → 16:00

        log.info("Hour Key Valid = ENV : {} | LAST : {}", 
                                            getHour(env.getHourKey()), 
                                            getHour(hourKeyOfM15));


        
        
        // if (hourKeyOfM15 != env.getHourKey()){ 
        //     log.warn("Invalid Hour Key => M15 : {} | ENV : {}", hourKeyOfM15, env.getHourKey()); 
        //     return;
        // }

        // 기존 시나리오에 현재 봉 반영
        updateScenarioWithCandle(sess, TimeFrame.M15, last);
        updateScenarioWithCandle(sess, TimeFrame.M5, last);


        // 4) 지표 계산
        double[] bb15 = ind.bollinger(m15List, TimeFrame.M15, 20, 2);
        double rsi15 = ind.rsi(m15List, TimeFrame.M15, 14);
        double prevRsi = ind.rsi(m15List.subList(0, m15List.size() - 1), TimeFrame.M15, 14);

        // H1 BB는 TP2 계산용
        List<Candle> h1List = new ArrayList<Candle>(h1);
        double[] bbH1 = ind.bollinger(h1List, TimeFrame.H1, 20, 2);

        // 모멘텀 완화: 볼륨 스파이크 OR RSI 회복/상승
        double avgVol = 0.0;
        if (m15List.size() >= 20) {
            double sum = 0.0;
            for (int i = m15List.size() - 20; i < m15List.size(); i++)
                sum += m15List.get(i).getVolume();
            avgVol = sum / 20.0;
        }
        boolean volSpike = avgVol > 0 && last.getVolume() >= 1.2 * avgVol;
        boolean rsiRecoverUp = (prevRsi < 49.0 && rsi15 >= 50.0); // for Long
        boolean rsiRecoverDown = (prevRsi > 51.0 && rsi15 <= 50.0); // for Short
        boolean momentumLongOk = volSpike || rsiRecoverUp || rsi15 >= 55.0;
        boolean momentumShortOk = volSpike || rsiRecoverDown || rsi15 <= 45.0;

        //double eps = 0.0005 * last.getClose();

        log.info("===[H1 Env] : LONG {} | SHORT {} ===", env.isLongOk(), env.isShortOk());

        // 6) 트리거 판정 (H1 게이트 → M15 스위치)
        boolean longTriggered = false;
        boolean shortTriggered = false;

        if (env.isLongOk()) {

            /* 필터링 완화 */
            //boolean crossUpMid  = (prev.getClose() <= bb15[0] + eps) && (last.getClose() >= bb15[0] - eps);
            //boolean longTrigger  = (crossUpMid  || last.getClose() > bb15[1]) && momentumLongOk;
            
            boolean longTrigger = ((prev.getClose() <= bb15[0] && last.getClose() > bb15[0]) // 중심선 재탈환
                     || (last.getClose() > bb15[1])) // 상단 돌파
                     && momentumLongOk;

            log.info("[LONG Alert] : {}", longTrigger);

            if (longTrigger) {
                longTriggered = true;
                double entry = last.getClose();
                // Stop: 하단밴드, 직전 저점, Entry보다 낮은 값 중 가장 낮은 값
                double stopCandidate = Math.min(bb15[2], prev.getLow());
                double stop = Math.min(stopCandidate, entry - (bb15[0] - bb15[2]) * 0.2); // Entry보다 확실히 낮게
                // TP1: 상단밴드 또는 Entry보다 높은 값 (이미 돌파한 경우 대비)
                double tp1 = Math.max(bb15[1], entry + (bb15[1] - bb15[0]) * 0.3); // 상단밴드 또는 Entry + 밴드폭의 30%
                double tp2 = bbH1[1]; // 1h 상단밴드

                TradeScenario scenario = startScenario(sess, TimeFrame.M15, "LONG", entry, stop, tp1, tp2, last);

                // 15분봉 필터링 통과 시 5분봉 활성화
                String currentSide = sess.getLastM15TriggerSide();
                if (!"LONG".equals(currentSide)) {
                    // 추세 역전 또는 새로운 신호
                    sess.activateM5("LONG", last.getCloseTime());
                    log.info("[M5 Activated] LONG at {}", last.getCloseTime());
                }
                
                sendOnce(sess.getSymbol(), "15m", "LONG",
                        last.getOpenTime(), entry, stop, tp1, tp2, scenario.getId(),
                        String.format("[Results of 15] H1+M15 LONG (tx=%s): 15m mid recapture/upper break & (Vol↑ or RSI↑)", scenario.getId()));
            }
        }

        // 양방향이 동시에 true가 날 수 있어 tie-break 필요 → long 우선 후 아니면 short
        if (!longTriggered && env.isShortOk()) {
            
            /* 필터링 완화 */
            //boolean crossDnMid  = (prev.getClose() >= bb15[0] - eps) && (last.getClose() <= bb15[0] + eps);
            //boolean shortTrigger = (crossDnMid  || last.getClose() < bb15[2]) && momentumShortOk;
            
            boolean shortTrigger = ((prev.getClose() >= bb15[0] && last.getClose() < bb15[0]) // 중심선 상실
                     || (last.getClose() < bb15[2])) // 하단 돌파
                     && momentumShortOk;

            log.info("[SHORT Alert] : {}", shortTrigger);

            if (shortTrigger) {
                shortTriggered = true;
                double entry = last.getClose();
                // Stop: 상단밴드, 직전 고점, Entry보다 높은 값 중 가장 높은 값
                double stopCandidate = Math.max(bb15[1], prev.getHigh());
                double stop = Math.max(stopCandidate, entry + (bb15[1] - bb15[0]) * 0.2); // Entry보다 확실히 높게
                // TP1: 하단밴드 또는 Entry보다 낮은 값 (이미 돌파한 경우 대비)
                double tp1 = Math.min(bb15[2], entry - (bb15[0] - bb15[2]) * 0.3); // 하단밴드 또는 Entry - 밴드폭의 30%
                double tp2 = bbH1[2]; // 1h 하단밴드

                TradeScenario scenario = startScenario(sess, TimeFrame.M15, "SHORT", entry, stop, tp1, tp2, last);
                
                // 15분봉 필터링 통과 시 5분봉 활성화
                String currentSide = sess.getLastM15TriggerSide();
                if (!"SHORT".equals(currentSide)) {
                    // 추세 역전 또는 새로운 신호
                    sess.activateM5("SHORT", last.getCloseTime());
                    log.info("[M5 Activated] SHORT at {}", last.getCloseTime());
                }
                
                sendOnce(sess.getSymbol(), "15m", "SHORT",
                        last.getOpenTime(), entry, stop, tp1, tp2, scenario.getId(),
                        String.format("[Results of 15] H1+M15 SHORT (tx=%s): 15m mid loss/lower break & (Vol↑ or RSI↓)", scenario.getId()));
            }
        }
        
        // 15분봉 필터링 실패 시 5분봉 비활성화 및 시나리오 종료
        if (!longTriggered && !shortTriggered) {
            closeScenario(sess, TimeFrame.M5, "M5 filter inactive after 15m evaluation");
            closeScenario(sess, TimeFrame.M15, "15m filter inactive");
            if (sess.isM5Active()) {
                sess.deactivateM5();
                log.info("[M5 Deactivated] No trigger at {}", last.getCloseTime());
            }
        }
    }

    /* 5분봉 마감 시 트리거 평가 */
    public void onCandleClosed_M5(SymbolSession sess) {
        
        // 1) 버퍼 체크
        Deque<Candle> m5 = sess.getBuffers().get(TimeFrame.M5);
        Deque<Candle> m15 = sess.getBuffers().get(TimeFrame.M15);
        Deque<Candle> h1 = sess.getBuffers().get(TimeFrame.H1);
        
        if (m5 == null || m15 == null || h1 == null) {
            log.warn("M5/M15/H1 Buffer NPE");
            return;
        }
        
        if (m5.size() < 40 || m15.size() < 60 || h1.size() < 60) {
            log.warn("Invalid Buffers for M5: M5={}, M15={}, H1={}", m5.size(), m15.size(), h1.size());
            return;
        }
        
        List<Candle> m5List = new ArrayList<>(m5);
        Candle m5Last = m5List.get(m5List.size() - 1);
        Candle m5Prev = m5List.get(m5List.size() - 2);

        // 기존 시나리오에 현재 봉 반영
        updateScenarioWithCandle(sess, TimeFrame.M5, m5Last);
        updateScenarioWithCandle(sess, TimeFrame.M15, m5Last);

        
        // 2) 활성화 상태 및 방향 확인
        if (!sess.isM5Active()) {
            return; // 비활성화 상태면 처리 안함
        }
        
        String expectedSide = sess.getLastM15TriggerSide();
        if (expectedSide == null) {
            log.warn("M5 Active but no side set");
            closeScenario(sess, TimeFrame.M5, "M5 active without direction");
            sess.deactivateM5();
            return;
        }
        
        List<Candle> m15List = new ArrayList<>(m15);
        Candle m15Last = m15List.get(m15List.size() - 1);
        
        // 5분봉이 마지막 15분봉의 시간 범위 내에 있는지 확인
        long m15Start = m15Last.getOpenTime();
        long m15End = m15Last.getCloseTime();
        if (m5Last.getOpenTime() < m15Start || m5Last.getCloseTime() > m15End) {
            // 5분봉이 15분봉 범위를 벗어나면 비활성화
            if (m5Last.getCloseTime() > m15End) {
                closeScenario(sess, TimeFrame.M5, "M5 candle out of 15m range");
                sess.deactivateM5();
                log.info("[M5 Deactivated] Out of M15 range");
            }
            return;
        }
        
        // 4) 지표 계산
        double[] bb5 = ind.bollinger(m5List, TimeFrame.M5, 20, 2);
        double rsi5 = ind.rsi(m5List, TimeFrame.M5, 14);
        double prevRsi5 = ind.rsi(m5List.subList(0, m5List.size() - 1), TimeFrame.M5, 14);
        
        // H1 BB는 TP2 계산용
        List<Candle> h1List = new ArrayList<>(h1);
        double[] bbH1 = ind.bollinger(h1List, TimeFrame.H1, 20, 2);
        
        // 5) 모멘텀 확인
        double avgVol = 0.0;
        if (m5List.size() >= 20) {
            double sum = 0.0;
            for (int i = m5List.size() - 20; i < m5List.size(); i++)
                sum += m5List.get(i).getVolume();
            avgVol = sum / 20.0;
        }
        boolean volSpike = avgVol > 0 && m5Last.getVolume() >= 1.2 * avgVol;
        boolean rsiRecoverUp = (prevRsi5 < 49.0 && rsi5 >= 50.0);
        boolean rsiRecoverDown = (prevRsi5 > 51.0 && rsi5 <= 50.0);
        boolean momentumLongOk = volSpike || rsiRecoverUp || rsi5 >= 55.0;
        boolean momentumShortOk = volSpike || rsiRecoverDown || rsi5 <= 45.0;
        
        // 6) 트리거 판정 (15분봉 방향과 일치하는지 확인)
        boolean trigger = false;
        double entry = 0.0;
        double stop = 0.0;
        double tp1 = 0.0;
        double tp2 = 0.0;
        String reason = "";
        TradeScenario scenario = null;
        
        if ("LONG".equals(expectedSide) && momentumLongOk) {
            // LONG: 5분봉 볼린저 중심선 재탈환 또는 상단 돌파
            boolean longTrigger = ((m5Prev.getClose() <= bb5[0] && m5Last.getClose() > bb5[0])
                     || (m5Last.getClose() > bb5[1]));
            
            if (longTrigger) {
                trigger = true;
                entry = m5Last.getClose();
                // Stop: 하단밴드, 직전 저점, Entry보다 낮은 값 중 가장 낮은 값
                double stopCandidate = Math.min(bb5[2], m5Prev.getLow());
                stop = Math.min(stopCandidate, entry - (bb5[0] - bb5[2]) * 0.2); // Entry보다 확실히 낮게
                // TP1: 상단밴드 또는 Entry보다 높은 값 (이미 돌파한 경우 대비)
                tp1 = Math.max(bb5[1], entry + (bb5[1] - bb5[0]) * 0.3); // 상단밴드 또는 Entry + 밴드폭의 30%
                tp2 = bbH1[1]; // 1시간 상단밴드
                scenario = startScenario(sess, TimeFrame.M5, "LONG", entry, stop, tp1, tp2, m5Last);
                reason = String.format("[Results of 5] M5 LONG (tx=%s): 5m mid recapture/upper break & (Vol↑ or RSI↑)", scenario.getId());
            }
        } else if ("SHORT".equals(expectedSide) && momentumShortOk) {
            // SHORT: 5분봉 볼린저 중심선 상실 또는 하단 돌파
            boolean shortTrigger = ((m5Prev.getClose() >= bb5[0] && m5Last.getClose() < bb5[0])
                     || (m5Last.getClose() < bb5[2]));
            
            if (shortTrigger) {
                trigger = true;
                entry = m5Last.getClose();
                // Stop: 상단밴드, 직전 고점, Entry보다 높은 값 중 가장 높은 값
                double stopCandidate = Math.max(bb5[1], m5Prev.getHigh());
                stop = Math.max(stopCandidate, entry + (bb5[1] - bb5[0]) * 0.2); // Entry보다 확실히 높게
                // TP1: 하단밴드 또는 Entry보다 낮은 값 (이미 돌파한 경우 대비)
                tp1 = Math.min(bb5[2], entry - (bb5[0] - bb5[2]) * 0.3); // 하단밴드 또는 Entry - 밴드폭의 30%
                tp2 = bbH1[2]; // 1시간 하단밴드
                scenario = startScenario(sess, TimeFrame.M5, "SHORT", entry, stop, tp1, tp2, m5Last);
                reason = String.format("[Results of 5] M5 SHORT (tx=%s): 5m mid loss/lower break & (Vol↑ or RSI↓)", scenario.getId());
            }
        }
        
        if (trigger && scenario != null) {
            sendOnce(sess.getSymbol(), "5m", expectedSide,
                    m5Last.getOpenTime(), entry, stop, tp1, tp2, scenario.getId(), reason);
        }
    }

    /** 15:45~16:00 봉 openTime → 16:00 (해당 시간 경계)로 정규화 */
    private long floorToHourEnd(long openTimeMs) {
        long close = openTimeMs + 15L * 60L * 1000L; // 15m close
        long hour = 60L * 60L * 1000L;
        return close - (close % hour); // 해당 시간 경계(예: 16:00)
    }

    private TradeScenario startScenario(SymbolSession sess, TimeFrame tf, String side,
                                        double entry, double stop, double tp1, double tp2, Candle candle) {
        closeScenario(sess, tf, "Replaced by new " + side + " signal");
        TradeScenario scenario = new TradeScenario(sess.getSymbol(), tf, side, entry, stop, tp1, tp2, candle.getCloseTime())
                .initialise(entry, candle.getCloseTime());
        scenario.update(candle.getHigh(), candle.getLow(), candle.getCloseTime());
        sess.setScenario(tf, scenario);
        return scenario;
    }

    private void updateScenarioWithCandle(SymbolSession sess, TimeFrame tf, Candle candle) {
        TradeScenario scenario = sess.getScenario(tf);
        if (scenario != null) {
            scenario.update(candle.getHigh(), candle.getLow(), candle.getCloseTime());
        }
    }

    private void closeScenario(SymbolSession sess, TimeFrame tf, String reason) {
        TradeScenario scenario = sess.getScenario(tf);
        if (scenario == null)
            return;
        String message = String.format("[Exit %s]\n[tx=%s]\nside: %s\nentry: %.2f\nstop: %.2f\ntp1: %.2f\ntp2: %.2f\nstatus: %s",
                tf.name().toLowerCase(), scenario.getId(), scenario.getSide(),
                scenario.getEntry(), scenario.getStop(), scenario.getTp1(), scenario.getTp2(),
                scenario.buildStatusSummary());
        sendExit(scenario.getSymbol(), tf.name().toLowerCase(), scenario.getId(), message);
        sess.setScenario(tf, null);
    }

    /* 트리거 발생 시 알림 */
    private void sendOnce(String symbol, String tf, String side, long closeTime,
            double entry, double stop, double tp1, double tp2, String txId, String reason) {

        String msg = "";
        String key = symbol + "|" + tf + "|" + side + "|" + closeTime;

        if (sentKeys.add(key)) {
            msg = String.format(
                    "[%s %s] \n[tx=%s] \n%s\nentry: %.2f\nstop: %.2f\ntp1: %.2f\ntp2: %.2f",
                    symbol, tf, txId, side, entry, stop, tp1, tp2);
            notifier.send(msg);
        }
    }

    private void sendExit(String symbol, String tf, String txId, String message) {

        String key = symbol + "|exit|" + txId + "|" + tf;
        if (sentKeys.add(key)) {
            notifier.send(message);
        }
    }
}
