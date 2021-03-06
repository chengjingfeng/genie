/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.web.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.genie.common.exceptions.GenieException;
import com.netflix.genie.common.internal.dto.JobDirectoryManifest;
import com.netflix.genie.common.internal.services.JobArchiveService;
import com.netflix.genie.common.internal.util.GenieHostInfo;
import com.netflix.genie.web.events.GenieEventBus;
import com.netflix.genie.web.jobs.workflow.WorkflowTask;
import com.netflix.genie.web.properties.DataServiceRetryProperties;
import com.netflix.genie.web.properties.ExponentialBackOffTriggerProperties;
import com.netflix.genie.web.properties.FileCacheProperties;
import com.netflix.genie.web.properties.HealthProperties;
import com.netflix.genie.web.properties.JobsActiveLimitProperties;
import com.netflix.genie.web.properties.JobsCleanupProperties;
import com.netflix.genie.web.properties.JobsForwardingProperties;
import com.netflix.genie.web.properties.JobsLocationsProperties;
import com.netflix.genie.web.properties.JobsMaxProperties;
import com.netflix.genie.web.properties.JobsMemoryProperties;
import com.netflix.genie.web.properties.JobsProperties;
import com.netflix.genie.web.properties.JobsUsersProperties;
import com.netflix.genie.web.services.AgentConnectionPersistenceService;
import com.netflix.genie.web.services.AgentFileStreamService;
import com.netflix.genie.web.services.AgentFilterService;
import com.netflix.genie.web.services.AgentJobService;
import com.netflix.genie.web.services.AgentMetricsService;
import com.netflix.genie.web.services.AgentRoutingService;
import com.netflix.genie.web.services.ApplicationPersistenceService;
import com.netflix.genie.web.services.AttachmentService;
import com.netflix.genie.web.services.ClusterLoadBalancer;
import com.netflix.genie.web.services.ClusterPersistenceService;
import com.netflix.genie.web.services.CommandPersistenceService;
import com.netflix.genie.web.services.FileTransferFactory;
import com.netflix.genie.web.services.JobCoordinatorService;
import com.netflix.genie.web.services.JobDirectoryServerService;
import com.netflix.genie.web.services.JobFileService;
import com.netflix.genie.web.services.JobKillService;
import com.netflix.genie.web.services.JobKillServiceV4;
import com.netflix.genie.web.services.JobPersistenceService;
import com.netflix.genie.web.services.JobSearchService;
import com.netflix.genie.web.services.JobSpecificationService;
import com.netflix.genie.web.services.JobStateService;
import com.netflix.genie.web.services.JobSubmitterService;
import com.netflix.genie.web.services.MailService;
import com.netflix.genie.web.services.impl.AgentJobServiceImpl;
import com.netflix.genie.web.services.impl.AgentMetricsServiceImpl;
import com.netflix.genie.web.services.impl.AgentRoutingServiceImpl;
import com.netflix.genie.web.services.impl.CacheGenieFileTransferService;
import com.netflix.genie.web.services.impl.DiskJobFileServiceImpl;
import com.netflix.genie.web.services.impl.FileSystemAttachmentService;
import com.netflix.genie.web.services.impl.GenieFileTransferService;
import com.netflix.genie.web.services.impl.JobCoordinatorServiceImpl;
import com.netflix.genie.web.services.impl.JobDirectoryServerServiceImpl;
import com.netflix.genie.web.services.impl.JobKillServiceImpl;
import com.netflix.genie.web.services.impl.JobKillServiceV3;
import com.netflix.genie.web.services.impl.JobSpecificationServiceImpl;
import com.netflix.genie.web.services.impl.LocalFileTransferImpl;
import com.netflix.genie.web.services.impl.LocalJobRunner;
import com.netflix.genie.web.tasks.job.JobCompletionService;
import com.netflix.genie.web.util.InspectionReport;
import com.netflix.genie.web.util.ProcessChecker;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.exec.Executor;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ServiceLocatorFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.retry.support.RetryTemplate;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for all the services.
 *
 * @author amsharma
 * @since 3.0.0
 */
