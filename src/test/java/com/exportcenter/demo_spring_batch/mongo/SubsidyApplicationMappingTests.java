package com.exportcenter.demo_spring_batch.mongo;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SubsidyApplicationMappingTests {
    @Test
    void mapsNestedStructureAndPreservesDamagedAmountType() {
        var context = new MongoMappingContext();
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        var amount = new Decimal128(new BigDecimal("120000.50"));
        var bson = new Document("_id", "subsidy-v1-8")
                .append("dataset", "subsidies-v1").append("schemaVersion", 1)
                .append("subsidyId", 8L).append("requestedSubsidy", "120000.50")
                .append("approvedSubsidy", amount)
                .append("company", new Document("id", 2L).append("title", "Компания")
                        .append("factAddress", "Москва"))
                .append("industry", new Document("id", 3L).append("code", "10.10").append("name", "Отрасль"))
                .append("personInCharge", new Document("id", 4L).append("companyId", 2L)
                        .append("name", "Иван").append("position", "Менеджер"));
        var model = converter.read(SubsidyApplication.class, bson);
        assertThat(model.id()).isEqualTo("subsidy-v1-8");
        assertThat(model.company().sourceId()).isEqualTo(2L);
        assertThat(model.industry().sourceId()).isEqualTo(3L);
        assertThat(model.personInCharge().sourceId()).isEqualTo(4L);
        assertThat(model.company().legalAddress()).isNull();
        assertThat(model.company().factAddress()).isEqualTo("Москва");
        assertThat(model.personInCharge().companyId()).isEqualTo(2L);
        assertThat(model.industry().code()).isEqualTo("10.10");
        assertThat(model.requestedSubsidy()).isInstanceOf(String.class);
        assertThat(model.approvedSubsidy()).isEqualTo(amount);
        bson.put("requestedSubsidy", amount);
        var correct = converter.read(SubsidyApplication.class, bson);
        assertThat(correct.requestedSubsidy()).isInstanceOf(Decimal128.class).isEqualTo(amount);
        var written = new Document();
        converter.write(correct, written);
        assertThat(written.get("requestedSubsidy")).isEqualTo(amount);
        assertThat(written.get("company", Document.class).getLong("id")).isEqualTo(2L);
        assertThat(context.getRequiredPersistentEntity(SubsidyApplication.class).getCollection())
                .isEqualTo("subsidy_applications");
    }
}
