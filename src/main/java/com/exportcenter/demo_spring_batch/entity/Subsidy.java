package com.exportcenter.demo_spring_batch.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
public class Subsidy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;
    @Column(precision = 19, scale = 2)
    private BigDecimal approvedSubsidy;
    @Column(precision = 19, scale = 2)
    private BigDecimal requestedSubsidy;
    @ManyToOne(fetch = FetchType.LAZY)
    private Industry industry;
    @ManyToOne(fetch = FetchType.LAZY)
    private Company company;
    @ManyToOne(fetch = FetchType.LAZY)
    private Employee personInCharge;

}
