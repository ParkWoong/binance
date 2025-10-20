package com.example.binance.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.binance.properties.DomainProperties;

import static com.example.binance.config.WebClientConfig.getSend;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final WebClient webClient;
    private final DomainProperties domainProperties;
    private final static ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<List<Map<String, Object>>>() {
    };
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
