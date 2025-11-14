package com.example.binance.dto;

import com.example.binance.enums.TimeFrame;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class TradeScenario {
    private final String id = UUID.randomUUID().toString()
                                    .replace("-","")
                                    .substring(27);
    private final String symbol;
    private final TimeFrame timeframe;
    private final String side; // "LONG" or "SHORT"
    private final double entry;
    private final double stop;
    private final double tp1;
    private final double tp2;
    private final long activatedAt;

    private double highestPrice;
    private double lowestPrice;
    private boolean hitTp1;
    private boolean hitTp2;
    private boolean hitStop;
    private long lastUpdated;

    public TradeScenario initialise(double price, long closeTime) {
        this.highestPrice = price;
        this.lowestPrice = price;
        this.lastUpdated = closeTime;
        return this;
    }

    public void update(double high, double low, long closeTime) {
        this.highestPrice = Math.max(this.highestPrice, high);
        this.lowestPrice = Math.min(this.lowestPrice, low);
        this.lastUpdated = closeTime;

        if (isLong()) {
            if (!hitStop && low <= stop)
                hitStop = true;
            if (!hitTp1 && high >= tp1)
                hitTp1 = true;
            if (!hitTp2 && high >= tp2)
                hitTp2 = true;
        } else {
            if (!hitStop && high >= stop)
                hitStop = true;
            if (!hitTp1 && low <= tp1)
                hitTp1 = true;
            if (!hitTp2 && low <= tp2)
                hitTp2 = true;
        }
    }

    public boolean isLong() {
        return "LONG".equalsIgnoreCase(side);
    }

    public String buildStatusSummary() {
        StringBuilder sb = new StringBuilder();
        if (hitStop)
            sb.append(isLong() ? "STOP (<= " : "STOP (>= ").append(stop).append(")");
        else {
            if (hitTp2)
                sb.append("TP2 hit (").append(tp2).append(")");
            else if (hitTp1)
                sb.append("TP1 hit (").append(tp1).append(")");
            else
                sb.append("No TP/STOP reached");
        }
        return sb.toString();
    }

    public long ageFrom(long closeTime) {
        return closeTime - activatedAt;
    }

    public long getLastUpdatedAge(long now) {
        return now - lastUpdated;
    }
}
