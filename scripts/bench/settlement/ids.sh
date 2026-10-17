# 정산 실험 데이터 ID 대역 (seed / verify / cleanup / run / fault 공통, source 해서 쓴다)
#
# 모두 현재 AUTO_INCREMENT보다 작은 빈 대역이다 (큰 ID를 직접 넣으면 AUTO_INCREMENT가 밀린다, §15 F7).
#   MP 회원     500,001 ~ 500,000 + MEMBER_POOL_MAX      (member AUTO_INCREMENT 약 999,000)
#   마켓        800,000 + run                            (run 1~40)
#   옵션        810,000 + run×2 (+0 결과 A, +1 패배 B)
#   예측        100,000 + run×20,000 + i                 (승자+패자 ≤ 19,999, 최대 919,999 < market_prediction AUTO_INCREMENT 약 920,100)
# 승자 i = 1..N → 회원 500,000+i, 패자 i = N+1..N+L → 회원 500,000+i
MEMBER_BASE=500000
MEMBER_POOL_MAX=15000
MARKET_BASE=800000
OPTION_BASE=810000
PREDICTION_BASE=100000
PREDICTION_STRIDE=20000
RUN_MAX=40

pred_lo() { echo $((PREDICTION_BASE + $1 * PREDICTION_STRIDE)); }
pred_hi() { echo $((PREDICTION_BASE + $1 * PREDICTION_STRIDE + PREDICTION_STRIDE - 1)); }