@Configuration
@EnableConfigurationProperties(
    {
        DataServiceRetryProperties.class,
        FileCacheProperties.class,
        HealthProperties.class,
        JobsCleanupProperties.class,
        JobsForwardingProperties.class,
        JobsLocationsProperties.class,
        JobsMaxProperties.class,
        JobsMemoryProperties.class,
        JobsUsersProperties.class,
        ExponentialBackOffTriggerProperties.class,
        JobsActiveLimitProperties.class,
    }
)
@AutoConfigureAfter(
    {
        // TODO: Likely there are more that should be here
        GenieJobWorkflowAutoConfiguration.class
    }
)
public class GenieServicesAutoConfiguration {

    /**
     * Collection of properties related to job execution.
     *
     * @param cleanup                cleanup properties
     * @param forwarding             forwarding properties
     * @param locations              locations properties
     * @param max                    max properties
     * @param memory                 memory properties
     * @param users                  users properties
     * @param completionCheckBackOff completion back-off properties
     * @param activeLimit            active limit properties
     * @return a {@code JobsProperties} instance
     */
    @Bean
    public JobsProperties jobsProperties(
        final JobsCleanupProperties cleanup,
        final JobsForwardingProperties forwarding,
        final JobsLocationsProperties locations,
        final JobsMaxProperties max,
        final JobsMemoryProperties memory,
        final JobsUsersProperties users,
        final ExponentialBackOffTriggerProperties completionCheckBackOff,
        final JobsActiveLimitProperties activeLimit
    ) {
        return new JobsProperties(
            cleanup,
            forwarding,
            locations,
            max,
            memory,
            users,
            completionCheckBackOff,
            activeLimit
        );
    }

    /**
     * Get an local implementation of the JobKillService.
     *
     * @param genieHostInfo         Information about the host the Genie process is running on
     * @param jobSearchService      The job search service to use to locate job information.
     * @param executor              The executor to use to run system processes.
     * @param jobsProperties        The jobs properties to use
     * @param genieEventBus         The application event bus to use to publish system wide events
     * @param genieWorkingDir       Working directory for genie where it creates jobs directories.
     * @param objectMapper          The Jackson ObjectMapper used to serialize from/to JSON
     * @param processCheckerFactory The process checker factory
     * @return A job kill service instance.
     */
    @Bean
    @ConditionalOnMissingBean(JobKillServiceV3.class)
    public JobKillServiceV3 jobKillServiceV3(
        final GenieHostInfo genieHostInfo,
        final JobSearchService jobSearchService,
        final Executor executor,
        final JobsProperties jobsProperties,
        final GenieEventBus genieEventBus,
        @Qualifier("jobsDir") final Resource genieWorkingDir,
        final ObjectMapper objectMapper,
        final ProcessChecker.Factory processCheckerFactory
    ) {
        return new JobKillServiceV3(
            genieHostInfo.getHostname(),
            jobSearchService,
            executor,
            jobsProperties.getUsers().isRunAsUserEnabled(),
            genieEventBus,
            genieWorkingDir,
            objectMapper,
            processCheckerFactory
        );
    }

    /**
     * Get an local implementation of the JobKillService.
     *
     * @param jobKillServiceV3      Service to kill V3 jobs.
     * @param jobKillServiceV4      Service to kill V4 jobs.
     * @param jobPersistenceService Job persistence service
     * @return A job kill service instance.
     */
    @Bean
    @ConditionalOnMissingBean(JobKillService.class)
    public JobKillService jobKillService(
        final JobKillServiceV3 jobKillServiceV3,
        final JobKillServiceV4 jobKillServiceV4,
        final JobPersistenceService jobPersistenceService
    ) {
        return new JobKillServiceImpl(
            jobKillServiceV3,
            jobKillServiceV4,
            jobPersistenceService
        );
    }

    /**
     * Get a fallback implementation of {@link JobKillServiceV4} in case gRPC is disabled.
     *
     * @return a placeholder V4 job kill service for tests.
     */
    @Bean
    @ConditionalOnMissingBean(JobKillServiceV4.class)
    public JobKillServiceV4 fallbackJobKillServiceV4() {
        return (jobId, reason) -> {
            throw new NotImplementedException("Not suppored when using fallback kill service");
        };
    }

