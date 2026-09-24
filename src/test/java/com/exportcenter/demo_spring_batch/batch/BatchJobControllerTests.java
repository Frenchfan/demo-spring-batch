package com.exportcenter.demo_spring_batch.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.*;
import org.springframework.batch.core.repository.JobRepository;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchJobControllerTests {
    @Test
    void restartPreservesExecutionAndRejectsCompletedJobs() throws Exception {
        var operator = mock(JobOperator.class);
        var repository = mock(JobRepository.class);
        var job = mock(Job.class);
        when(job.getName()).thenReturn("repair");
        var previous = mock(org.springframework.batch.core.job.JobExecution.class);
        var instance = mock(org.springframework.batch.core.job.JobInstance.class);
        when(previous.getJobInstance()).thenReturn(instance);
        when(instance.getJobName()).thenReturn("repair");
        when(repository.getJobExecution(1L)).thenReturn(previous);
        var controller = new BatchJobController(operator, job, job, job, repository, Map.of("repair", job));
        assertThat(controller.restart(99L).getStatusCode().value()).isEqualTo(404);
        when(previous.getStatus()).thenReturn(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(controller.restart(1L).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(operator);
        when(previous.getStatus()).thenReturn(org.springframework.batch.core.BatchStatus.FAILED);
        when(operator.restart(previous)).thenThrow(new JobRestartException("internal"));
        assertThat(controller.restart(1L).getStatusCode().value()).isEqualTo(409);
        verify(operator).restart(previous);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new InvalidJobParametersException("internal"), 400, "INVALID_PARAMETERS"),
                Arguments.of(new JobExecutionAlreadyRunningException("internal"), 409, "ALREADY_RUNNING"),
                Arguments.of(new JobInstanceAlreadyCompleteException("internal"), 409, "ALREADY_COMPLETE"),
                Arguments.of(new JobRestartException("internal"), 409, "RESTART_NOT_ALLOWED"),
                Arguments.of(new IllegalStateException("internal secret"), 500, "LAUNCH_FAILED"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void translatesFailuresForEveryLaunchEndpoint(Exception failure, int status, String code) throws Exception {
        var operator = mock(JobOperator.class);
        var job = mock(Job.class);
        when(job.getName()).thenReturn("repair");
        when(operator.start(eq(job), any(JobParameters.class))).thenThrow(failure);
        var controller = new BatchJobController(operator, job, job, job,
                mock(JobRepository.class), Map.of("repair", job));
        var responses = java.util.List.of(controller.repairSubsidies(), controller.auditSubsidies(),
                controller.quarantineSubsidies(), controller.runNamedJob("repair"));
        for (var response : responses) {
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull()
                    .containsEntry("code", code)
                    .containsEntry("jobName", "repair");
            assertThat(body).extractingByKey("message").asString().doesNotContain("internal");
        }
    }

    @Test
    void unknownJobDoesNotInvokeOperator() {
        var operator = mock(JobOperator.class);
        var job = mock(Job.class);
        var controller = new BatchJobController(operator, job, job, job,
                mock(JobRepository.class), Map.of());
        assertThat(controller.runNamedJob("missing").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(operator);
    }
}
