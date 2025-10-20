package com.example.binance.enums;

public enum TimeFrame {
    D1("1d"),
    H4("4h"),
    H1("1h"),
    M15("15m");

    public final String timeFrame;

    TimeFrame(String b) {this.timeFrame = b;}
}
