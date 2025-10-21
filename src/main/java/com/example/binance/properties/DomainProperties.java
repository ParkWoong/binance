package com.example.binance.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties("binance.domain")
public class DomainProperties {
    
    private String main;
    private String klines;
    private String ws;
    private Get get;

    @Getter
    @Setter
    public static class Get{
        private String time;
    }
}
