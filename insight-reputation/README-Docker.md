# Docker 환경 구성 가이드

## 환경 파일 구성

### 📁 환경 파일 분리
- **`.env.local`** - 로컬 개발/테스트용 (외부 MySQL 컨테이너)
- **`.env.rds`** - RDS 운영 환경용

## 🚀 사용법

### 로컬 개발 환경
```bash
# 로컬 MySQL 컨테이너 사용
docker-compose -f docker-compose.local.yml up -d

# 로그 확인
docker-compose -f docker-compose.local.yml logs -f

# 중지
docker-compose -f docker-compose.local.yml down
```

### RDS 환경
```bash
# RDS 사용 (명시적)
docker-compose -f docker-compose.rds.yml up -d

# RDS 사용 (기본값)
docker-compose up -d

# 중지
docker-compose -f docker-compose.rds.yml down
# 또는
docker-compose down
```

## 🔧 환경 설정 차이점

| 구분 | 로컬 (.env.local) | RDS (.env.rds) |
|------|-------------------|----------------|
| **DB 호스트** | `insight-mysql` | `todongsan-dev.cdwkgswgkayp.ap-northeast-2.rds.amazonaws.com` |
| **DB 포트** | `3306` | `3306` |
| **DB 사용자** | `insight_user` | `admin` |
| **SSL** | `false` | `true` |
| **네트워크** | `infra_default` | `insight-rds-network` |
| **서비스 URL** | `localhost:808x` | `service-name:808x` |

## 📋 헬스체크

```bash
# 애플리케이션 헬스체크
curl http://localhost:8083/actuator/health

# 컨테이너 상태 확인
docker-compose ps
```

## 🛠 트러블슈팅

### MySQL 연결 문제
- 로컬: `insight-mysql` 컨테이너가 실행 중인지 확인
- RDS: AWS RDS 인스턴스 상태 및 보안 그룹 확인

### 네트워크 문제
- 로컬: `infra_default` 네트워크에 연결되어 있는지 확인
- RDS: 인터넷 연결 및 AWS 네트워크 설정 확인
