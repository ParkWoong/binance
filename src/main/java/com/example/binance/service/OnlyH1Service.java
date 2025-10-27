package com.example.binance.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.binance.dto.Candle;
import com.example.binance.dto.H1Env;
import com.example.binance.enums.TimeFrame;
import com.example.binance.utils.Notifier;
import com.example.binance.ws.SymbolSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlyH1Service {

    private final IndicatorService ind;
    private final Notifier notifier;
    private final Set<String> sentKeys = Collections.synchronizedSet(new HashSet<>());

    public void onCandleClosed_H1M15(SymbolSession sess) {
        // 1) 트레이딩 시간 필터 (호주 14:00~22:00)
        //ZonedDateTime now = ZonedDateTime.now(AEST);
        //int hour = now.getHour();
        //if (hour < 14 || hour >= 22){ return; }
            

        // 2) 버퍼 체크
        Deque<Candle> h1 = sess.getBuffers().get(TimeFrame.H1);
        Deque<Candle> m15 = sess.getBuffers().get(TimeFrame.M15);
        
        if(h1 == null ) 
            { log.warn("H1 NPE"); return; } 
        if(m15 == null) 
            { log.warn("M15 NPE"); return; } 

        log.info("H1 Size : {} | M15 Size : {}", h1.size(), m15.size());
        
        if (h1.size() < 60 || m15.size() < 60) 
            { log.warn("Invalid Buffers"); return; }

        // 3) H1 환경 (부트스트랩/갱신된 값)
        H1Env env = sess.getH1EnvRef().get();
        if (env == null) { log.warn("Invalid H1"); return;}

        // 4) 동일 시간대 매핑: 해당 15m 봉이 env.hourKey 내에 있어야 함
        List<Candle> m15List = new ArrayList<Candle>(m15);
        Candle last = m15List.get(m15List.size() - 1);
        Candle prev = m15List.get(m15List.size() - 2);
        long hourKeyOfM15 = floorToHourEnd(last.getOpenTime()); // 15:45~16:00 → 16:00
        if (hourKeyOfM15 != env.getHourKey()){ log.warn("Invalid Hour Key"); return;}
            

        // 5) 지표 계산
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
        boolean volSpike = avgVol > 0 && last.getVolume() >= 1.3 * avgVol;
        boolean rsiRecoverUp = (prevRsi < 48.0 && rsi15 >= 50.0); // for Long
        boolean rsiRecoverDown = (prevRsi > 52.0 && rsi15 <= 50.0); // for Short
        boolean momentumLongOk = volSpike || rsiRecoverUp || rsi15 >= 55.0;
        boolean momentumShortOk = volSpike || rsiRecoverDown || rsi15 <= 45.0;

        log.info("===[H1 Env] : LONG {} | SHORT {} ===", env.isLongOk(), env.isShortOk());

        // 6) 트리거 판정 (H1 게이트 → M15 스위치)
        boolean longTriggered = false;

        if (env.isLongOk()) {
            boolean longTrigger = ((prev.getClose() <= bb15[0] && last.getClose() > bb15[0]) // 중심선 재탈환
                    || (last.getClose() > bb15[1])) // 상단 돌파
                    && momentumLongOk;
            
            log.info("[LONG Alert] : {}", longTrigger);

            if (longTrigger) {
                longTriggered = true;
                double entry = last.getClose();
                double stop = Math.min(bb15[2], prev.getLow()); // 하단밴드 또는 직전 저점
                double tp1 = bb15[1]; // 15m 상단밴드
                double tp2 = bbH1[1]; // 1h 상단밴드
                sendOnce(sess.getSymbol(), "15m", "LONG",
                        last.getOpenTime(), entry, stop, tp1, tp2,
                        "H1+M15 LONG: 15m mid recapture/upper break & (Vol↑ or RSI↑)");
            }
        }

        // 양방향이 동시에 true가 날 수 있어 tie-break 필요 → long 우선 후 아니면 short
        if (!longTriggered && env.isShortOk()) {
            boolean shortTrigger = ((prev.getClose() >= bb15[0] && last.getClose() < bb15[0]) // 중심선 상실
                    || (last.getClose() < bb15[2])) // 하단 돌파
                    && momentumShortOk;

            log.info("[SHORT Alert] : {}", shortTrigger);

            if (shortTrigger) {
                double entry = last.getClose();
                double stop = Math.max(bb15[1], prev.getHigh()); // 상단밴드 또는 직전 고점
                double tp1 = bb15[2]; // 15m 하단밴드
                double tp2 = bbH1[2]; // 1h 하단밴드
                sendOnce(sess.getSymbol(), "15m", "SHORT",
                        last.getOpenTime(), entry, stop, tp1, tp2,
                        "H1+M15 SHORT: 15m mid loss/lower break & (Vol↑ or RSI↓)");
            }
        }

        // 필요 시: longTriggered && shortTriggered 모두 발생할 드문 상황에서 규칙 추가 가능
        // (예: RSI/가격 위치 기준 우선순위 결정). 현재는 LONG 우선 처리 후 SHORT는 패스.
    }

    /** 15:45~16:00 봉 openTime → 16:00 (해당 시간 경계)로 정규화 */
    private long floorToHourEnd(long openTimeMs) {
        long close = openTimeMs + 15L * 60L * 1000L; // 15m close
        long hour = 60L * 60L * 1000L;
        return close - (close % hour); // 해당 시간 경계(예: 16:00)
    }

    private void sendOnce(String symbol, String tf, String side, long closeTime,
            double entry, double stop, double tp1, double tp2, String reason) {

        String msg = "";
        String key = symbol + "|" + tf + "|" + side + "|" + closeTime;

        if (sentKeys.add(key)) {
            msg = String.format(
                    "[%s %s] %s\nentry: %.2f\nstop: %.2f\ntp1: %.2f\ntp2: %.2f\nreason: %s",
                    symbol, tf, side, entry, stop, tp1, tp2, reason);
            notifier.send(msg);
        }

        log.info("MSG : {}", msg);
    }
}
