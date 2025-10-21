package com.example.binance.utils;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import com.example.binance.properties.TelegramProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.example.binance.config.WebClientConfig.postSend;


@Component
@Slf4j
@RequiredArgsConstructor
public class Notifier {

    private final TelegramProperties telegramProperties;

    public void send(final String message){

        final String payload = String.format("{\"chat_id\":\"%s\",\"text\":\"%s\"}", 
                                            telegramProperties.getChatId(), 
                                            message);

        try {
            postSend(telegramProperties.getDomain(),
                    null,
                    payload,
                    null,
                    new ParameterizedTypeReference<String>(){});
        } catch (Exception e) {
            log.error("ERROR : {}", e.getMessage());
        }
    }
}
