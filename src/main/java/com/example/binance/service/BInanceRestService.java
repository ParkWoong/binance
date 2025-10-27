package com.example.binance.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.example.binance.dto.Candle;
import com.example.binance.enums.TimeFrame;
import com.example.binance.properties.DomainProperties;

import static com.example.binance.config.WebClientConfig.getSend;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BInanceRestService {
    private final DomainProperties domainProperties;

    public List<Candle> klines(String symbol, TimeFrame tf, int limit) {

        final String endPoint = new StringBuilder(domainProperties.getMain())
                .append(domainProperties.getKlines())
                .toString();

        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("interval", tf.timeFrame);
        params.add("limit", String.valueOf(limit));

        final ResponseEntity<List<List<Object>>> response = getSend(endPoint, null, params,
                new ParameterizedTypeReference<>() {
                });

        final List<List<Object>> responseBody = response.getBody();
        
        List<Candle> out = new ArrayList<>();
        
        if (responseBody == null)
            return out;

        for (List<Object> k : responseBody) {
            // kline 배열 인덱스: 0 opentime,1 open,2 high,3 low,4 close,5 volume,6 closetime ...
            out.add(Candle.builder()
                    .openTime(((Number) k.get(0)).longValue())
                    .closeTime(((Number)k.get(6)).longValue())
                    .open(Double.parseDouble((String) k.get(1)))
                    .high(Double.parseDouble((String) k.get(2)))
                    .low(Double.parseDouble((String) k.get(3)))
                    .close(Double.parseDouble((String) k.get(4)))
                    .volume(Double.parseDouble((String) k.get(5)))
                    .build());
        }
        return out;
    }
}
