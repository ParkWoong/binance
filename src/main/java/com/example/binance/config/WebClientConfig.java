package com.example.binance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import reactor.netty.http.client.HttpClient;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private static WebClient webClient;

    private static final String ENCODINGHEADER = "accept-encoding";
    private static final String ENCODINGVALUE = "identity";
    private static byte[] EMPTY_BODY = {};

    private HttpClient defaultHttpClient() {
        HttpClient client = HttpClient
                .create()
                .doOnConnected(con -> con
                        .addHandlerFirst(
                                new ReadTimeoutHandler(300))
                        .addHandlerLast(
                                new WriteTimeoutHandler(300)))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);
        return client;
    }

    private WebClient webClient(HttpClient httpClient){
        return WebClient
        .builder()
        // Use ExchangeFilterFunction
        //.filter(exchaneFilter)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build()
        ;
    }


    @Bean
    public WebClient defaultWebClient(){
        return webClient = webClient(defaultHttpClient());
    }

    public static <T,R> ResponseEntity<T> postSend(
                        final String endPoint,
                        final HttpHeaders headers,
                        final R requestBody,
                        final MultiValueMap<String, String> params,
                        final ParameterizedTypeReference<T> responseType) {

        ResponseEntity<T> responseFromExternal = webClient
                        .post()
                        .uri(endPoint, uriBuilder ->
                                    uriBuilder.queryParams(params).build())
                        .contentType(MediaType.APPLICATION_JSON) // default Content Type
                        .headers(h -> {
                                if(headers != null)
                                    h.addAll(headers);
                                h.set(ENCODINGHEADER, ENCODINGVALUE);
                        })
                        //body(new CachingBodyInserter<>(BodyInserters.fromValue(requestBody)))
                        .bodyValue(requestBody!=null?requestBody:EMPTY_BODY)
                        .retrieve()
                        .toEntity(responseType)
                        .block();


        return responseFromExternal;
    }

    public static <T> ResponseEntity<T> getSend(final String endPoint,
                                                final HttpHeaders headers,
                                                final MultiValueMap<String, String> params,
                                                final ParameterizedTypeReference<T> responseType){
                                    
        return webClient.get()
                        .uri(endPoint, uriBuilder -> uriBuilder.queryParams(params).build())
                        .headers(h -> {
                            if(headers != null)
                                h.addAll(headers);
                            h.set(ENCODINGHEADER, ENCODINGVALUE);
                        })
                        .retrieve()
                        .toEntity(responseType)
                        .block();
    }

}
