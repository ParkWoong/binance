package com.example.binance.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;


import com.example.binance.properties.DomainProperties;

import static com.example.binance.config.WebClientConfig.getSend;

import java.util.Map;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final DomainProperties domainProperties;
    private final static ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<Map<String, Object>>() {
    };

    public ResponseEntity<?> test(){

        final String endPoint = new StringBuilder()
                                    .append(domainProperties.getMain())
                                    .append(domainProperties.getGet().getTime())
                                    .toString();


        final ResponseEntity<Map<String, Object>> resposne = 
                            getSend(endPoint, null, null, 
                            MAP);
            
        return resposne;
    }
}
