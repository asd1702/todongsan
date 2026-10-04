package com.todongsan.memberpointservice.global.config;

import com.todongsan.memberpointservice.global.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 외부 공개 API
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/oauth/kakao").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/token/refresh").permitAll()
                        // 내부 연계 API (서비스 간 호출, JWT 없이 호출됨 — Gateway에 /internal/** 경로 없어 외부 접근 자동 차단)
                        .requestMatchers("/internal/**").permitAll()
                        // 헬스·readiness 프로브 (노출은 health만, 워밍업 뒤 UP: SPEND_CIRCUIT_BREAKER.md §6)
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Swagger
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 나머지 외부 API는 JWT 인증 필수
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
