package com.todongsan.marketservice.market.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MarketOutboxEvent {
    private Long id;

    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;

    private String payload;

    private LocalDateTime createdAt;
}
