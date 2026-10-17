package com.todongsan.insightreputation.global.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // REB API 서버가 JSON 데이터를 text/html 로 잘못 응답하는 문제 해결.
        // defaultCharset=UTF_8 을 명시하지 않으면 text/html·text/plain 의 HTTP 기본값(ISO-8859-1)으로
        // 디코드되어 한국어가 깨진다.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setDefaultCharset(StandardCharsets.UTF_8);
        converter.setSupportedMediaTypes(List.of(
            MediaType.APPLICATION_JSON,
            new MediaType("text", "html",  StandardCharsets.UTF_8),
            new MediaType("text", "plain", StandardCharsets.UTF_8)
        ));

        restTemplate.getMessageConverters().add(0, converter);
        return restTemplate;
    }

    @Bean
    @Qualifier("marketRestTemplate")
    public RestTemplate marketRestTemplate(
            @org.springframework.beans.factory.annotation.Value("${client.market.connect-timeout-ms:500}") int connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${client.market.read-timeout-ms:1000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        RestTemplate restTemplate = new RestTemplate(factory);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setDefaultCharset(StandardCharsets.UTF_8);
        converter.setSupportedMediaTypes(List.of(
            MediaType.APPLICATION_JSON,
            new MediaType("text", "html", StandardCharsets.UTF_8),
            new MediaType("text", "plain", StandardCharsets.UTF_8)
        ));
        restTemplate.getMessageConverters().add(0, converter);
        return restTemplate;
    }

    // Claude API는 max_tokens=4000 응답 생성에 최대 2분 이상 걸릴 수 있으므로 별도 타임아웃 설정
    @Bean
    @Qualifier("claudeRestTemplate")
    public RestTemplate claudeRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 연결 10초
        factory.setReadTimeout(120_000);     // 응답 대기 120초

        RestTemplate restTemplate = new RestTemplate(factory);

        // RestTemplate 기본 StringHttpMessageConverter 는 ISO-8859-1 을 사용한다.
        // Claude API 응답(application/json, UTF-8)을 올바르게 처리하도록 UTF-8 로 교체한다.
        restTemplate.getMessageConverters().replaceAll(converter ->
            converter instanceof StringHttpMessageConverter
                ? new StringHttpMessageConverter(StandardCharsets.UTF_8)
                : converter
        );

        return restTemplate;
    }
}
