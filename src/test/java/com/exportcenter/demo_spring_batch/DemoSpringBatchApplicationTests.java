package com.exportcenter.demo_spring_batch;

import com.exportcenter.demo_spring_batch.migration.V001__SeedSubsidyApplications;
import com.mongodb.client.MongoClient;
import io.mongock.driver.mongodb.sync.v4.driver.MongoSync4Driver;
import io.mongock.runner.standalone.MongockStandalone;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.flywaydb.core.Flyway;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:seed-test;DB_CLOSE_DELAY=-1",
    "spring.mongodb.port=0", "spring.mongodb.database=batch_seed_test",
    "logging.level.org.springframework.web=INFO", "logging.level.org.springframework.boot.web=INFO"
})
class DemoSpringBatchApplicationTests {
    @DynamicPropertySource
    static void embeddedDirectory(DynamicPropertyRegistry properties) throws IOException {
        var directory = Files.createTempDirectory("batch-mongo-test-").toString();
        properties.add("de.flapdoodle.mongodb.embedded.database-dir", () -> directory);
    }

    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MongoTemplate mongo;
    @Autowired MongoClient client;
    @Autowired Flyway flyway;
    @Autowired JobRepository jobRepository;
    @Autowired JobOperator jobOperator;
    @Autowired Job repairSubsidiesJob;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Map<String, Job> jobs;

    @Test
    @DirtiesContext
    void allEightChunkJobsReadAfterSeedAndCanRunAgainWithoutWrites() throws Exception {
        for (int scenario = 1; scenario <= 8; scenario++) {
            Job job = jobs.get("repairDefect%02dJob".formatted(scenario));
            var first = jobOperator.start(job, new JobParametersBuilder()
                    .addString("run", "first").toJobParameters());
            assertThat(first.getStatus()).as(job.getName()).isEqualTo(BatchStatus.COMPLETED);
            var step = first.getStepExecutions().iterator().next();
            assertThat(step.getReadCount()).isEqualTo(15);
            assertThat(step.getWriteCount()).isEqualTo(15);
            var second = jobOperator.start(job, new JobParametersBuilder()
                    .addString("run", "second").toJobParameters());
            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            var repeated = second.getStepExecutions().iterator().next();
            assertThat(repeated.getReadCount()).isEqualTo(15);
            assertThat(repeated.getWriteCount()).as("Second launch of %s", job.getName()).isZero();
            assertThat(repeated.getFilterCount()).isEqualTo(15);
        }
        for (Document expected : V001__SeedSubsidyApplications.readSource(jdbc)) {
            assertThat(mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                    .find(eq("_id", expected.get("_id"))).first()).isEqualTo(expected);
        }
    }

