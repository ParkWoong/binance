package com.example.binance.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.binance.dto.BiasRegime;
import com.example.binance.dto.Candle;
import com.example.binance.enums.TimeFrame;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DirectionService {
    private final BInanceRestService rest;
    private final IndicatorService ind;

    public BiasRegime evaluate(final String symbol){
        
        final TimeFrame D1 = TimeFrame.D1;
        final TimeFrame H4 = TimeFrame.H4;

        // 1D
        List<Candle> d1 = rest.klines(symbol, D1, 200);
        double ema21d = ind.ema(d1, D1, 21);
        double ema50d = ind.ema(d1, D1, 50);
        double rsiD = ind.rsi(d1, D1, 14);

        BiasRegime.Bias bias = 
                        (ema21d > ema50d && rsiD >= 50) ? BiasRegime.Bias.LONG :
                        (ema21d < ema50d && rsiD <= 50) ? BiasRegime.Bias.SHORT :
                        BiasRegime.Bias.NEUTRAL;

        // 4H
        List<Candle> h4 = rest.klines(symbol, H4, 200);
        double[] adx = ind.adxDmi(h4, H4, 14);
        BiasRegime.Regime regime = 
                        (adx[0] >= 22) ? BiasRegime.Regime.TREND : BiasRegime.Regime.RANGE;

        
        return BiasRegime
                .builder()
                .bias(bias)
                .regime(regime)
                //.reason("for logging")
                .build();
    }
}