    /**
     * Get an instance of the Genie File Transfer service.
     *
     * @param fileTransferFactory file transfer implementation factory
     * @return A singleton for GenieFileTransferService
     * @throws GenieException If there is any problem
     */
    @Bean
    @ConditionalOnMissingBean(name = "genieFileTransferService")
    public GenieFileTransferService genieFileTransferService(
        final FileTransferFactory fileTransferFactory
    ) throws GenieException {
        return new GenieFileTransferService(fileTransferFactory);
    }

    /**
     * Get an instance of the Cache Genie File Transfer service.
     *
     * @param fileTransferFactory file transfer implementation factory
     * @param fileCacheProperties Properties related to the file cache that can be set by the admin
     * @param localFileTransfer   local file transfer service
     * @param registry            Registry
     * @return A singleton for GenieFileTransferService
     * @throws GenieException If there is any problem
     */
    @Bean
    @ConditionalOnMissingBean(name = "cacheGenieFileTransferService")
    public GenieFileTransferService cacheGenieFileTransferService(
        final FileTransferFactory fileTransferFactory,
        final FileCacheProperties fileCacheProperties,
        final LocalFileTransferImpl localFileTransfer,
        final MeterRegistry registry
    ) throws GenieException {
        return new CacheGenieFileTransferService(
            fileTransferFactory,
            fileCacheProperties.getLocation(),
            localFileTransfer,
            registry
        );
    }

    /**
     * Get a implementation of the JobSubmitterService that runs jobs locally.
     *
     * @param jobPersistenceService Implementation of the job persistence service.
     * @param genieEventBus         The genie event bus implementation to use
     * @param workflowTasks         List of all the workflow tasks to be executed.
     * @param genieWorkingDir       Working directory for genie where it creates jobs directories.
     * @param registry              The metrics registry to use
     * @return An instance of the JobSubmitterService.
     */
    @Bean
    @ConditionalOnMissingBean(JobSubmitterService.class)
    public JobSubmitterService jobSubmitterService(
        final JobPersistenceService jobPersistenceService,
        final GenieEventBus genieEventBus,
        final List<WorkflowTask> workflowTasks,
        @Qualifier("jobsDir") final Resource genieWorkingDir,
        final MeterRegistry registry
    ) {
        return new LocalJobRunner(
            jobPersistenceService,
            genieEventBus,
            workflowTasks,
            genieWorkingDir,
            registry
        );
    }

    /**
     * Get an instance of the JobCoordinatorService.
     *
     * @param jobPersistenceService         implementation of job persistence service interface
     * @param jobKillService                The job kill service to use
     * @param jobStateService               The running job metrics service to use
     * @param jobSearchService              Implementation of job search service interface
     * @param jobsProperties                The jobs properties to use
     * @param applicationPersistenceService Implementation of application service interface
     * @param clusterPersistenceService     Implementation of cluster service interface
     * @param commandPersistenceService     Implementation of command service interface
     * @param specificationService          The job specification service to use
     * @param registry                      The metrics registry to use
     * @param genieHostInfo                 Information about the host the Genie process is running on
     * @return An instance of the JobCoordinatorService.
     */
    @Bean
    @ConditionalOnMissingBean(JobCoordinatorService.class)
    public JobCoordinatorService jobCoordinatorService(
        final JobPersistenceService jobPersistenceService,
        final JobKillService jobKillService,
        @Qualifier("jobMonitoringCoordinator") final JobStateService jobStateService,
        final JobSearchService jobSearchService,
        final JobsProperties jobsProperties,
        final ApplicationPersistenceService applicationPersistenceService,
        final ClusterPersistenceService clusterPersistenceService,
        final CommandPersistenceService commandPersistenceService,
        final JobSpecificationService specificationService,
        final MeterRegistry registry,
        final GenieHostInfo genieHostInfo
    ) {
        return new JobCoordinatorServiceImpl(
            jobPersistenceService,
            jobKillService,
            jobStateService,
            jobsProperties,
            applicationPersistenceService,
            jobSearchService,
            clusterPersistenceService,
            commandPersistenceService,
            specificationService,
            registry,
            genieHostInfo.getHostname()
        );
    }

