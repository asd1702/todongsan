package com.todongsan.marketservice.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PointTransportProperties.class)
public class PointTransportConfig {
}
