package com.exportcenter.demo_spring_batch.batch;

import com.exportcenter.demo_spring_batch.migration.V001__SeedSubsidyApplications;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;

@Configuration(proxyBeanMethods = false)
public class RepairSubsidiesJobConfiguration {

    @Bean
    Job repairSubsidiesJob(JobRepository jobRepository, Step repairSubsidiesStep) {
        return new JobBuilder("repairSubsidiesJob", jobRepository)
                .start(repairSubsidiesStep)
                .build();
    }

    @Bean
    Step repairSubsidiesStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             MongoTemplate mongoTemplate,
                             JdbcTemplate jdbcTemplate) {
        return new StepBuilder("repairSubsidiesStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    Map<Long, Document> expected = new HashMap<>();
                    V001__SeedSubsidyApplications.readSource(jdbcTemplate)
                            .forEach(document -> expected.put(document.getLong("subsidyId"), document));

                    var collection = mongoTemplate.getCollection(V001__SeedSubsidyApplications.COLLECTION);
                    long repaired = 0;
                    for (Document actual : collection.find()) {
                        Long subsidyId = actual.getLong("subsidyId");
                        Document source = expected.get(subsidyId);
                        if (source != null && !source.equals(actual)) {
                            collection.replaceOne(eq("_id", actual.get("_id")), source, new ReplaceOptions());
                            repaired++;
                        }
                    }
                    chunkContext.getStepContext().getStepExecution().getExecutionContext()
                            .putLong("repairedCount", repaired);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
