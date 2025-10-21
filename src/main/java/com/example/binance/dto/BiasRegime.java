package com.example.binance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiasRegime {
    public enum Bias{LONG, SHORT, NEUTRAL}  // 1D 시장의 방향
    public enum Regime{TREND, RANGE}        // 4H 현재 시장의 상태(추세 vs 횡보)
    private Bias bias;
    private Regime regime;
    private String reason; //for logging and debugig
}
