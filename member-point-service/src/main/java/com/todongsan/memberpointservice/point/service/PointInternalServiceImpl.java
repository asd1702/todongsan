package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.*;
import com.todongsan.memberpointservice.point.dto.response.*;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointInternalServiceImpl implements PointInternalService {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public PointResult<EarnResponse> earn(String idempotencyKey, EarnRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            String newHash = RequestHashUtil.compute(
                    request.getMemberId(), request.getType(),
                    request.getAmount(), request.getReferenceType(), request.getReferenceId());
            if (newHash.equals(existing.get().getRequestHash())) {
                return PointResult.alreadyProcessed(new EarnResponse(existing.get()));
            }
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ErrorCode.POINT_INVALID_AMOUNT);
        }

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());

        memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.DOWN);
        memberRepository.earnPoint(request.getMemberId(), normalizedAmount);

        Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        PointHistory history = PointHistory.builder()
                .memberId(request.getMemberId())
                .type(histType)
                .amount(normalizedAmount)
                .balanceSnapshot(updated.getPointBalance())
                .reason(request.getReason())
                .referenceType(refType)
                .referenceId(request.getReferenceId())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();

        pointHistoryRepository.save(history);

        return PointResult.of(new EarnResponse(history));
    }

    @Override
    @Transactional(noRollbackFor = CustomException.class)
    public PointResult<SpendResponse> spend(String idempotencyKey, SpendRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PointHistory history = existing.get();
            String newHash = RequestHashUtil.compute(
                    request.getMemberId(), request.getType(),
                    request.getAmount(), request.getReferenceType(), request.getReferenceId());
            if (newHash.equals(history.getRequestHash())) {
                if (history.getStatus() == PointTransactionStatus.FAILED) {
                    if (ErrorCode.POINT_SPEND_FENCED.getCode().equals(history.getFailReason())) {
                        throw new CustomException(ErrorCode.POINT_SPEND_FENCED);
                    }
                    throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
                }
                return PointResult.alreadyProcessed(new SpendResponse(history));
            }
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ErrorCode.POINT_INVALID_AMOUNT);
        }

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());

        Member member = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        int affected = memberRepository.spendPoint(request.getMemberId(), normalizedAmount);
        if (affected == 0) {
            PointHistory failedHistory = PointHistory.builder()
                    .memberId(request.getMemberId())
                    .type(histType)
                    .amount(normalizedAmount)
                    .balanceSnapshot(member.getPointBalance())
                    .reason(request.getReason())
                    .referenceType(refType)
                    .referenceId(request.getReferenceId())
                    .idempotencyKey(idempotencyKey)
                    .requestHash(requestHash)
                    .status(PointTransactionStatus.FAILED)
                    .failReason(ErrorCode.POINT_INSUFFICIENT.getCode())
                    .build();
            pointHistoryRepository.save(failedHistory);
            throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
        }

        Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        PointHistory history = PointHistory.builder()
                .memberId(request.getMemberId())
                .type(histType)
                .amount(normalizedAmount)
                .balanceSnapshot(updated.getPointBalance())
                .reason(request.getReason())
                .referenceType(refType)
                .referenceId(request.getReferenceId())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();

        pointHistoryRepository.save(history);
        return PointResult.of(new SpendResponse(history));
    }

    private PointReferenceType parseReferenceType(String referenceType) {
        if (referenceType == null || referenceType.isBlank()) {
            return null;
        }
        try {
            return PointReferenceType.valueOf(referenceType);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.POINT_INVALID_REFERENCE_TYPE);
        }
    }

    private PointHistoryType parseHistoryType(String type) {
        if (type == null || type.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return PointHistoryType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Override
    public TransactionResponse getTransaction(String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        Optional<PointHistory> opt = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);

        if (opt.isEmpty()) {
            return TransactionResponse.builder()
                    .idempotencyKey(idempotencyKey)
                    .status("NOT_FOUND")
                    .build();

        }

        return toTransactionResponse(opt.get());
    }

    /**
     * 대사가 NOT_FOUND를 확인한 차감 요청을 실패로 먼저 기록해, 늦게 도착한 spend가 차감하지 못하게 한다 (ASYNC_POINT_FLOW.md §12.6).
     * 펜싱과 spend가 동시에 실행되면 idempotency_key UNIQUE가 하나만 통과시킨다. 펜싱 INSERT가 충돌하면 먼저 커밋된 기록을 반환한다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionResponse fenceSpend(String idempotencyKey, SpendRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        try {
            return transactionTemplate.execute(status -> fenceInTransaction(idempotencyKey, request));
        } catch (DataIntegrityViolationException e) {
            return pointHistoryRepository.findByIdempotencyKey(idempotencyKey)
                    .map(PointInternalServiceImpl::toTransactionResponse)
                    .orElseThrow(() -> e);
        }
    }

    private TransactionResponse fenceInTransaction(String idempotencyKey, SpendRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ErrorCode.POINT_INVALID_AMOUNT);
        }
        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().getRequestHash())) {
                throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return toTransactionResponse(existing.get());
        }

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());
        Member member = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        PointHistory fenced = PointHistory.builder()
                .memberId(request.getMemberId())
                .type(histType)
                .amount(request.getAmount().setScale(2, RoundingMode.DOWN))
                .balanceSnapshot(member.getPointBalance())
                .reason(request.getReason())
                .referenceType(refType)
                .referenceId(request.getReferenceId())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.FAILED)
                .failReason(ErrorCode.POINT_SPEND_FENCED.getCode())
                .build();
        return toTransactionResponse(pointHistoryRepository.saveAndFlush(fenced));
    }

    private static TransactionResponse toTransactionResponse(PointHistory history) {
        String status = history.getStatus() == PointTransactionStatus.SUCCEEDED ? "PROCESSED" : "FAILED";

        return TransactionResponse.builder()
                .idempotencyKey(history.getIdempotencyKey())
                .status(status)
                .memberId(history.getMemberId())
                .type(history.getType().name())
                .amount(history.getAmount().toPlainString())
                .referenceType(history.getReferenceType() != null ? history.getReferenceType().name() : null)
                .referenceId(history.getReferenceId())
                .balanceSnapshot(history.getBalanceSnapshot().toPlainString())
                .createdAt(history.getCreatedAt())
                .failReason(history.getFailReason())
                .build();
    }

    @Override
    @Transactional
    public SettlementResponse settle(String idempotencyKey, SettlementRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!idempotencyKey.equals(request.getSettlementId())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        for (SettlementItem item : request.getItems()) {
            if (item.getIdempotencyKey() == null || item.getIdempotencyKey().isBlank()) {
                throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
            }
        }

        List<BatchItemResult> results = new ArrayList<>();
        for (SettlementItem item : request.getItems()) {
            results.add(processEarnItem(
                    item.getPredictionId(), item.getMemberId(), item.getAmount(),
                    item.getReferenceType(), item.getReferenceId(),
                    item.getReason(), item.getIdempotencyKey(),
                    PointHistoryType.SETTLE_MARKET));
        }

        return SettlementResponse.builder()
                .marketId(request.getMarketId())
                .results(results)
                .build();
    }

    @Override
    @Transactional
    public RefundResponse refund(String idempotencyKey, RefundRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!idempotencyKey.equals(request.getRefundId())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        for (RefundItem item : request.getItems()) {
            if (item.getIdempotencyKey() == null || item.getIdempotencyKey().isBlank()) {
                throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
            }
        }

        List<BatchItemResult> results = new ArrayList<>();
        for (RefundItem item : request.getItems()) {
            PointHistoryType histType = "INSIGHT_REPORT".equals(item.getReferenceType())
                    ? PointHistoryType.REFUND_INSIGHT
                    : PointHistoryType.REFUND_MARKET;
            results.add(processEarnItem(
                    item.getPredictionId(), item.getMemberId(), item.getAmount(),
                    item.getReferenceType(), item.getReferenceId(),
                    item.getReason(), item.getIdempotencyKey(),
                    histType));
        }

        return RefundResponse.builder()
                .marketId(request.getMarketId())
                .results(results)
                .build();
    }

    @Override
    @Transactional
    public BatchItemResult creditMarketPoint(PointHistoryType type, Long predictionId, Long memberId, BigDecimal amount,
                                             String referenceType, Long referenceId, String reason, String idempotencyKey) {
        return processEarnItem(predictionId, memberId, amount, referenceType, referenceId, reason, idempotencyKey, type);
    }

    private BatchItemResult processEarnItem(Long predictionId, Long memberId, BigDecimal amount,
                                            String referenceType, Long referenceId,
                                            String reason, String idempotencyKey,
                                            PointHistoryType histType) {
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PointHistory history = existing.get();
            String newHash = RequestHashUtil.compute(memberId, histType.name(), amount, referenceType, referenceId);
            if (newHash.equals(history.getRequestHash())) {
                return BatchItemResult.builder()
                        .predictionId(predictionId)
                        .memberId(memberId)
                        .status("ALREADY_PROCESSED")
                        .amount(history.getAmount().toPlainString())
                        .balanceSnapshot(history.getBalanceSnapshot().toPlainString())
                        .build();
            }
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.getCode())
                    .amount(amount != null ? amount.toPlainString() : null)
                    .build();
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.POINT_INVALID_AMOUNT.getCode())
                    .amount(amount != null ? amount.toPlainString() : null)
                    .build();
        }

        PointReferenceType refType = null;
        if (referenceType != null && !referenceType.isBlank()) {
            try {
                refType = PointReferenceType.valueOf(referenceType);
            } catch (IllegalArgumentException e) {
                return BatchItemResult.builder()
                        .predictionId(predictionId)
                        .memberId(memberId)
                        .status("FAILED")
                        .errorCode(ErrorCode.POINT_INVALID_REFERENCE_TYPE.getCode())
                        .amount(amount.toPlainString())
                        .build();
            }
        }

        Optional<Member> memberOpt = memberRepository.findById(memberId);
        if (memberOpt.isEmpty()) {
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.MEMBER_NOT_FOUND.getCode())
                    .amount(amount.toPlainString())
                    .build();
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(memberId, histType.name(), amount, referenceType, referenceId);

        memberRepository.earnPoint(memberId, normalizedAmount);

        Member updated = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        PointHistory history = PointHistory.builder()
                .memberId(memberId)
                .type(histType)
                .amount(normalizedAmount)
                .balanceSnapshot(updated.getPointBalance())
                .reason(reason)
                .referenceType(refType)
                .referenceId(referenceId)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();

        pointHistoryRepository.save(history);

        return BatchItemResult.builder()
                .predictionId(predictionId)
                .memberId(memberId)
                .status("PROCESSED")
                .amount(normalizedAmount.toPlainString())
                .balanceSnapshot(updated.getPointBalance().toPlainString())
                .build();
    }


}
