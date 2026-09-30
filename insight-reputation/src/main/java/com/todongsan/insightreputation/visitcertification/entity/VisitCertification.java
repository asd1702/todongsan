package com.todongsan.insightreputation.visitcertification.entity;

import com.todongsan.insightreputation.enums.VisitCertMethod;
import com.todongsan.insightreputation.global.config.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "visit_certification",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_member_location", 
        columnNames = {"member_id", "sido", "sigu"}
    ),
    indexes = @Index(name = "idx_member_id", columnList = "member_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCertification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "sido", nullable = false, length = 50)
    private String sido;

    @Column(name = "sigu", nullable = false, length = 50)
    private String sigu;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private VisitCertMethod method;

    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "comment_content", columnDefinition = "TEXT")
    private String commentContent;

    @Column(name = "battle_id")
    private Long battleId;

    @Column(name = "last_certified_at", nullable = false)
    private LocalDateTime lastCertifiedAt;

    @Column(name = "certified_at", nullable = false)
    private LocalDateTime certifiedAt;

    @Builder
    public VisitCertification(Long memberId, String sido, String sigu, 
                             VisitCertMethod method, BigDecimal latitude, BigDecimal longitude,
                             String commentContent, Long battleId) {
        this.memberId = memberId;
        this.sido = sido;
        this.sigu = sigu;
        this.method = method;
        this.latitude = latitude;
        this.longitude = longitude;
        this.commentContent = commentContent;
        this.battleId = battleId;
        this.certifiedAt = LocalDateTime.now();
        this.lastCertifiedAt = LocalDateTime.now();
    }

    public void updateCertification(VisitCertMethod method, BigDecimal latitude, BigDecimal longitude,
                                   String commentContent, Long battleId) {
        this.method = method;
        this.latitude = latitude;
        this.longitude = longitude;
        this.commentContent = commentContent;
        this.battleId = battleId;
        this.lastCertifiedAt = LocalDateTime.now();
    }

    public void updateGpsCertification(BigDecimal latitude, BigDecimal longitude) {
        this.method = VisitCertMethod.GPS;
        this.latitude = latitude;
        this.longitude = longitude;
        this.commentContent = null;
        this.battleId = null;
        this.lastCertifiedAt = LocalDateTime.now();
    }

    public void updateCommentCertification(String commentContent, Long battleId) {
        this.method = VisitCertMethod.COMMENT;
        this.latitude = null;
        this.longitude = null;
        this.commentContent = commentContent;
        this.battleId = battleId;
        this.lastCertifiedAt = LocalDateTime.now();
    }
}
