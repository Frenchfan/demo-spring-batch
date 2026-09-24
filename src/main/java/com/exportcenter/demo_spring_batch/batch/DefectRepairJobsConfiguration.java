package com.exportcenter.demo_spring_batch.batch;

import com.exportcenter.demo_spring_batch.migration.V001__SeedSubsidyApplications;
import com.exportcenter.demo_spring_batch.mongo.SubsidyApplication;
import org.bson.Document;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static com.mongodb.client.model.Filters.eq;

@Configuration(proxyBeanMethods = false)
public class DefectRepairJobsConfiguration {
    private final SubsidyApplicationReader reader;
    private final ItemProcessor<SubsidyApplication, Document> defectProcessor;

    public DefectRepairJobsConfiguration(SubsidyApplicationReader reader,
            ItemProcessor<SubsidyApplication, Document> defectProcessor) {
        this.reader = reader;
        this.defectProcessor = defectProcessor;
    }

    @Bean
    @StepScope
    static SubsidyApplicationReader subsidyReader(MongoTemplate mongo,
            @Value("#{stepExecution.stepName}") String stepName) {
        return new SubsidyApplicationReader(mongo, scenario(stepName));
    }

    @Bean
    @StepScope
    static ItemProcessor<SubsidyApplication, Document> defectProcessor(JdbcTemplate jdbc,
            @Value("#{stepExecution.stepName}") String stepName) {
        var expected = new java.util.HashMap<Long, Document>();
        V001__SeedSubsidyApplications.readSource(jdbc)
                .forEach(d -> expected.put(d.getLong("subsidyId"), d));
        return item -> {
            Document source = expected.get(item.subsidyId());
            if (source == null) throw new IllegalArgumentException("Unknown subsidyId " + item.subsidyId());
            // The document carries the defect scenario in its stable ID. This also
            // keeps the processor correct when the same step is restarted.
            return hasDefect(item, source, Math.toIntExact(item.subsidyId() % 100)) ? source : null;
        };
    }

    private static int scenario(String stepName) {
        return Integer.parseInt(stepName.substring("repairDefect".length(), "repairDefect".length() + 2));
    }
    @Bean Job repairDefect01Job(JobRepository r, Step repairDefect01Step){return new JobBuilder("repairDefect01Job",r).start(repairDefect01Step).build();}
    @Bean Job repairDefect02Job(JobRepository r, Step repairDefect02Step){return new JobBuilder("repairDefect02Job",r).start(repairDefect02Step).build();}
    @Bean Job repairDefect03Job(JobRepository r, Step repairDefect03Step){return new JobBuilder("repairDefect03Job",r).start(repairDefect03Step).build();}
    @Bean Job repairDefect04Job(JobRepository r, Step repairDefect04Step){return new JobBuilder("repairDefect04Job",r).start(repairDefect04Step).build();}
    @Bean Job repairDefect05Job(JobRepository r, Step repairDefect05Step){return new JobBuilder("repairDefect05Job",r).start(repairDefect05Step).build();}
    @Bean Job repairDefect06Job(JobRepository r, Step repairDefect06Step){return new JobBuilder("repairDefect06Job",r).start(repairDefect06Step).build();}
    @Bean Job repairDefect07Job(JobRepository r, Step repairDefect07Step){return new JobBuilder("repairDefect07Job",r).start(repairDefect07Step).build();}
    @Bean Job repairDefect08Job(JobRepository r, Step repairDefect08Step){return new JobBuilder("repairDefect08Job",r).start(repairDefect08Step).build();}

    @Bean Step repairDefect01Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,1);}
    @Bean Step repairDefect02Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,2);}
    @Bean Step repairDefect03Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,3);}
    @Bean Step repairDefect04Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,4);}
    @Bean Step repairDefect05Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,5);}
    @Bean Step repairDefect06Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,6);}
    @Bean Step repairDefect07Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,7);}
    @Bean Step repairDefect08Step(JobRepository r, PlatformTransactionManager tx, MongoTemplate m, JdbcTemplate j){return step(r,tx,m,j,8);}

    private Step step(JobRepository r, PlatformTransactionManager tx, MongoTemplate mongo, JdbcTemplate jdbc, int scenario) {
        ItemWriter<Document> writer = chunk -> {
            for (Document d : chunk) {
                mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                        .replaceOne(eq("subsidyId", d.getLong("subsidyId")), d);
            }
        };
        var step = new StepBuilder("repairDefect%02dStep".formatted(scenario), r)
                .<SubsidyApplication, Document>chunk(scenario == 8 ? 10 : 25)
                .transactionManager(tx)
                .reader(reader).processor(defectProcessor).writer(writer)
                .skipListener(new org.springframework.batch.core.listener.SkipListener<>() {
                    public void onSkipInRead(@NonNull Throwable t) {
                        quarantine(mongo, null, t);
                    }

                    public void onSkipInProcess(@NonNull SubsidyApplication item, @NonNull Throwable t) {
                        Document snapshot = new Document();
                        mongo.getConverter().write(item, snapshot);
                        quarantine(mongo, snapshot, t);
                    }

                    public void onSkipInWrite(@NonNull Document item, @NonNull Throwable t) {
                        quarantine(mongo, item, t);
                    }
                })
                .faultTolerant().skipLimit(5).skip(IllegalArgumentException.class)
                .retryLimit(scenario == 4 || scenario == 5 ? 3 : 1).retry(RuntimeException.class)
                .build();
        return step;
    }

    private static boolean hasDefect(SubsidyApplication d, Document source, int scenario) {
        var company = d.company();
        var industry = d.industry();
        var person = d.personInCharge();
        return switch (scenario) {
            case 1 -> company != null && company.title() != null
                    && !company.title().equals(source.get("company", Document.class).getString("title"));
            case 2 -> company == null || company.legalAddress() == null;
            case 3 -> company != null && !java.util.Objects.equals(company.factAddress(),
                    source.get("company", Document.class).getString("factAddress"));
            case 4 -> d.approvedSubsidy() != null && source.get("approvedSubsidy") instanceof org.bson.types.Decimal128 expected
                    && d.approvedSubsidy().bigDecimalValue().compareTo(expected.bigDecimalValue()) != 0;
            case 5 -> d.requestedSubsidy() instanceof org.bson.types.Decimal128 requested
                    && source.get("requestedSubsidy") instanceof org.bson.types.Decimal128 expected
                    && requested.bigDecimalValue().compareTo(expected.bigDecimalValue()) != 0;
            case 6 -> industry != null && !java.util.Objects.equals(industry.code(),
                    source.get("industry", Document.class).getString("code"));
            case 7 -> person != null && !java.util.Objects.equals(person.sourceId(),
                    source.get("personInCharge", Document.class).getLong("id"));
            case 8 -> d.requestedSubsidy() instanceof String;
            default -> false;
        };
    }

    private static void quarantine(MongoTemplate mongo, Document item, Throwable t) {
        mongo.getCollection("subsidy_quarantine").insertOne(new Document("item", item)
                .append("reason", t == null ? "unknown" : t.getMessage()));
    }
}
