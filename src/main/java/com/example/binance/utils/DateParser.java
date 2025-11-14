package com.example.binance.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateParser {
    private static final SimpleDateFormat YMDHM_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final SimpleDateFormat HOUR_FORMAT = new SimpleDateFormat("HH");

    public static String getNowMinute(){
        return YMDHM_FORMAT.format(new Date());
    }

    public static String getHour(long millisTime){
        return HOUR_FORMAT.format(new Date(millisTime));
    }
}