    /**
     * The attachment service to use.
     *
     * @param jobsProperties All properties related to jobs
     * @return The attachment service to use
     */
    @Bean
    @ConditionalOnMissingBean(AttachmentService.class)
    public AttachmentService attachmentService(final JobsProperties jobsProperties) {
        return new FileSystemAttachmentService(jobsProperties.getLocations().getAttachments());
    }

    /**
     * FileTransfer factory.
     *
     * @return FileTransfer factory
     */
    @Bean
    @ConditionalOnMissingBean(name = "fileTransferFactory", value = ServiceLocatorFactoryBean.class)
    public ServiceLocatorFactoryBean fileTransferFactory() {
        final ServiceLocatorFactoryBean factoryBean = new ServiceLocatorFactoryBean();
        factoryBean.setServiceLocatorInterface(FileTransferFactory.class);
        return factoryBean;
    }

    /**
     * Get a {@link AgentJobService} instance if there isn't already one.
     *
     * @param jobPersistenceService   The persistence service to use
     * @param jobSpecificationService The specification service to use
     * @param agentFilterService      The agent filter service to use
     * @param meterRegistry           The metrics registry to use
     * @return An {@link AgentJobServiceImpl} instance.
     */
    @Bean
    @ConditionalOnMissingBean(AgentJobService.class)
    public AgentJobService agentJobService(
        final JobPersistenceService jobPersistenceService,
        final JobSpecificationService jobSpecificationService,
        final AgentFilterService agentFilterService,
        final MeterRegistry meterRegistry
    ) {
        return new AgentJobServiceImpl(
            jobPersistenceService,
            jobSpecificationService,
            agentFilterService,
            meterRegistry
        );
    }

    /**
     * Get a {@link JobFileService} implementation if one is required.
     *
     * @param jobsDir The job directory resource
     * @return A {@link DiskJobFileServiceImpl} instance
     * @throws IOException When the job directory can't be created or isn't a directory
     */
    @Bean
    @ConditionalOnMissingBean(JobFileService.class)
    public JobFileService jobFileService(@Qualifier("jobsDir") final Resource jobsDir) throws IOException {
        return new DiskJobFileServiceImpl(jobsDir);
    }

    /**
     * Get an implementation of {@link JobSpecificationService} if one hasn't already been defined.
     *
     * @param applicationPersistenceService The service to use to manipulate applications
     * @param clusterPersistenceService     The service to use to manipulate clusters
     * @param commandPersistenceService     The service to use to manipulate commands
     * @param clusterLoadBalancers          The load balancer implementations to use
     * @param registry                      The metrics repository to use
     * @param jobsProperties                The properties for running a job set by the user
     * @return A {@link JobSpecificationServiceImpl} instance
     */
    @Bean
    @ConditionalOnMissingBean(JobSpecificationService.class)
    public JobSpecificationService jobSpecificationService(
        final ApplicationPersistenceService applicationPersistenceService,
        final ClusterPersistenceService clusterPersistenceService,
        final CommandPersistenceService commandPersistenceService,
        @NotEmpty final List<ClusterLoadBalancer> clusterLoadBalancers,
        final MeterRegistry registry,
        final JobsProperties jobsProperties
    ) {
        return new JobSpecificationServiceImpl(
            applicationPersistenceService,
            clusterPersistenceService,
            commandPersistenceService,
            clusterLoadBalancers,
            registry,
            jobsProperties
        );
    }

    /**
     * Get an implementation of {@link AgentRoutingService} if one hasn't already been defined.
     *
     * @param agentConnectionPersistenceService The persistence service to use for agent connections
     * @param genieHostInfo                     The local genie host information
     * @return A {@link AgentRoutingServiceImpl} instance
     */
    @Bean
    @ConditionalOnMissingBean(AgentRoutingService.class)
    public AgentRoutingService agentRoutingService(
        final AgentConnectionPersistenceService agentConnectionPersistenceService,
        final GenieHostInfo genieHostInfo
    ) {
        return new AgentRoutingServiceImpl(
            agentConnectionPersistenceService,
            genieHostInfo
        );
    }