    @Test
    @DirtiesContext
    void restartUsesCommittedKeyAndHandlesMongoWriteBeforeFailure() throws Exception {
        var realCollection = mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION);
        var failingCollection = org.mockito.Mockito.spy(realCollection);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            Document replacement = invocation.getArgument(1);
            // Write succeeds in Mongo, then connection fails. JDBC rollback cannot undo Mongo.
            var result = realCollection.replaceOne(invocation.getArgument(0), replacement);
            if (replacement.getLong("subsidyId") == 1108L && fail.get()) {
                throw new IllegalStateException("Injected write failure after Mongo commit");
            }
            return result;
        }).when(failingCollection).replaceOne(org.mockito.ArgumentMatchers.any(org.bson.conversions.Bson.class),
                org.mockito.ArgumentMatchers.any(Document.class));
        org.mockito.Mockito.doReturn(failingCollection).when(mongo)
                .getCollection(V001__SeedSubsidyApplications.COLLECTION);
        Job job = jobs.get("repairDefect08Job");
        var failed = jobOperator.start(job, new JobParametersBuilder()
                .addString("run", "restart-test").toJobParameters());
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        var persisted = jobRepository.getJobExecution(failed.getId());
        assertThat(persisted.getStepExecutions().iterator().next().getExecutionContext()
                .getLong(com.exportcenter.demo_spring_batch.batch.SubsidyApplicationReader.CHECKPOINT))
                .isEqualTo(908L);
        fail.set(false);
        var restarted = jobOperator.restart(persisted);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(restarted.getId()).isNotEqualTo(failed.getId());
        var step = restarted.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).isEqualTo(5);
        assertThat(step.getFilterCount()).isGreaterThanOrEqualTo(2);
        assertThat(step.getWriteCount() + step.getFilterCount()).isEqualTo(5);
        for (Document expected : V001__SeedSubsidyApplications.readSource(jdbc)) {
            if (expected.getLong("subsidyId") % 100 == 8) {
                assertThat(realCollection.find(eq("_id", expected.get("_id"))).first()).isEqualTo(expected);
            }
        }
        assertThat(realCollection.countDocuments()).isEqualTo(1500);
    }

    @Test
    void flywayOwnsBatchSchemaAndJdbcRepositoryPersistsARealJobExecution() throws Exception {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = 'TEST' AND TABLE_NAME LIKE 'BATCH_%'
            """, Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME LIKE 'BATCH_%'
            """, Long.class)).isZero();
        // Updating Batch must prompt a deliberate schema compatibility review.
        try (var upstream = new ClassPathResource("org/springframework/batch/core/schema-h2.sql").getInputStream();
             var migration = new ClassPathResource("db/migration/V3__create_spring_batch_metadata.sql").getInputStream()) {
            var charset = java.nio.charset.StandardCharsets.UTF_8;
            assertThat(new String(migration.readAllBytes(), charset).replace("\r\n", "\n"))
                    .endsWith(new String(upstream.readAllBytes(), charset).replace("\r\n", "\n"));
        }
        var step = new StepBuilder("metadataSmokeStep", jobRepository)
                .tasklet((contribution, context) -> {
                    context.getStepContext().getStepExecution().getExecutionContext().putString("checkpoint", "saved");
                    return RepeatStatus.FINISHED;
                }, transactionManager).build();
        var job = new JobBuilder("metadataSmokeJob", jobRepository).start(step).build();
        var parameters = new JobParametersBuilder().addString("dataset", "subsidies-v1").toJobParameters();
        var execution = jobOperator.start(job, parameters);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM TEST.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                String.class, execution.getId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEST.BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = ?",
                Long.class, execution.getId())).isEqualTo(1);
        var saved = jobRepository.getJobExecution(execution.getId());
        assertThat(saved).isNotNull();
        var savedStep = saved.getStepExecutions().iterator().next();
        assertThat(savedStep.getExecutionContext().getString("checkpoint")).isEqualTo("saved");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT STATUS FROM TEST.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                String.class, execution.getId())).isEqualTo("COMPLETED");
    }

    @Test
    @DirtiesContext
    void repairJobCanBeLaunchedExplicitlyAndFixesTheSeededMongoDocuments() throws Exception {
        var execution = jobOperator.start(repairSubsidiesJob,
                new JobParametersBuilder().addString("dataset", "subsidies-v1")
                        .addLong("runId", System.nanoTime()).toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().iterator().next().getExecutionContext()
                .getLong("repairedCount")).isEqualTo(120L);
        assertThat(mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                .countDocuments()).isEqualTo(1500);
        assertThat(mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                .countDocuments(new org.bson.Document("requestedSubsidy", new org.bson.Document("$type", "string"))))
                .isZero();
    }

    @Test
    @DirtiesContext
    void seedsRelatedDataAndExactly120DefectsAndPreservesRepairsOnRerun() {
        assertThat(count("industry")).isEqualTo(12);
        assertThat(count("company")).isEqualTo(300);
        assertThat(count("employee")).isEqualTo(900);
        assertThat(count("subsidy")).isEqualTo(1500);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM TEST.subsidy s JOIN TEST.employee e ON e.id = s.person_in_charge_id
            WHERE e.company_id <> s.company_id OR s.requested_subsidy <= 0
               OR s.approved_subsidy < 0 OR s.approved_subsidy > s.requested_subsidy
            """, Long.class)).isZero();
        var collection = mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION);
        assertThat(collection.countDocuments()).isEqualTo(1500);
        Map<Long, Document> actual = new HashMap<>();
        collection.find().forEach(d -> actual.put(d.getLong("subsidyId"), d));
        var source = V001__SeedSubsidyApplications.readSource(jdbc);
        int intact = 0;
        Map<Integer, Integer> defects = new HashMap<>();
        for (Document expected : source) {
            long id = expected.getLong("subsidyId");
            Document found = actual.get(id);
            assertThat(found).isNotNull();
            int scenario = (int) (id % 100);
            if (scenario < 1 || scenario > 8) {
                assertThat(found).isEqualTo(expected);
                intact++;
                continue;
            }
            assertThat(found).isNotEqualTo(expected);
            defects.merge(scenario, 1, Integer::sum);
            switch (scenario) {
                case 1 -> assertThat(found.get("company", Document.class).getString("title")).startsWith("  ").endsWith("  ");
                case 2 -> assertThat(found.get("company", Document.class)).doesNotContainKey("legalAddress");
                case 3 -> assertThat(found.get("company", Document.class).getString("factAddress")).contains("устаревший");
                case 4 -> assertThat(found.get("approvedSubsidy", Decimal128.class).bigDecimalValue())
                    .isGreaterThan(found.get("requestedSubsidy", Decimal128.class).bigDecimalValue());
                case 5 -> assertThat(found.get("requestedSubsidy", Decimal128.class).bigDecimalValue()).isNegative();
                case 6 -> assertThat(found.get("industry", Document.class).getString("code")).isEqualTo("99.99");
                case 7 -> assertThat(found.get("personInCharge", Document.class).getLong("id")).isEqualTo(999999L);
                case 8 -> assertThat(found.get("requestedSubsidy")).isInstanceOf(String.class);
            }
        }
        assertThat(intact).isEqualTo(1380);
        assertThat(defects).hasSize(8);
        assertThat(defects.values()).allMatch(count -> count == 15);
        Document repaired = source.getFirst();
        collection.replaceOne(eq("_id", repaired.get("_id")), repaired);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        MongockStandalone.builder()
            .setDriver(MongoSync4Driver.withDefaultLock(client, mongo.getDb().getName()))
            .addMigrationScanPackage("com.exportcenter.demo_spring_batch.migration")
            .addDependency(JdbcTemplate.class, jdbc)
            .setTransactional(false).buildRunner().execute();
        assertThat(collection.find(eq("_id", repaired.get("_id"))).first()).isEqualTo(repaired);
        // Retry a partial seed without overwriting records already repaired by Batch.
        collection.deleteOne(eq("_id", "subsidy-v1-1500"));
        new V001__SeedSubsidyApplications().execute(mongo.getDb(), jdbc);
        assertThat(collection.countDocuments()).isEqualTo(1500);
        assertThat(collection.find(eq("_id", repaired.get("_id"))).first()).isEqualTo(repaired);
        assertThat(count("subsidy")).isEqualTo(1500);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM TEST." + table, Long.class);
    }
}
