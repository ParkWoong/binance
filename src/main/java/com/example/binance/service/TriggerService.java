package com.example.binance.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import com.example.binance.dto.BiasRegime;
import com.example.binance.dto.Candle;
import com.example.binance.dto.BootStrapEnv;
import com.example.binance.enums.TimeFrame;
import com.example.binance.utils.Notifier;
import com.example.binance.ws.SymbolSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TriggerService {
    private final IndicatorService ind;
    private final Notifier notifier;

    // Set Current Time
    private final ZoneId AEST = ZoneId.of("Australia/Sydney");
    private final Set<String> sentKeys = Collections.synchronizedSet(new HashSet<>());

    public void onCandleClosedWithH1Env(SymbolSession sess) {
        ZonedDateTime now = ZonedDateTime.now(AEST);
        int hour = now.getHour();
        if (hour < 14 || hour >= 22)
            return;

        Deque<Candle> m15 = sess.getBuffers().get(TimeFrame.M15);
        if (m15 == null || m15.size() < 40){
            log.warn("Warning from M15 Size");
            return;
        }
            
        BootStrapEnv env = sess.getH1EnvRef().get();
        if (env == null){
            log.warn("Warning from there is no H1 objects");
            return;
        }
            

        List<Candle> m15List = new ArrayList<>(m15);
        Candle last = m15List.get(m15List.size() - 1);
        Candle prev = m15List.get(m15List.size() - 2);

        long hourKeyOfM15 = floorToHourEnd(last.getOpenTime());
        if (hourKeyOfM15 != env.getHourKey())
            return;

        double[] bb15 = ind.bollinger(m15List, TimeFrame.M15, 20, 2);
        double rsi15 = ind.rsi(m15List, TimeFrame.M15, 14);
        double prevRsi = ind.rsi(m15List.subList(0, m15List.size() - 1), TimeFrame.M15, 14);

        // ✅ 완화된 모멘텀 조건 (볼륨↑ OR RSI 회복)
        double avgVol = m15List.stream()
                .skip(Math.max(0, m15List.size() - 20))
                .mapToDouble(Candle::getVolume).average().orElse(0);
        boolean volSpike = avgVol > 0 && last.getVolume() >= 1.3 * avgVol;
        boolean rsiRecover = prevRsi < 48 && rsi15 >= 50;
        boolean momentumOk = volSpike || rsiRecover || rsi15 >= 55;

        // Bias and Regime Check
        BiasRegime br = sess.getBrRef().get();

        if (br.getRegime() == BiasRegime.Regime.TREND) {
            if (br.getBias() == BiasRegime.Bias.LONG && env.isLongOk()) {
                boolean trigger = ((prev.getClose() <= bb15[0] && last.getClose() > bb15[0])
                        || (last.getClose() > bb15[1])) && momentumOk;
                if (trigger)
                    sendOnce(sess.getSymbol(), "15m", "LONG",
                            last.getOpenTime(), last.getClose(),
                            Math.min(bb15[2], prev.getLow()), bb15[1], env.getBbUp(),
                            "TREND LONG (vol↑/RSI↑)");
            } else if (br.getBias() == BiasRegime.Bias.SHORT && env.isShortOk()) {
                boolean trigger = ((prev.getClose() >= bb15[0] && last.getClose() < bb15[0])
                        || (last.getClose() < bb15[2])) && momentumOk;
                if (trigger)
                    sendOnce(sess.getSymbol(), "15m", "SHORT",
                            last.getOpenTime(), last.getClose(),
                            Math.max(bb15[1], prev.getHigh()), bb15[2], env.getBbLow(),
                            "TREND SHORT (vol↑/RSI↓)");
            }
        }
    }

    private long floorToHourEnd(long openTimeMs) {
        // 15:45~16:00 봉의 openTime을 받아서 16:00 closetime(시간 경계)로 맞추는 간단한 보정
        // openTime + 15분 = closetime, 그걸 시간 경계에 맞춤
        long close = openTimeMs + 15L * 60L * 1000L;
        return close - (close % (60L * 60L * 1000L));
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
