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
    public enum Bias{LONG, SHORT, NEUTRAL}
    public enum Regime{TREND, RANGE}
    private Bias bias;
    private Regime regime;
    private String reason; //for logging and debugig
}
