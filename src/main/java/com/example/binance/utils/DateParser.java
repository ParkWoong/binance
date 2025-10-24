package com.example.binance.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateParser {
    private static final SimpleDateFormat YMDHM_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public static String getNowMinute(){
        return YMDHM_FORMAT.format(new Date());
    }
}
