package com.example.binance.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import com.example.binance.dto.Candle;
import com.example.binance.enums.TimeFrame;

@Service
public class IndicatorService {
    
    private Duration toDuration(TimeFrame tf){
        return switch (tf) {
            case D1 -> Duration.ofDays(1);
            case H4  -> Duration.ofHours(4);
            case H1  -> Duration.ofHours(1);
            case M15 -> Duration.ofMinutes(15);
        };
    }

    private BarSeries toSeries(String name, TimeFrame tf, List<Candle> candles) {
        Duration dur = toDuration(tf);
        BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
        ZoneId zone = ZoneId.of("UTC");

        for (Candle c : candles) {
            // ta4j Bar는 endTime 기준이므로, openTime + duration을 end로 구성
            ZonedDateTime end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(c.getOpenTime()), zone).plus(dur);
            Bar bar = BaseBar.builder()
                    .endTime(end)
                    .openPrice(DecimalNum.valueOf(c.getOpen()))
                    .highPrice(DecimalNum.valueOf(c.getHigh()))
                    .lowPrice(DecimalNum.valueOf(c.getLow()))
                    .closePrice(DecimalNum.valueOf(c.getClose()))
                    .volume(DecimalNum.valueOf(c.getVolume()))
                    .timePeriod(dur)
                    .build();
            series.addBar(bar);
        }
        return series;
    }

        /** EMA */
    public double ema(List<Candle> candles, TimeFrame tf, int period) {
        BarSeries s = toSeries("ema-" + tf, tf, candles);
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        EMAIndicator ema = new EMAIndicator(close, period);
        Num v = ema.getValue(s.getEndIndex());
        return v.doubleValue();
    }

    /** RSI */
    public double rsi(List<Candle> candles, TimeFrame tf, int period) {
        BarSeries s = toSeries("rsi-" + tf, tf, candles);
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        RSIIndicator rsi = new RSIIndicator(close, period);
        return rsi.getValue(s.getEndIndex()).doubleValue();
    }

    /** Bollinger Bands (mid, upper, lower) */
    public double[] bollinger(List<Candle> candles, TimeFrame tf, int period, double k) {
        BarSeries s = toSeries("bb-" + tf, tf, candles);
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        SMAIndicator sma = new SMAIndicator(close, period);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, period);

        int i = s.getEndIndex();
        double mid = sma.getValue(i).doubleValue();
        double std = sd.getValue(i).doubleValue();
        return new double[] { mid, mid + k*std, mid - k*std };
    }

    /** ADX(+DI/-DI) */
    public double[] adxDmi(List<Candle> candles, TimeFrame tf, int period) {
        BarSeries s = toSeries("adx-" + tf, tf, candles);
        ADXIndicator adx = new ADXIndicator(s, period);
        PlusDIIndicator pdi = new PlusDIIndicator(s, period);
        MinusDIIndicator mdi = new MinusDIIndicator(s, period);

        int i = s.getEndIndex();
        return new double[] {
                adx.getValue(i).doubleValue(),
                pdi.getValue(i).doubleValue(),
                mdi.getValue(i).doubleValue()
        };
    }

}
