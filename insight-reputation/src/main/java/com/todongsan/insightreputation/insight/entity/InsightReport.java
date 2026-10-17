package com.todongsan.insightreputation.insight.entity;

import com.todongsan.insightreputation.enums.InsightReportStatus;
import com.todongsan.insightreputation.enums.InsightReportType;
import com.todongsan.insightreputation.global.config.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.LocalDateTime;

/**
 * AI 분석 리포트 상태 관리
 * 
 * 상태 전이: PENDING → PROCESSING → DONE / FAILED
 * 역방향 전이 불가. PROCESSING 전이 시 processingStartedAt 기록.
 * retry_count >= 3: 영구 FAILED. 스케줄러 재시도 없음.
 */
@Entity
@Table(
    name = "insight_report",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_type_reference", 
        columnNames = {"type", "reference_id"}
    ),
    indexes = {
        @Index(name = "idx_type", columnList = "type"),
        @Index(name = "idx_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private InsightReportType type;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InsightReportStatus status = InsightReportStatus.PENDING;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "analysis_data", columnDefinition = "JSON")
    private String analysisData;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "raw_prompt", columnDefinition = "TEXT")
    private String rawPrompt;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "failed_reason", columnDefinition = "TEXT")
    private String failedReason;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "retry_count", nullable = false)
    private Byte retryCount = 0;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "report_content", columnDefinition = "TEXT")
    private String reportContent;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    public static final int MAX_RETRY_COUNT = 3;

    @Builder
    public InsightReport(InsightReportType type, Long referenceId, Long requestedBy, InsightReportStatus status, Integer retryCount) {
        this.type = type;
        this.referenceId = referenceId;
        this.requestedBy = requestedBy;
        this.status = status != null ? status : InsightReportStatus.PENDING;
        this.retryCount = retryCount != null ? retryCount.byteValue() : 0;
    }

    public void startProcessing() {
        if (this.status != InsightReportStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 처리 시작 가능: current=" + this.status);
        }
        this.status = InsightReportStatus.PROCESSING;
        this.processingStartedAt = LocalDateTime.now();
    }

    public void complete(String analysisResult) {
        if (this.status != InsightReportStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태에서만 완료 가능: current=" + this.status);
        }
        this.status = InsightReportStatus.DONE;
        this.summary = analysisResult;
        this.reportContent = analysisResult;
        this.generatedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status != InsightReportStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태에서만 실패로 전환 가능: current=" + this.status);
        }
        this.status = InsightReportStatus.FAILED;
        this.failedReason = reason;
        this.retryCount++;
    }

    public void resetForRetry() {
        if (this.status == InsightReportStatus.DONE) {
            throw new IllegalStateException("DONE 상태에서는 재시도로 전환할 수 없음: current=" + this.status);
        }
        if (this.status != InsightReportStatus.FAILED && this.status != InsightReportStatus.PROCESSING) {
            throw new IllegalStateException("FAILED 또는 PROCESSING 상태에서만 재시도 가능: current=" + this.status);
        }
        this.status = InsightReportStatus.PENDING;
        this.processingStartedAt = null;
        // retry_count는 fail() 메서드에서 이미 증가했으므로 여기서는 증가하지 않음
    }

    public boolean canRetry() {
        return this.retryCount < MAX_RETRY_COUNT;
    }

    public void updateAnalysisData(String analysisData) {
        this.analysisData = analysisData;
    }
}
