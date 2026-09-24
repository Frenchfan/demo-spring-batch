package com.exportcenter.demo_spring_batch.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobRestartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class BatchJobController {
    private static final Logger log = LoggerFactory.getLogger(BatchJobController.class);
    private final JobOperator jobOperator;
    private final Job repairSubsidiesJob;
    private final Job auditSubsidiesJob;
    private final Job quarantineSubsidiesJob;
    private final JobRepository jobRepository;
    private final Map<String, Job> jobs;

    public BatchJobController(JobOperator jobOperator, Job repairSubsidiesJob,
                              Job auditSubsidiesJob, Job quarantineSubsidiesJob, JobRepository jobRepository,
                              Map<String, Job> jobs) {
        this.jobs = jobs;
        this.jobOperator = jobOperator;
        this.repairSubsidiesJob = repairSubsidiesJob;
        this.auditSubsidiesJob = auditSubsidiesJob;
        this.quarantineSubsidiesJob = quarantineSubsidiesJob;
        this.jobRepository = jobRepository;
    }

    @PostMapping("/api/batch/jobs/repair-subsidies")
    ResponseEntity<Map<String, Object>> repairSubsidies() {
        return start(repairSubsidiesJob,
                new JobParametersBuilder()
                        .addString("dataset", "subsidies-v1")
                        .addLong("runId", Instant.now().toEpochMilli())
                        .toJobParameters());
    }

    @PostMapping("/api/batch/jobs/audit-subsidies")
    ResponseEntity<Map<String, Object>> auditSubsidies() {
        return launch(auditSubsidiesJob, "audit");
    }

    @PostMapping("/api/batch/jobs/quarantine-subsidies")
    ResponseEntity<Map<String, Object>> quarantineSubsidies() {
        return launch(quarantineSubsidiesJob, "quarantine");
    }

    @PostMapping("/api/batch/jobs/{jobName}")
    ResponseEntity<Map<String, Object>> runNamedJob(
            @PathVariable String jobName) {
        Job job = jobs.get(jobName);
        if (job == null) return ResponseEntity.notFound().build();
        return launch(job, jobName);
    }

    private ResponseEntity<Map<String, Object>> launch(Job job, String operation) {
        return start(job,
                new JobParametersBuilder()
                        .addString("dataset", "subsidies-v1")
                        .addString("operation", operation)
                        .addLong("runId", Instant.now().toEpochMilli())
                        .toJobParameters());
    }

    private ResponseEntity<Map<String, Object>> start(Job job, JobParameters parameters) {
        try {
            JobExecution execution = jobOperator.start(job, parameters);
            return ResponseEntity.accepted().body(Map.of(
                "jobName", execution.getJobInstance().getJobName(),
                "executionId", execution.getId(),
                "status", execution.getStatus().name(),
                "exitCode", execution.getExitStatus().getExitCode()));
        } catch (InvalidJobParametersException exception) {
            return launchError(job, 400, "INVALID_PARAMETERS", "Некорректные параметры запуска задания.", exception);
        } catch (JobExecutionAlreadyRunningException exception) {
            return launchError(job, 409, "ALREADY_RUNNING", "Задание с этими параметрами уже выполняется.", exception);
        } catch (JobInstanceAlreadyCompleteException exception) {
            return launchError(job, 409, "ALREADY_COMPLETE", "Задание с этими параметрами уже завершено.", exception);
        } catch (JobRestartException exception) {
            return launchError(job, 409, "RESTART_NOT_ALLOWED", "Задание нельзя перезапустить в текущем состоянии.", exception);
        } catch (RuntimeException exception) {
            return launchError(job, 500, "LAUNCH_FAILED", "Не удалось запустить задание. Подробности в журнале приложения.", exception);
        }
    }

    private ResponseEntity<Map<String, Object>> launchError(
            Job job, int status, String code, String message, Exception exception) {
        if (status >= 500) log.error("Batch job {} launch failed", job.getName(), exception);
        else log.warn("Batch job {} launch rejected: {}", job.getName(), code, exception);
        return ResponseEntity.status(status).body(Map.of(
                "jobName", job.getName(), "code", code, "message", message));
    }

    @GetMapping("/api/batch/executions/{executionId}")
    ResponseEntity<Map<String, Object>> execution(
            @PathVariable long executionId) {
        var execution = jobRepository.getJobExecution(executionId);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "jobName", execution.getJobInstance().getJobName(),
                "executionId", execution.getId(),
                "status", execution.getStatus().name(),
                "exitCode", execution.getExitStatus().getExitCode(),
                "steps", execution.getStepExecutions().stream().map(step -> Map.of(
                        "name", step.getStepName(),
                        "status", step.getStatus().name(),
                        "readCount", step.getReadCount(),
                        "writeCount", step.getWriteCount(),
                        "skipCount", step.getSkipCount())).toList()));
    }

    @PostMapping("/api/batch/executions/{executionId}/restart")
    ResponseEntity<Map<String, Object>> restart(
            @org.springframework.web.bind.annotation.PathVariable long executionId) {
        JobExecution previous = jobRepository.getJobExecution(executionId);
        if (previous == null) return ResponseEntity.notFound().build();
        Job job = jobs.get(previous.getJobInstance().getJobName());
        if (job == null) return ResponseEntity.notFound().build();
        if (previous.getStatus() != org.springframework.batch.core.BatchStatus.FAILED
                && previous.getStatus() != org.springframework.batch.core.BatchStatus.STOPPED) {
            return ResponseEntity.status(409).body(Map.of("jobName", job.getName(),
                    "code", "RESTART_NOT_ALLOWED", "message", "Продолжить можно FAILED или STOPPED выполнение."));
        }
        try {
            JobExecution execution = jobOperator.restart(previous);
            return ResponseEntity.accepted().body(Map.of("jobName", job.getName(),
                    "executionId", execution.getId(), "status", execution.getStatus().name(),
                    "exitCode", execution.getExitStatus().getExitCode()));
        } catch (JobRestartException exception) {
            return launchError(job, 409, "RESTART_NOT_ALLOWED", "Не удалось продолжить выполнение задания.", exception);
        } catch (RuntimeException exception) {
            return launchError(job, 500, "RESTART_FAILED", "Ошибка продолжения задания. Подробности в журнале.", exception);
        }
    }

}