    /**
     * Get an implementation of {@link JobCompletionService} if one hasn't already been defined.
     *
     * @param jobPersistenceService The job persistence service to use
     * @param jobSearchService      The job search service to use
     * @param jobArchiveService     The {@link JobArchiveService} implementation to use
     * @param genieWorkingDir       Working directory for genie where it creates jobs directories.
     * @param mailService           The mail service
     * @param registry              Registry
     * @param jobsProperties        The jobs properties to use
     * @param retryTemplate         The retry template
     * @return an instance of {@link JobCompletionService}
     * @throws GenieException if the bean fails during construction
     */
    @Bean
    @ConditionalOnMissingBean(JobCompletionService.class)
    public JobCompletionService jobCompletionService(
        final JobPersistenceService jobPersistenceService,
        final JobSearchService jobSearchService,
        final JobArchiveService jobArchiveService,
        @Qualifier("jobsDir") final Resource genieWorkingDir,
        final MailService mailService,
        final MeterRegistry registry,
        final JobsProperties jobsProperties,
        @Qualifier("genieRetryTemplate") final RetryTemplate retryTemplate
    ) throws GenieException {
        return new JobCompletionService(
            jobPersistenceService,
            jobSearchService,
            jobArchiveService,
            genieWorkingDir,
            mailService,
            registry,
            jobsProperties,
            retryTemplate
        );
    }

    /**
     * Get a NOOP/fallback {@link AgentFilterService} instance if there isn't already one.
     *
     * @return An {@link AgentFilterService} instance.
     * @see GenieAgentFilterAutoConfiguration
     */
    @Bean
    @ConditionalOnMissingBean(AgentFilterService.class)
    public AgentFilterService agentFilterService() {
        return agentClientMetadata -> new InspectionReport(
            InspectionReport.Decision.ACCEPT,
            "Accepted by default"
        );
    }

    /**
     * Provide the default implementation of {@link JobDirectoryServerService} for serving job directory resources.
     *
     * @param resourceLoader         The application resource loader used to get references to resources
     * @param jobPersistenceService  The job persistence service used to get information about a job
     * @param jobFileService         The service responsible for managing the job working directory on disk for V3 Jobs
     * @param agentFileStreamService The service to request a file from an agent running a job
     * @param meterRegistry          The meter registry used to keep track of metrics
     * @return An instance of {@link JobDirectoryServerServiceImpl}
     */
    @Bean
    @ConditionalOnMissingBean(JobDirectoryServerService.class)
    public JobDirectoryServerService jobDirectoryServerService(
        final ResourceLoader resourceLoader,
        final JobPersistenceService jobPersistenceService,
        final JobFileService jobFileService,
        final AgentFileStreamService agentFileStreamService,
        final MeterRegistry meterRegistry
    ) {
        return new JobDirectoryServerServiceImpl(
            resourceLoader,
            jobPersistenceService,
            jobFileService,
            agentFileStreamService,
            meterRegistry
        );
    }

    /**
     * Get a fallback implementation of {@link AgentFileStreamService} in case gRPC is disabled.
     *
     * @return a placeholder agent file stream service for tests.
     */
    @Bean
    @ConditionalOnMissingBean(AgentFileStreamService.class)
    public AgentFileStreamService fallbackAgentFileStreamService() {
        return new AgentFileStreamService() {
            @Override
            public Optional<AgentFileResource> getResource(
                @NotBlank final String jobId,
                final Path relativePath,
                final URI uri
            ) {
                throw new NotImplementedException("Not supported when using fallback service");
            }

            @Override
            public Optional<JobDirectoryManifest> getManifest(final String jobId) {
                throw new NotImplementedException("Not supported when using fallback service");
            }
        };
    }

    /**
     * Provide an implementation of {@link AgentMetricsService} if one hasn't been provided.
     *
     * @param genieHostInfo                     The Genie host information
     * @param agentConnectionPersistenceService Implementation of {@link AgentConnectionPersistenceService} to get
     *                                          information about running agents in the ecosystem
     * @param registry                          The metrics repository
     * @return An instance of {@link AgentMetricsServiceImpl}
     */
    @Bean
    @ConditionalOnMissingBean(AgentMetricsService.class)
    public AgentMetricsServiceImpl agentMetricsService(
        final GenieHostInfo genieHostInfo,
        final AgentConnectionPersistenceService agentConnectionPersistenceService,
        final MeterRegistry registry
    ) {
        return new AgentMetricsServiceImpl(genieHostInfo, agentConnectionPersistenceService, registry);
    }
}
