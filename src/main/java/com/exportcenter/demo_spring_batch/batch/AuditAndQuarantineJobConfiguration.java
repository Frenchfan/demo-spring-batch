package com.exportcenter.demo_spring_batch.batch;

import com.exportcenter.demo_spring_batch.migration.V001__SeedSubsidyApplications;
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

import java.util.HashSet;

import static com.mongodb.client.model.Filters.exists;

@Configuration(proxyBeanMethods = false)
public class AuditAndQuarantineJobConfiguration {

    @Bean
    Job auditSubsidiesJob(JobRepository repo, Step auditSubsidiesStep) {
        return new JobBuilder("auditSubsidiesJob", repo).start(auditSubsidiesStep).build();
    }

    @Bean
    Step auditSubsidiesStep(JobRepository repo, PlatformTransactionManager tx,
                            MongoTemplate mongo, JdbcTemplate jdbc) {
        return new StepBuilder("auditSubsidiesStep", repo).tasklet((contribution, context) -> {
            var expected = new HashSet<Long>();
            V001__SeedSubsidyApplications.readSource(jdbc)
                    .forEach(d -> expected.add(d.getLong("subsidyId")));
            long actual = mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                    .countDocuments(exists("subsidyId"));
            context.getStepContext().getStepExecution().getExecutionContext()
                    .putLong("expectedCount", expected.size());
            context.getStepContext().getStepExecution().getExecutionContext()
                    .putLong("actualCount", actual);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Job quarantineSubsidiesJob(JobRepository repo, Step quarantineSubsidiesStep) {
        return new JobBuilder("quarantineSubsidiesJob", repo).start(quarantineSubsidiesStep).build();
    }

    @Bean
    Step quarantineSubsidiesStep(JobRepository repo, PlatformTransactionManager tx,
                                MongoTemplate mongo) {
        return new StepBuilder("quarantineSubsidiesStep", repo).tasklet((contribution, context) -> {
            long count = mongo.getCollection(V001__SeedSubsidyApplications.COLLECTION)
                    .countDocuments(new Document("demoError", true));
            context.getStepContext().getStepExecution().getExecutionContext()
                    .putLong("quarantineCandidateCount", count);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }
}
