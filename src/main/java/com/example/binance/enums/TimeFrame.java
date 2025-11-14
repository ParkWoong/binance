package com.example.binance.enums;

public enum TimeFrame {
    H1("1h"),
    M15("15m"),
    M5("5m");

    public final String timeFrame;

    TimeFrame(String b) {this.timeFrame = b;}
}
