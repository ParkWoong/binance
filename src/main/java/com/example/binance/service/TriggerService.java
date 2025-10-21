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

    // ?
    private final Set<String> sentKeys = Collections.synchronizedSet(new HashSet<>());

    public void onCandleClosed(SymbolSession session){
        final ZonedDateTime now = ZonedDateTime.now(AEST);
        final int h = now.getHour();
        if(h<14||h>=24) {log.error("Invalid Time Zone"); return;}

        final Deque<Candle> h1 = session.getBuffers().get(TimeFrame.H1);
        final Deque<Candle> m15 = session.getBuffers().get(TimeFrame.M15);

        if (h1 == null || m15 == null || h1.size() < 60 || m15.size() < 60) return;

        final List<Candle> h1List = new ArrayList<Candle>(h1);
        final double ema21H1 = ind.ema(h1List, TimeFrame.H1, 21);
        final double ema50H1 = ind.ema(h1List, TimeFrame.H1, 50);
        final double rsiH1 = ind.rsi(h1List, TimeFrame.H1, 14);
        final double[] bbH1 = ind.bollinger(h1List, TimeFrame.H1, 20, 2);

        final List<Candle> m15List = new ArrayList<Candle>(m15);
        final double[] bb15 = ind.bollinger(m15List, TimeFrame.M15, 20, 2);
        final double rsi15 = ind.rsi(m15List, TimeFrame.M15, 14);

         double volAvg = 0;
        if (m15List.size() >= 20) {
            double sum = 0;
            for (int i = m15List.size() - 20; i < m15List.size(); i++) {
                sum += m15List.get(i).getVolume();
            }
            volAvg = sum / 20.0;
        }

        Candle last = m15List.get(m15List.size() - 1);
        Candle prev = m15List.get(m15List.size() - 2);
        boolean volSpike = volAvg > 0 && last.getVolume() >= 1.3 * volAvg;

        BiasRegime br = session.getBrRef().get();

        if (br.getRegime() == BiasRegime.Regime.TREND) {
            if (br.getBias() == BiasRegime.Bias.LONG) {
                boolean env = ema21H1 > ema50H1 && rsiH1 >= 50 && last.getClose() >= bbH1[0];
                boolean trigger = (prev.getClose() <= bb15[0] && last.getClose() > bb15[0])
                        || (last.getClose() > bb15[1]);
                if (env && trigger && volSpike && rsi15 >= 50) {
                    double entry = last.getClose();
                    double stop = Math.min(bb15[2], prev.getLow());
                    double tp1 = bb15[1];
                    double tp2 = bbH1[1];
                    sendOnce(session.getSymbol(), "15m", "LONG", last.getOpenTime(),
                            entry, stop, tp1, tp2,
                            "TREND LONG: 1H EMA21>50,RSI>=50 & 15m BB mid recapture/upper break + Vol↑");
                }
            } else if (br.getBias() == BiasRegime.Bias.SHORT) {
                boolean env = ema21H1 < ema50H1 && rsiH1 <= 50 && last.getClose() <= bbH1[0];
                boolean trigger = (prev.getClose() >= bb15[0] && last.getClose() < bb15[0])
                        || (last.getClose() < bb15[2]);
                if (env && trigger && volSpike && rsi15 <= 50) {
                    double entry = last.getClose();
                    double stop = Math.max(bb15[1], prev.getHigh());
                    double tp1 = bb15[2];
                    double tp2 = bbH1[2];
                    sendOnce(session.getSymbol(), "15m", "SHORT", last.getOpenTime(),
                            entry, stop, tp1, tp2,
                            "TREND SHORT: 1H EMA21<50,RSI<=50 & 15m BB mid loss/lower break + Vol↑");
                }
            }
        } else { // RANGE 모드
            if (prev.getClose() >= bb15[1] && last.getClose() < bb15[1] && rsi15 >= 55 && volSpike) {
                double entry = last.getClose();
                double stop = Math.max(bb15[1], prev.getHigh());
                double tp1 = bb15[0];
                double tp2 = bb15[2];
                sendOnce(session.getSymbol(), "15m", "SHORT", last.getOpenTime(),
                        entry, stop, tp1, tp2,
                        "RANGE MR SHORT: 15m upper reject + Vol↑");
            }
            if (prev.getClose() <= bb15[2] && last.getClose() > bb15[2] && rsi15 <= 45 && volSpike) {
                double entry = last.getClose();
                double stop = Math.min(bb15[2], prev.getLow());
                double tp1 = bb15[0];
                double tp2 = bb15[1];
                sendOnce(session.getSymbol(), "15m", "LONG", last.getOpenTime(),
                        entry, stop, tp1, tp2,
                        "RANGE MR LONG: 15m lower rebound + Vol↑");
            }
        }
    }

    private void sendOnce(String symbol, String tf, String side, long closeTime,
                          double entry, double stop, double tp1, double tp2, String reason) {
        
        String msg = "";                    
        String key = symbol + "|" + tf + "|" + side + "|" + closeTime;
        
        if (sentKeys.add(key)) {
            msg = String.format(
                    "[%s %s] %s\nentry: %.2f\nstop: %.2f\ntp1: %.2f\ntp2: %.2f\nreason: %s",
                    symbol, tf, side, entry, stop, tp1, tp2, reason
            );
            notifier.send(msg);
        }

        log.info("MSG : {}", msg);
    }

}

