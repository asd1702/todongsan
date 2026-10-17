-- Debezium MySQL Connector 전용 계정 (market-mysql)
-- 기존 volume에는 init 스크립트가 실행되지 않으므로 수동으로 1회 적용한다. 재실행해도 안전하다.
--   docker exec -i market-mysql mysql -uroot -p<MARKET_MYSQL_ROOT_PASSWORD> < infra/debezium/market-debezium-user.sql
-- 로컬 전용 비밀번호다. EC2/RDS 환경에서는 반드시 변경한다.

CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium-local-password';

-- binlog 읽기 및 스냅샷에 필요한 전역 권한
GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';

-- 데이터 조회는 market DB로 한정
GRANT SELECT ON market.* TO 'debezium'@'%';

FLUSH PRIVILEGES;
