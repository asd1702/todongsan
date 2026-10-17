package com.todongsan.memberpointservice.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포인트 적립 요청 consumer 전용 DB 커넥션 풀 (벌크헤드, ASYNC_POINT_FLOW.md §17.7).
 * spend 같은 API와 한 풀을 나눠 쓰면 커밋이 잠깐 느려질 때 풀이 바닥나 양쪽이 함께 멈추고 회복도 늦어진다.
 *
 * 접속 정보와 Hikari 설정(spring.datasource.*, spring.datasource.hikari.*)은 API 풀과 같고 크기와 이름만 다르다.
 * DataSource·JdbcTemplate 빈으로 노출하지 않는다. 노출하면 Spring Boot의 기본 DataSource·JdbcTemplate 자동 설정이 꺼진다.
 */
@Component
public class ConsumerDatabase implements DisposableBean {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ConsumerDatabase(
            DataSourceProperties properties,
            Environment environment,
            @Value("${member-point.kafka.market-point-requests.pool-size:6}") int poolSize
    ) {
        this.dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
        dataSource.setPoolName("market-point-consumer");
        dataSource.setMaximumPoolSize(poolSize);
        dataSource.setMinimumIdle(poolSize);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public TransactionTemplate transactionTemplate() {
        return transactionTemplate;
    }

    HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
