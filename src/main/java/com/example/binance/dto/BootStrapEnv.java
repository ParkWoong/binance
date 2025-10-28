package com.example.binance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
public class BootStrapEnv {
    private long hourKey;
    private boolean longOk;
    private boolean shortOk;

    private double bbMid;
    private double bbUp;
    private double bbLow;
}
