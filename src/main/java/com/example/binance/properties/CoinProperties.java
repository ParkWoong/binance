package com.example.binance.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties("coin")
public class CoinProperties {
    private String symbol;
}
