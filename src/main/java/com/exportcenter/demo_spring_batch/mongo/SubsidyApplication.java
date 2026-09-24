package com.exportcenter.demo_spring_batch.mongo;

import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.Field;

/** Mongo snapshot of subsidy, company, industry and employee tables in H2. */
@Document(collection = SubsidyApplication.COLLECTION)
public record SubsidyApplication(
        @MongoId String id,
        String dataset,
        Integer schemaVersion,
        Long subsidyId,
        // Intentionally Object: legacy documents contain either BSON Decimal128 or String.
        // BigDecimal here would silently normalize the string and conceal defect 08.
        Object requestedSubsidy,
        Decimal128 approvedSubsidy,
        Company company,
        Industry industry,
        PersonInCharge personInCharge) {

    public static final String COLLECTION = "subsidy_applications";

    public record Company(@Field("id") Long sourceId, String title, String legalAddress, String factAddress) { }

    public record Industry(@Field("id") Long sourceId, String name, String code) { }

    public record PersonInCharge(@Field("id") Long sourceId, String name, String position, Long companyId) { }
}
