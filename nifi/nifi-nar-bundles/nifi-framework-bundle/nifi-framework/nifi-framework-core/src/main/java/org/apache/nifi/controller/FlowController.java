/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.controller;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.admin.service.UserService;
import org.apache.nifi.annotation.lifecycle.OnAdded;
import org.apache.nifi.annotation.lifecycle.OnRemoved;
import org.apache.nifi.cluster.BulletinsPayload;
import org.apache.nifi.cluster.HeartbeatPayload;
import org.apache.nifi.cluster.protocol.DataFlow;
import org.apache.nifi.cluster.protocol.Heartbeat;
import org.apache.nifi.cluster.protocol.NodeBulletins;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.cluster.protocol.NodeProtocolSender;
import org.apache.nifi.cluster.protocol.UnknownServiceAddressException;
import org.apache.nifi.cluster.protocol.message.HeartbeatMessage;
import org.apache.nifi.cluster.protocol.message.NodeBulletinsMessage;
import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.Funnel;
import org.apache.nifi.connectable.LocalPort;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.connectable.Position;
import org.apache.nifi.connectable.Size;
import org.apache.nifi.connectable.StandardConnection;
import org.apache.nifi.controller.exception.CommunicationsException;
import org.apache.nifi.controller.exception.ProcessorInstantiationException;
import org.apache.nifi.controller.exception.ProcessorLifeCycleException;
import org.apache.nifi.controller.label.Label;
import org.apache.nifi.controller.label.StandardLabel;
import org.apache.nifi.controller.reporting.ReportingTaskInstantiationException;
import org.apache.nifi.controller.reporting.StandardReportingTaskNode;
import org.apache.nifi.controller.repository.ContentRepository;
import org.apache.nifi.controller.repository.CounterRepository;
import org.apache.nifi.controller.repository.FlowFileEvent;
import org.apache.nifi.controller.repository.FlowFileEventRepository;
import org.apache.nifi.controller.repository.FlowFileRecord;
import org.apache.nifi.controller.repository.FlowFileRepository;
import org.apache.nifi.controller.repository.FlowFileSwapManager;
import org.apache.nifi.controller.repository.QueueProvider;
import org.apache.nifi.controller.repository.RepositoryRecord;
import org.apache.nifi.controller.repository.RepositoryStatusReport;
import org.apache.nifi.controller.repository.StandardCounterRepository;
import org.apache.nifi.controller.repository.StandardFlowFileRecord;
import org.apache.nifi.controller.repository.StandardRepositoryRecord;
import org.apache.nifi.controller.repository.claim.ContentClaim;
import org.apache.nifi.controller.repository.claim.ContentClaimManager;
import org.apache.nifi.controller.repository.claim.ContentDirection;
import org.apache.nifi.controller.repository.claim.StandardContentClaimManager;
import org.apache.nifi.controller.repository.io.LimitedInputStream;
import org.apache.nifi.controller.scheduling.EventDrivenSchedulingAgent;
import org.apache.nifi.controller.scheduling.ProcessContextFactory;
import org.apache.nifi.controller.scheduling.QuartzSchedulingAgent;
import org.apache.nifi.controller.scheduling.StandardProcessScheduler;
import org.apache.nifi.controller.scheduling.TimerDrivenSchedulingAgent;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.service.ControllerServiceReference;
import org.apache.nifi.controller.service.StandardControllerServiceProvider;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.PortStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.controller.status.RemoteProcessGroupStatus;
import org.apache.nifi.controller.status.RunStatus;
import org.apache.nifi.controller.status.TransmissionStatus;
import org.apache.nifi.controller.status.history.ComponentStatusRepository;
import org.apache.nifi.controller.status.history.StatusHistoryUtil;
import org.apache.nifi.controller.tasks.ExpireFlowFiles;
import org.apache.nifi.diagnostics.SystemDiagnostics;
import org.apache.nifi.diagnostics.SystemDiagnosticsFactory;
import org.apache.nifi.encrypt.StringEncryptor;
import org.apache.nifi.engine.FlowEngine;
import org.apache.nifi.events.BulletinFactory;
import org.apache.nifi.events.EventReporter;
import org.apache.nifi.events.NodeBulletinProcessingStrategy;
import org.apache.nifi.events.VolatileBulletinRepository;
import org.apache.nifi.flowfile.FlowFilePrioritizer;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.framework.security.util.SslContextFactory;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.RemoteProcessGroup;
import org.apache.nifi.groups.RemoteProcessGroupPortDescriptor;
import org.apache.nifi.groups.StandardProcessGroup;
import org.apache.nifi.logging.LogLevel;
import org.apache.nifi.logging.LogRepository;
import org.apache.nifi.logging.LogRepositoryFactory;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.logging.ProcessorLogObserver;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.nar.NarThreadContextClassLoader;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.QueueSize;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.SimpleProcessLogger;
import org.apache.nifi.processor.StandardProcessorInitializationContext;
import org.apache.nifi.processor.StandardValidationContextFactory;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.provenance.StandardProvenanceEventRecord;
import org.apache.nifi.remote.RemoteGroupPort;
import org.apache.nifi.remote.RemoteResourceManager;
import org.apache.nifi.remote.RemoteSiteListener;
import org.apache.nifi.remote.RootGroupPort;
import org.apache.nifi.remote.SocketRemoteSiteListener;
import org.apache.nifi.remote.StandardRemoteProcessGroup;
import org.apache.nifi.remote.StandardRemoteProcessGroupPortDescriptor;
import org.apache.nifi.remote.StandardRootGroupPort;
import org.apache.nifi.remote.TransferDirection;
import org.apache.nifi.remote.protocol.socket.SocketFlowFileServerProtocol;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.reporting.EventAccess;
import org.apache.nifi.reporting.ReportingTask;
import org.apache.nifi.reporting.Severity;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.util.ReflectionUtils;
import org.apache.nifi.web.api.dto.ConnectableDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.FlowSnippetDTO;
import org.apache.nifi.web.api.dto.FunnelDTO;
import org.apache.nifi.web.api.dto.LabelDTO;
import org.apache.nifi.web.api.dto.PortDTO;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessorConfigDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.RelationshipDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupContentsDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupPortDTO;
import org.apache.nifi.web.api.dto.TemplateDTO;
import org.apache.nifi.web.api.dto.status.StatusHistoryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.ClientHandlerException;

public class FlowController implements EventAccess, ControllerServiceProvider, Heartbeater, QueueProvider {

    // default repository implementations
    public static final String DEFAULT_FLOWFILE_REPO_IMPLEMENTATION = "org.apache.nifi.controller.repository.WriteAheadFlowFileRepository";
    public static final String DEFAULT_CONTENT_REPO_IMPLEMENTATION = "org.apache.nifi.controller.repository.FileSystemRepository";
    public static final String DEFAULT_PROVENANCE_REPO_IMPLEMENTATION = "org.apache.nifi.provenance.VolatileProvenanceRepository";
    public static final String DEFAULT_SWAP_MANAGER_IMPLEMENTATION = "org.apache.nifi.controller.FileSystemSwapManager";
    public static final String DEFAULT_COMPONENT_STATUS_REPO_IMPLEMENTATION = "org.apache.nifi.controller.status.history.VolatileComponentStatusRepository";

    public static final String SCHEDULE_MINIMUM_NANOSECONDS = "flowcontroller.minimum.nanoseconds";
    public static final String GRACEFUL_SHUTDOWN_PERIOD = "nifi.flowcontroller.graceful.shutdown.seconds";
    public static final long DEFAULT_GRACEFUL_SHUTDOWN_SECONDS = 10;
    public static final int METRICS_RESERVOIR_SIZE = 288;   // 1 day worth of 5-minute captures

    public static final String ROOT_GROUP_ID_ALIAS = "root";
    public static final String DEFAULT_ROOT_GROUP_NAME = "NiFi Flow";

    private final AtomicInteger maxTimerDrivenThreads;
    private final AtomicInteger maxEventDrivenThreads;
    private final AtomicReference<FlowEngine> timerDrivenEngineRef;
    private final AtomicReference<FlowEngine> eventDrivenEngineRef;

    private final ContentRepository contentRepository;
    private final FlowFileRepository flowFileRepository;
    private final FlowFileEventRepository flowFileEventRepository;
    private final ProvenanceEventRepository provenanceEventRepository;
    private final VolatileBulletinRepository bulletinRepository;
    private final StandardProcessScheduler processScheduler;
    private final TemplateManager templateManager;
    private final SnippetManager snippetManager;
    private final long gracefulShutdownSeconds;
    private final ExtensionManager extensionManager;
    private final NiFiProperties properties;
    private final SSLContext sslContext;
    private final RemoteSiteListener externalSiteListener;
    private final AtomicReference<CounterRepository> counterRepositoryRef;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final ControllerServiceProvider controllerServiceProvider;
    private final UserService userService;
    private final EventDrivenWorkerQueue eventDrivenWorkerQueue;
    private final ComponentStatusRepository componentStatusRepository;
    private final long systemStartTime = System.currentTimeMillis();    // time at which the node was started
    private final ConcurrentMap<String, ReportingTaskNode> reportingTasks = new ConcurrentHashMap<>();

    // The Heartbeat Bean is used to provide an Atomic Reference to data that is used in heartbeats that may
    // change while the instance is running. We do this because we want to generate heartbeats even if we
    // are unable to obtain a read lock on the entire FlowController.
    private final AtomicReference<HeartbeatBean> heartbeatBeanRef = new AtomicReference<>();
    private final AtomicBoolean heartbeatsSuspended = new AtomicBoolean(false);

    private final Integer remoteInputSocketPort;
    private final Boolean isSiteToSiteSecure;
    private Integer clusterManagerRemoteSitePort = null;
    private Boolean clusterManagerRemoteSiteCommsSecure = null;

    private ProcessGroup rootGroup;
    private final List<Connectable> startConnectablesAfterInitialization;
    private final List<RemoteGroupPort> startRemoteGroupPortsAfterInitialization;

    /**
     * true if controller is configured to operate in a clustered environment
     */
    private final boolean configuredForClustering;

    /**
     * the time to wait between heartbeats
     */
    private final int heartbeatDelaySeconds;

    /**
     * The sensitive property string encryptor *
     */
    private final StringEncryptor encryptor;

    /**
     * cluster protocol sender
     */
    private final NodeProtocolSender protocolSender;

    private final ScheduledExecutorService clusterTaskExecutor = new FlowEngine(3, "Clustering Tasks");
    private final ContentClaimManager contentClaimManager = new StandardContentClaimManager();

    // guarded by rwLock
    /**
     * timer to periodically send heartbeats to the cluster
     */
    private ScheduledFuture<?> bulletinFuture;
    private ScheduledFuture<?> heartbeatGeneratorFuture;
    private ScheduledFuture<?> heartbeatSenderFuture;

    // guarded by FlowController lock
    /**
     * timer task to generate heartbeats
     */
    private final AtomicReference<HeartbeatMessageGeneratorTask> heartbeatMessageGeneratorTaskRef = new AtomicReference<>(null);

    private AtomicReference<NodeBulletinProcessingStrategy> nodeBulletinSubscriber;

    // guarded by rwLock
    /**
     * the node identifier;
     */
    private NodeIdentifier nodeId;

    // guarded by rwLock
    /**
     * true if controller is connected or trying to connect to the cluster
     */
    private boolean clustered;
    private String clusterManagerDN;

    // guarded by rwLock
    /**
     * true if controller is the primary of the cluster
     */
    private boolean primary;

    // guarded by rwLock
    /**
     * true if connected to a cluster
     */
    private boolean connected;

    // guarded by rwLock
    private String instanceId;

    private volatile boolean shutdown = false;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private FlowFileSwapManager flowFileSwapManager;    // guarded by read/write lock

    private static final Logger LOG = LoggerFactory.getLogger(FlowController.class);
    private static final Logger heartbeatLogger = LoggerFactory.getLogger("org.apache.nifi.cluster.heartbeat");

    public static FlowController createStandaloneInstance(
            final FlowFileEventRepository flowFileEventRepo,
            final NiFiProperties properties,
            final UserService userService,
            final StringEncryptor encryptor) {
        return new FlowController(
                flowFileEventRepo,
                properties,
                userService,
                encryptor,
                /* configuredForClustering */ false,
                /* NodeProtocolSender */ null);
    }

    public static FlowController createClusteredInstance(
            final FlowFileEventRepository flowFileEventRepo,
            final NiFiProperties properties,
            final UserService userService,
            final StringEncryptor encryptor,
            final NodeProtocolSender protocolSender) {
        final FlowController flowController = new FlowController(
                flowFileEventRepo,
                properties,
                userService,
                encryptor,
                /* configuredForClustering */ true,
                /* NodeProtocolSender */ protocolSender);

        flowController.setClusterManagerRemoteSiteInfo(properties.getRemoteInputPort(), properties.isSiteToSiteSecure());

        return flowController;
    }

    private FlowController(
            final FlowFileEventRepository flowFileEventRepo,
            final NiFiProperties properties,
            final UserService userService,
            final StringEncryptor encryptor,
            final boolean configuredForClustering,
            final NodeProtocolSender protocolSender) {

        maxTimerDrivenThreads = new AtomicInteger(10);
        maxEventDrivenThreads = new AtomicInteger(5);

        this.encryptor = encryptor;
        this.properties = properties;
        sslContext = SslContextFactory.createSslContext(properties, false);
        extensionManager = new ExtensionManager();
        controllerServiceProvider = new StandardControllerServiceProvider();

        timerDrivenEngineRef = new AtomicReference<>(new FlowEngine(maxTimerDrivenThreads.get(), "Timer-Driven Process"));
        eventDrivenEngineRef = new AtomicReference<>(new FlowEngine(maxEventDrivenThreads.get(), "Event-Driven Process"));

        final FlowFileRepository flowFileRepo = createFlowFileRepository(properties, contentClaimManager);
        flowFileRepository = flowFileRepo;
        flowFileEventRepository = flowFileEventRepo;
        counterRepositoryRef = new AtomicReference<CounterRepository>(new StandardCounterRepository());

        bulletinRepository = new VolatileBulletinRepository();
        nodeBulletinSubscriber = new AtomicReference<>();

        try {
            this.provenanceEventRepository = createProvenanceRepository(properties);
            this.provenanceEventRepository.initialize(createEventReporter(bulletinRepository));

            this.contentRepository = createContentRepository(properties);
        } catch (final Exception e) {
            throw new RuntimeException("Unable to create Provenance Repository", e);
        }

        processScheduler = new StandardProcessScheduler(this, this, encryptor);
        eventDrivenWorkerQueue = new EventDrivenWorkerQueue(false, false, processScheduler);

        final ProcessContextFactory contextFactory = new ProcessContextFactory(contentRepository, flowFileRepository, flowFileEventRepository, counterRepositoryRef.get(), provenanceEventRepository);
        processScheduler.setSchedulingAgent(SchedulingStrategy.EVENT_DRIVEN, new EventDrivenSchedulingAgent(
                eventDrivenEngineRef.get(), this, eventDrivenWorkerQueue, contextFactory, maxEventDrivenThreads.get(), encryptor));

        final QuartzSchedulingAgent quartzSchedulingAgent = new QuartzSchedulingAgent(this, timerDrivenEngineRef.get(), contextFactory, encryptor);
        final TimerDrivenSchedulingAgent timerDrivenAgent = new TimerDrivenSchedulingAgent(this, timerDrivenEngineRef.get(), contextFactory, encryptor);
        processScheduler.setSchedulingAgent(SchedulingStrategy.TIMER_DRIVEN, timerDrivenAgent);
        processScheduler.setSchedulingAgent(SchedulingStrategy.PRIMARY_NODE_ONLY, timerDrivenAgent);
        processScheduler.setSchedulingAgent(SchedulingStrategy.CRON_DRIVEN, quartzSchedulingAgent);
        processScheduler.scheduleFrameworkTask(new ExpireFlowFiles(this, contextFactory), "Expire FlowFiles", 30L, 30L, TimeUnit.SECONDS);

        startConnectablesAfterInitialization = new ArrayList<>();
        startRemoteGroupPortsAfterInitialization = new ArrayList<>();
        this.userService = userService;

        final String gracefulShutdownSecondsVal = properties.getProperty(GRACEFUL_SHUTDOWN_PERIOD);
        long shutdownSecs;
        try {
            shutdownSecs = Long.parseLong(gracefulShutdownSecondsVal);
            if (shutdownSecs < 1) {
                shutdownSecs = DEFAULT_GRACEFUL_SHUTDOWN_SECONDS;
            }
        } catch (final NumberFormatException nfe) {
            shutdownSecs = DEFAULT_GRACEFUL_SHUTDOWN_SECONDS;
        }
        gracefulShutdownSeconds = shutdownSecs;

        remoteInputSocketPort = properties.getRemoteInputPort();
        isSiteToSiteSecure = properties.isSiteToSiteSecure();

        if (isSiteToSiteSecure && sslContext == null && remoteInputSocketPort != null) {
            throw new IllegalStateException("NiFi Configured to allow Secure Site-to-Site communications but the Keystore/Truststore properties are not configured");
        }

        this.configuredForClustering = configuredForClustering;
        this.heartbeatDelaySeconds = (int) FormatUtils.getTimeDuration(properties.getNodeHeartbeatInterval(), TimeUnit.SECONDS);
        this.protocolSender = protocolSender;
        try {
            this.templateManager = new TemplateManager(properties.getTemplateDirectory());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.snippetManager = new SnippetManager();

        rootGroup = new StandardProcessGroup(UUID.randomUUID().toString(), this, processScheduler, properties, encryptor);
        rootGroup.setName(DEFAULT_ROOT_GROUP_NAME);
        instanceId = UUID.randomUUID().toString();

        if (remoteInputSocketPort == null){
            LOG.info("Not enabling Site-to-Site functionality because nifi.remote.input.socket.port is not set");
            externalSiteListener = null;
        } else if (isSiteToSiteSecure && sslContext == null) {
            LOG.error("Unable to create Secure Site-to-Site Listener because not all required Keystore/Truststore Properties are set. Site-to-Site functionality will be disabled until this problem is has been fixed.");
            externalSiteListener = null;
        } else {
            // Register the SocketFlowFileServerProtocol as the appropriate resource for site-to-site Server Protocol
            RemoteResourceManager.setServerProtocolImplementation(SocketFlowFileServerProtocol.RESOURCE_NAME, SocketFlowFileServerProtocol.class);
            externalSiteListener = new SocketRemoteSiteListener(remoteInputSocketPort, isSiteToSiteSecure ? sslContext : null);
            externalSiteListener.setRootGroup(rootGroup);
        }

        // Determine frequency for obtaining component status snapshots
        final String snapshotFrequency = properties.getProperty(NiFiProperties.COMPONENT_STATUS_SNAPSHOT_FREQUENCY, NiFiProperties.DEFAULT_COMPONENT_STATUS_SNAPSHOT_FREQUENCY);
        long snapshotMillis;
        try {
            snapshotMillis = FormatUtils.getTimeDuration(snapshotFrequency, TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            snapshotMillis = FormatUtils.getTimeDuration(NiFiProperties.DEFAULT_COMPONENT_STATUS_SNAPSHOT_FREQUENCY, TimeUnit.MILLISECONDS);
        }

        componentStatusRepository = createComponentStatusRepository();
        timerDrivenEngineRef.get().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                componentStatusRepository.capture(getControllerStatus());
            }
        }, snapshotMillis, snapshotMillis, TimeUnit.MILLISECONDS);

        heartbeatBeanRef.set(new HeartbeatBean(rootGroup, false, false));
    }

    private static FlowFileRepository createFlowFileRepository(final NiFiProperties properties, final ContentClaimManager contentClaimManager) {
        final String implementationClassName = properties.getProperty(NiFiProperties.FLOWFILE_REPOSITORY_IMPLEMENTATION, DEFAULT_FLOWFILE_REPO_IMPLEMENTATION);
        if (implementationClassName == null) {
            throw new RuntimeException("Cannot create FlowFile Repository because the NiFi Properties is missing the following property: "
                    + NiFiProperties.FLOWFILE_REPOSITORY_IMPLEMENTATION);
        }

        try {
            final FlowFileRepository created = NarThreadContextClassLoader.createInstance(implementationClassName, FlowFileRepository.class);
            synchronized (created) {
                created.initialize(contentClaimManager);
            }
            return created;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FlowFileSwapManager createSwapManager(final NiFiProperties properties) {
        final String implementationClassName = properties.getProperty(NiFiProperties.FLOWFILE_SWAP_MANAGER_IMPLEMENTATION, DEFAULT_SWAP_MANAGER_IMPLEMENTATION);
        if (implementationClassName == null) {
            return null;
        }

        try {
            return NarThreadContextClassLoader.createInstance(implementationClassName, FlowFileSwapManager.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static EventReporter createEventReporter(final BulletinRepository bulletinRepository) {
        return new EventReporter() {
            @Override
            public void reportEvent(final Severity severity, final String category, final String message) {
                final Bulletin bulletin = BulletinFactory.createBulletin(category, severity.name(), message);
                bulletinRepository.addBulletin(bulletin);
            }
        };
    }
    
    public void initializeFlow() throws IOException {
        writeLock.lock();
        try {
            flowFileSwapManager = createSwapManager(properties);

            long maxIdFromSwapFiles = -1L;
            if (flowFileSwapManager != null) {
                if (flowFileRepository.isVolatile()) {
                    flowFileSwapManager.purge();
                } else {
                    maxIdFromSwapFiles = flowFileSwapManager.recoverSwappedFlowFiles(this, contentClaimManager);
                }
            }

            flowFileRepository.loadFlowFiles(this, maxIdFromSwapFiles + 1);

            // now that we've loaded the FlowFiles, this has restored our ContentClaims' states, so we can tell the
            // ContentRepository to purge superfluous files
            contentRepository.cleanup();

            if (flowFileSwapManager != null) {
                flowFileSwapManager.start(flowFileRepository, this, contentClaimManager, createEventReporter(bulletinRepository));
            }

            if (externalSiteListener != null) {
                externalSiteListener.start();
            }

            timerDrivenEngineRef.get().scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateRemoteProcessGroups();
                    } catch (final Throwable t) {
                        LOG.warn("Unable to update Remote Process Groups due to " + t);
                        if (LOG.isDebugEnabled()) {
                            LOG.warn("", t);
                        }
                    }
                }
            }, 0L, 30L, TimeUnit.SECONDS);

            initialized.set(true);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * <p>
     * Causes any processors that were added to the flow with a 'delayStart'
     * flag of true to now start
     * </p>
     */
    public void startDelayed() {
        writeLock.lock();
        try {
            LOG.info("Starting {} processors/ports/funnels", (startConnectablesAfterInitialization.size() + startRemoteGroupPortsAfterInitialization.size()));
            for (final Connectable connectable : startConnectablesAfterInitialization) {
                if (connectable.getScheduledState() == ScheduledState.DISABLED) {
                    continue;
                }

                try {
                    if (connectable instanceof ProcessorNode) {
                        connectable.getProcessGroup().startProcessor((ProcessorNode) connectable);
                    } else {
                        startConnectable(connectable);
                    }
                } catch (final Throwable t) {
                    LOG.error("Unable to start {} due to {}", new Object[]{connectable, t});
                }
            }

            startConnectablesAfterInitialization.clear();

            int startedTransmitting = 0;
            for (final RemoteGroupPort remoteGroupPort : startRemoteGroupPortsAfterInitialization) {
                try {
                    remoteGroupPort.getRemoteProcessGroup().startTransmitting(remoteGroupPort);
                    startedTransmitting++;
                } catch (final Throwable t) {
                    LOG.error("Unable to start transmitting with {} due to {}", new Object[]{remoteGroupPort, t});
                }
            }

            LOG.info("Started {} Remote Group Ports transmitting", startedTransmitting);
            startRemoteGroupPortsAfterInitialization.clear();
        } finally {
            writeLock.unlock();
        }
    }

    private ContentRepository createContentRepository(final NiFiProperties properties) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        final String implementationClassName = properties.getProperty(NiFiProperties.CONTENT_REPOSITORY_IMPLEMENTATION, DEFAULT_CONTENT_REPO_IMPLEMENTATION);
        if (implementationClassName == null) {
            throw new RuntimeException("Cannot create Provenance Repository because the NiFi Properties is missing the following property: "
                    + NiFiProperties.CONTENT_REPOSITORY_IMPLEMENTATION);
        }

        try {
            final ContentRepository contentRepo = NarThreadContextClassLoader.createInstance(implementationClassName, ContentRepository.class);
            synchronized (contentRepo) {
                contentRepo.initialize(contentClaimManager);
            }
            return contentRepo;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ProvenanceEventRepository createProvenanceRepository(final NiFiProperties properties) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        final String implementationClassName = properties.getProperty(NiFiProperties.PROVENANCE_REPO_IMPLEMENTATION_CLASS, DEFAULT_PROVENANCE_REPO_IMPLEMENTATION);
        if (implementationClassName == null) {
            throw new RuntimeException("Cannot create Provenance Repository because the NiFi Properties is missing the following property: "
                    + NiFiProperties.PROVENANCE_REPO_IMPLEMENTATION_CLASS);
        }

        try {
            return NarThreadContextClassLoader.createInstance(implementationClassName, ProvenanceEventRepository.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ComponentStatusRepository createComponentStatusRepository() {
        final String implementationClassName = properties.getProperty(NiFiProperties.COMPONENT_STATUS_REPOSITORY_IMPLEMENTATION, DEFAULT_COMPONENT_STATUS_REPO_IMPLEMENTATION);
        if (implementationClassName == null) {
            throw new RuntimeException("Cannot create Component Status Repository because the NiFi Properties is missing the following property: "
                    + NiFiProperties.COMPONENT_STATUS_REPOSITORY_IMPLEMENTATION);
        }

        try {
            return NarThreadContextClassLoader.createInstance(implementationClassName, ComponentStatusRepository.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a connection between two Connectable objects.
     *
     * @param id required ID of the connection
     * @param name the name of the connection, or <code>null</code> to leave the
     * connection unnamed
     * @param source required source
     * @param destination required destination
     * @param relationshipNames required collection of relationship names
     * @return
     *
     * @throws NullPointerException if the ID, source, destination, or set of
     * relationships is null.
     * @throws IllegalArgumentException if <code>relationships</code> is an
     * empty collection
     */
    public Connection createConnection(final String id, final String name, final Connectable source, final Connectable destination, final Collection<String> relationshipNames) {
        final StandardConnection.Builder builder = new StandardConnection.Builder(processScheduler);

        final List<Relationship> relationships = new ArrayList<>();
        for (final String relationshipName : requireNonNull(relationshipNames)) {
            relationships.add(new Relationship.Builder().name(relationshipName).build());
        }

        return builder.id(requireNonNull(id).intern()).name(name == null ? null : name.intern()).relationships(relationships).source(requireNonNull(source)).destination(destination).build();
    }

    /**
     * Creates a new Label
     *
     * @param id
     * @param text
     * @return
     * @throws NullPointerException if either argument is null
     */
    public Label createLabel(final String id, final String text) {
        return new StandardLabel(requireNonNull(id).intern(), text);
    }

    /**
     * Creates a funnel
     *
     * @param id
     * @return
     */
    public Funnel createFunnel(final String id) {
        return new StandardFunnel(id.intern(), null, processScheduler);
    }

    /**
     * Creates a Port to use as an Input Port for a Process Group
     *
     * @param id
     * @param name
     * @return
     * @throws NullPointerException if the ID or name is not unique
     * @throws IllegalStateException if an Input Port already exists with the
     * same name or id.
     */
    public Port createLocalInputPort(String id, String name) {
        id = requireNonNull(id).intern();
        name = requireNonNull(name).intern();
        verifyPortIdDoesNotExist(id);
        return new LocalPort(id, name, null, ConnectableType.INPUT_PORT, processScheduler);
    }

    /**
     * Creates a Port to use as an Output Port for a Process Group
     *
     * @param id
     * @param name
     * @return
     * @throws NullPointerException if the ID or name is not unique
     * @throws IllegalStateException if an Input Port already exists with the
     * same name or id.
     */
    public Port createLocalOutputPort(String id, String name) {
        id = requireNonNull(id).intern();
        name = requireNonNull(name).intern();
        verifyPortIdDoesNotExist(id);
        return new LocalPort(id, name, null, ConnectableType.OUTPUT_PORT, processScheduler);
    }

    /**
     * Creates a ProcessGroup with the given ID
     *
     * @param id
     * @return
     * @throws NullPointerException if the argument is null
     */
    public ProcessGroup createProcessGroup(final String id) {
        return new StandardProcessGroup(requireNonNull(id).intern(), this, processScheduler, properties, encryptor);
    }

    /**
     * <p>
     * Creates a new ProcessorNode with the given type and identifier and initializes it invoking the
     * methods annotated with {@link OnAdded}.
     * </p>
     *
     * @param type
     * @param id
     * @return
     * @throws NullPointerException if either arg is null
     * @throws ProcessorInstantiationException if the processor cannot be
     * instantiated for any reason
     */
    public ProcessorNode createProcessor(final String type, String id) throws ProcessorInstantiationException {
        return createProcessor(type, id, true);
    }
    
    /**
     * <p>
     * Creates a new ProcessorNode with the given type and identifier and optionally initializes it.
     * </p>
     *
     * @param type the fully qualified Processor class name
     * @param id the unique ID of the Processor
     * @param firstTimeAdded whether or not this is the first time this Processor is added to the graph. If {@code true},
     *                       will invoke methods annotated with the {@link OnAdded} annotation.
     * @return
     * @throws NullPointerException if either arg is null
     * @throws ProcessorInstantiationException if the processor cannot be
     * instantiated for any reason
     */
    @SuppressWarnings("deprecation")
    public ProcessorNode createProcessor(final String type, String id, final boolean firstTimeAdded) throws ProcessorInstantiationException {
        id = id.intern();
        final Processor processor = instantiateProcessor(type, id);
        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(controllerServiceProvider);
        final ProcessorNode procNode = new StandardProcessorNode(processor, id, validationContextFactory, processScheduler, controllerServiceProvider);

        final LogRepository logRepository = LogRepositoryFactory.getRepository(id);
        logRepository.addObserver(StandardProcessorNode.BULLETIN_OBSERVER_ID, LogLevel.WARN, new ProcessorLogObserver(getBulletinRepository(), procNode));

        if ( firstTimeAdded ) {
            try (final NarCloseable x = NarCloseable.withNarLoader()) {
                ReflectionUtils.invokeMethodsWithAnnotation(OnAdded.class, org.apache.nifi.processor.annotation.OnAdded.class, processor);
            } catch (final Exception e) {
                logRepository.removeObserver(StandardProcessorNode.BULLETIN_OBSERVER_ID);
                throw new ProcessorLifeCycleException("Failed to invoke @OnAdded methods of " + procNode.getProcessor(), e);
            }
        }

        return procNode;
    }

    private Processor instantiateProcessor(final String type, final String identifier) throws ProcessorInstantiationException {
        Processor processor;

        final ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassLoader detectedClassLoaderForType = ExtensionManager.getClassLoader(type);
            final Class<?> rawClass;
            if (detectedClassLoaderForType == null) {
                // try to find from the current class loader
                rawClass = Class.forName(type);
            } else {
                // try to find from the registered classloader for that type
                rawClass = Class.forName(type, true, ExtensionManager.getClassLoader(type));
            }

            Thread.currentThread().setContextClassLoader(detectedClassLoaderForType);
            final Class<? extends Processor> processorClass = rawClass.asSubclass(Processor.class);
            processor = processorClass.newInstance();
            final ProcessorLog processorLogger = new SimpleProcessLogger(identifier, processor);
            final ProcessorInitializationContext ctx = new StandardProcessorInitializationContext(identifier, processorLogger, this);
            processor.initialize(ctx);
            return processor;
        } catch (final Throwable t) {
            throw new ProcessorInstantiationException(type, t);
        } finally {
            if (ctxClassLoader != null) {
                Thread.currentThread().setContextClassLoader(ctxClassLoader);
            }
        }
    }

    /**
     * @return the ExtensionManager used for instantiating Processors,
     * Prioritizers, etc.
     */
    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    public String getInstanceId() {
        readLock.lock();
        try {
            return instanceId;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Gets the BulletinRepository for storing and retrieving Bulletins.
     *
     * @return
     */
    public BulletinRepository getBulletinRepository() {
        return bulletinRepository;
    }

    public SnippetManager getSnippetManager() {
        return snippetManager;
    }

    /**
     * Creates a Port to use as an Input Port for the root Process Group, which
     * is used for Site-to-Site communications
     *
     * @param id
     * @param name
     * @return
     * @throws NullPointerException if the ID or name is not unique
     * @throws IllegalStateException if an Input Port already exists with the
     * same name or id.
     */
    public Port createRemoteInputPort(String id, String name) {
        id = requireNonNull(id).intern();
        name = requireNonNull(name).intern();
        verifyPortIdDoesNotExist(id);
        return new StandardRootGroupPort(id, name, null, TransferDirection.RECEIVE, ConnectableType.INPUT_PORT, userService, getBulletinRepository(), processScheduler, Boolean.TRUE.equals(isSiteToSiteSecure));
    }

    /**
     * Creates a Port to use as an Output Port for the root Process Group, which
     * is used for Site-to-Site communications and will queue flow files waiting
     * to be delivered to remote instances
     *
     * @param id
     * @param name
     * @return
     * @throws NullPointerException if the ID or name is not unique
     * @throws IllegalStateException if an Input Port already exists with the
     * same name or id.
     */
    public Port createRemoteOutputPort(String id, String name) {
        id = requireNonNull(id).intern();
        name = requireNonNull(name).intern();
        verifyPortIdDoesNotExist(id);
        return new StandardRootGroupPort(id, name, null, TransferDirection.SEND, ConnectableType.OUTPUT_PORT, userService, getBulletinRepository(), processScheduler, Boolean.TRUE.equals(isSiteToSiteSecure));
    }

    /**
     * Creates a new Remote Process Group with the given ID that points to the
     * given URI
     *
     * @param id
     * @param uri
     * @return
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if <code>uri</code> is not a valid URI.
     */
    public RemoteProcessGroup createRemoteProcessGroup(final String id, final String uri) {
        return new StandardRemoteProcessGroup(requireNonNull(id).intern(), requireNonNull(uri).intern(), null, this, sslContext);
    }

    /**
     * Verifies that no output port exists with the given id or name. If this
     * does not hold true, throws an IllegalStateException
     *
     * @param id
     * @throws IllegalStateException
     */
    private void verifyPortIdDoesNotExist(final String id) {
        Port port = rootGroup.findOutputPort(id);
        if (port != null) {
            throw new IllegalStateException("An Input Port already exists with ID " + id);
        }
        port = rootGroup.findInputPort(id);
        if (port != null) {
            throw new IllegalStateException("An Input Port already exists with ID " + id);
        }
    }

    /**
     * @return the name of this controller, which is also the name of the Root
     * Group.
     */
    public String getName() {
        readLock.lock();
        try {
            return rootGroup.getName();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Sets the name for the Root Group, which also changes the name for the
     * controller.
     *
     * @param name
     */
    public void setName(final String name) {
        readLock.lock();
        try {
            rootGroup.setName(name);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Gets the comments of this controller, which is also the comment of the
     * Root Group.
     *
     * @return
     */
    public String getComments() {
        readLock.lock();
        try {
            return rootGroup.getComments();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Sets the comment for the Root Group, which also changes the comment for
     * the controller.
     *
     * @param comments
     */
    public void setComments(final String comments) {
        readLock.lock();
        try {
            rootGroup.setComments(comments);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * @return <code>true</code> if the scheduling engine for this controller
     * has been terminated.
     */
    public boolean isTerminated() {
        this.readLock.lock();
        try {
            return (null == this.timerDrivenEngineRef.get() || this.timerDrivenEngineRef.get().isTerminated());
        } finally {
            this.readLock.unlock();
        }
    }

    /**
     * Triggers the controller to begin shutdown, stopping all processors and
     * terminating the scheduling engine. After calling this method, the
     * {@link #isTerminated()} method will indicate whether or not the shutdown
     * has finished.
     *
     * @param kill if <code>true</code>, attempts to stop all active threads,
     * but makes no guarantee that this will happen
     *
     * @throws IllegalStateException if the controller is already stopped or
     * currently in the processor of stopping
     */
    public void shutdown(final boolean kill) {
        this.shutdown = true;
        stopAllProcessors();

        writeLock.lock();
        try {
            if (isTerminated() || timerDrivenEngineRef.get().isTerminating()) {
                throw new IllegalStateException("Controller already stopped or still stopping...");
            }

            if (kill) {
                this.timerDrivenEngineRef.get().shutdownNow();
                this.eventDrivenEngineRef.get().shutdownNow();
                LOG.info("Initiated immediate shutdown of flow controller...");
            } else {
                this.timerDrivenEngineRef.get().shutdown();
                this.eventDrivenEngineRef.get().shutdown();
                LOG.info("Initiated graceful shutdown of flow controller...waiting up to " + gracefulShutdownSeconds + " seconds");
            }

            clusterTaskExecutor.shutdown();

            // Trigger any processors' methods marked with @OnShutdown to be called
            rootGroup.shutdown();

            try {
                this.timerDrivenEngineRef.get().awaitTermination(gracefulShutdownSeconds / 2, TimeUnit.SECONDS);
                this.eventDrivenEngineRef.get().awaitTermination(gracefulShutdownSeconds / 2, TimeUnit.SECONDS);
            } catch (final InterruptedException ie) {
                LOG.info("Interrupted while waiting for controller termination.");
            }

            try {
                flowFileRepository.close();
            } catch (final Throwable t) {
                LOG.warn("Unable to shut down FlowFileRepository due to {}", new Object[]{t});
            }

            if (this.timerDrivenEngineRef.get().isTerminated() && eventDrivenEngineRef.get().isTerminated()) {
                LOG.info("Controller has been terminated successfully.");
            } else {
                LOG.warn("Controller hasn't terminated properly.  There exists an uninterruptable thread that will take an indeterminate amount of time to stop.  Might need to kill the program manually.");
            }

            if (externalSiteListener != null) {
                externalSiteListener.stop();
            }

            if (flowFileSwapManager != null) {
                flowFileSwapManager.shutdown();
            }
            
            if ( processScheduler != null ) {
            	processScheduler.shutdown();
            }
            
            if ( contentRepository != null ) {
                contentRepository.shutdown();
            }
            
            if ( provenanceEventRepository != null ) {
            	try {
            		provenanceEventRepository.close();
            	} catch (final IOException ioe) {
            		LOG.warn("There was a problem shutting down the Provenance Repository: " + ioe.toString());
            		if ( LOG.isDebugEnabled() ) {
            			LOG.warn("", ioe);
            		}
            	}
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Serializes the current state of the controller to the given OutputStream
     *
     * @param serializer
     * @param os
     * @throws FlowSerializationException if serialization of the flow fails for
     * any reason
     */
    public void serialize(final FlowSerializer serializer, final OutputStream os) throws FlowSerializationException {
        readLock.lock();
        try {
            serializer.serialize(this, os);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Synchronizes this controller with the proposed flow.
     *
     * For more details, see
     * {@link FlowSynchronizer#sync(FlowController, DataFlow)}.
     *
     * @param synchronizer
     * @param dataFlow the flow to load the controller with. If the flow is null
     * or zero length, then the controller must not have a flow or else an
     * UninheritableFlowException will be thrown.
     *
     * @throws FlowSerializationException if proposed flow is not a valid flow
     * configuration file
     * @throws UninheritableFlowException if the proposed flow cannot be loaded
     * by the controller because in doing so would risk orphaning flow files
     * @throws FlowSynchronizationException if updates to the controller failed.
     * If this exception is thrown, then the controller should be considered
     * unsafe to be used
     */
    public void synchronize(final FlowSynchronizer synchronizer, final DataFlow dataFlow)
            throws FlowSerializationException, FlowSynchronizationException, UninheritableFlowException {
        writeLock.lock();
        try {
            LOG.debug("Synchronizing controller with proposed flow");
            synchronizer.sync(this, dataFlow, encryptor);
            LOG.info("Successfully synchronized controller with proposed flow");
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * @return the currently configured maximum number of threads that can be
     * used for executing processors at any given time.
     */
    public int getMaxTimerDrivenThreadCount() {
        return maxTimerDrivenThreads.get();
    }

    public int getMaxEventDrivenThreadCount() {
        return maxEventDrivenThreads.get();
    }

    public void setMaxTimerDrivenThreadCount(final int maxThreadCount) {
        writeLock.lock();
        try {
            setMaxThreadCount(maxThreadCount, this.timerDrivenEngineRef.get(), this.maxTimerDrivenThreads);
        } finally {
            writeLock.unlock();
        }
    }

    public void setMaxEventDrivenThreadCount(final int maxThreadCount) {
        writeLock.lock();
        try {
            setMaxThreadCount(maxThreadCount, this.eventDrivenEngineRef.get(), this.maxEventDrivenThreads);
            processScheduler.setMaxThreadCount(SchedulingStrategy.EVENT_DRIVEN, maxThreadCount);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Updates the number of threads that can be simultaneously used for
     * executing processors.
     *
     * @param maxThreadCount
     *
     * This method must be called while holding the write lock!
     */
    private void setMaxThreadCount(final int maxThreadCount, final FlowEngine engine, final AtomicInteger maxThreads) {
        if (maxThreadCount < 1) {
            throw new IllegalArgumentException();
        }

        maxThreads.getAndSet(maxThreadCount);
        if (null != engine && engine.getCorePoolSize() < maxThreadCount) {
            engine.setCorePoolSize(maxThreads.intValue());
        }
    }

    /**
     * @return the ID of the root group
     */
    public String getRootGroupId() {
        readLock.lock();
        try {
            return rootGroup.getIdentifier();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Sets the root group to the given group
     *
     * @param group the ProcessGroup that is to become the new Root Group
     *
     * @throws IllegalArgumentException if the ProcessGroup has a parent
     * @throws IllegalStateException if the FlowController does not know about
     * the given process group
     */
    void setRootGroup(final ProcessGroup group) {
        if (requireNonNull(group).getParent() != null) {
            throw new IllegalArgumentException("A ProcessGroup that has a parent cannot be the Root Group");
        }

        writeLock.lock();
        try {
            rootGroup = group;

            if (externalSiteListener != null) {
                externalSiteListener.setRootGroup(group);
            }

            // update the heartbeat bean
            this.heartbeatBeanRef.set(new HeartbeatBean(rootGroup, primary, connected));
        } finally {
            writeLock.unlock();
        }
    }

    public SystemDiagnostics getSystemDiagnostics() {
        final SystemDiagnosticsFactory factory = new SystemDiagnosticsFactory();
        return factory.create(flowFileRepository, contentRepository);
    }

    //
    // ProcessGroup access
    //
    /**
     * Updates the process group corresponding to the specified DTO. Any field
     * in DTO that is <code>null</code> (with the exception of the required ID)
     * will be ignored.
     *
     * @param dto
     * @return a fully-populated DTO representing the newly updated ProcessGroup
     * @throws ProcessorInstantiationException
     *
     * @throws IllegalStateException if no process group can be found with the
     * ID of DTO or with the ID of the DTO's parentGroupId, if the template ID
     * specified is invalid, or if the DTO's Parent Group ID changes but the
     * parent group has incoming or outgoing connections
     *
     * @throws NullPointerException if the DTO or its ID is null
     */
    public void updateProcessGroup(final ProcessGroupDTO dto) throws ProcessorInstantiationException {
        final ProcessGroup group = lookupGroup(requireNonNull(dto).getId());

        final String name = dto.getName();
        final PositionDTO position = dto.getPosition();
        final String comments = dto.getComments();

        if (name != null) {
            group.setName(name);
        }
        if (position != null) {
            group.setPosition(toPosition(position));
        }
        if (comments != null) {
            group.setComments(comments);
        }
    }

    //
    // Template access
    //
    /**
     * Adds a template to this controller. The contents of this template must be
     * part of the current flow. This is going create a template based on a
     * snippet of this flow.
     *
     * @param dto
     * @return a copy of the given DTO
     * @throws IOException if an I/O error occurs when persisting the Template
     * @throws NullPointerException if the DTO is null
     * @throws IllegalArgumentException if does not contain all required
     * information, such as the template name or a processor's configuration
     * element
     */
    public Template addTemplate(final TemplateDTO dto) throws IOException {
        return templateManager.addTemplate(dto);
    }

    /**
     * Removes all templates from this controller
     *
     * @throws IOException
     */
    public void clearTemplates() throws IOException {
        templateManager.clear();
    }

    /**
     * Imports the specified template into this controller. The contents of this
     * template may have come from another NiFi instance.
     *
     * @param dto
     * @return
     * @throws IOException
     */
    public Template importTemplate(final TemplateDTO dto) throws IOException {
        return templateManager.importTemplate(dto);
    }

    /**
     * Returns the template with the given ID, or <code>null</code> if no
     * template exists with the given ID.
     *
     * @param id
     * @return
     */
    public Template getTemplate(final String id) {
        return templateManager.getTemplate(id);
    }

    public TemplateManager getTemplateManager() {
        return templateManager;
    }

    /**
     * Returns all templates that this controller knows about.
     *
     * @return
     */
    public Collection<Template> getTemplates() {
        return templateManager.getTemplates();
    }

    /**
     * Removes the template with the given ID.
     *
     * @param id the ID of the template to remove
     * @throws NullPointerException if the argument is null
     * @throws IllegalStateException if no template exists with the given ID
     * @throws IOException if template could not be removed
     */
    public void removeTemplate(final String id) throws IOException, IllegalStateException {
        templateManager.removeTemplate(id);
    }

    private Position toPosition(final PositionDTO dto) {
        return new Position(dto.getX(), dto.getY());
    }

    //
    // Snippet
    //
    /**
     * Creates an instance of the given snippet and adds the components to the
     * given group
     *
     * @param group
     * @param dto
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if the snippet is not valid because a
     * component in the snippet has an ID that is not unique to this flow, or
     * because it shares an Input Port or Output Port at the root level whose
     * name already exists in the given ProcessGroup, or because the Template
     * contains a Processor or a Prioritizer whose class is not valid within
     * this instance of NiFi.
     * @throws ProcessorInstantiationException if unable to instantiate a
     * processor
     */
    public void instantiateSnippet(final ProcessGroup group, final FlowSnippetDTO dto) throws ProcessorInstantiationException {
        writeLock.lock();
        try {
            validateSnippetContents(requireNonNull(group), dto);

            //
            // Instantiate the labels
            //
            for (final LabelDTO labelDTO : dto.getLabels()) {
                final Label label = createLabel(labelDTO.getId(), labelDTO.getLabel());
                label.setPosition(toPosition(labelDTO.getPosition()));
                if (labelDTO.getWidth() != null && labelDTO.getHeight() != null) {
                    label.setSize(new Size(labelDTO.getWidth(), labelDTO.getHeight()));
                }

                // TODO: Update the label's "style"
                group.addLabel(label);
            }

            // 
            // Instantiate the funnels
            for (final FunnelDTO funnelDTO : dto.getFunnels()) {
                final Funnel funnel = createFunnel(funnelDTO.getId());
                funnel.setPosition(toPosition(funnelDTO.getPosition()));
                group.addFunnel(funnel);
            }

            //
            // Instantiate Input Ports & Output Ports
            //
            for (final PortDTO portDTO : dto.getInputPorts()) {
                final Port inputPort;
                if (group.isRootGroup()) {
                    inputPort = createRemoteInputPort(portDTO.getId(), portDTO.getName());
                    inputPort.setMaxConcurrentTasks(portDTO.getConcurrentlySchedulableTaskCount());
                    if (portDTO.getGroupAccessControl() != null) {
                        ((RootGroupPort) inputPort).setGroupAccessControl(portDTO.getGroupAccessControl());
                    }
                    if (portDTO.getUserAccessControl() != null) {
                        ((RootGroupPort) inputPort).setUserAccessControl(portDTO.getUserAccessControl());
                    }
                } else {
                    inputPort = createLocalInputPort(portDTO.getId(), portDTO.getName());
                }

                inputPort.setPosition(toPosition(portDTO.getPosition()));
                inputPort.setProcessGroup(group);
                inputPort.setComments(portDTO.getComments());
                group.addInputPort(inputPort);
            }

            for (final PortDTO portDTO : dto.getOutputPorts()) {
                final Port outputPort;
                if (group.isRootGroup()) {
                    outputPort = createRemoteOutputPort(portDTO.getId(), portDTO.getName());
                    outputPort.setMaxConcurrentTasks(portDTO.getConcurrentlySchedulableTaskCount());
                    if (portDTO.getGroupAccessControl() != null) {
                        ((RootGroupPort) outputPort).setGroupAccessControl(portDTO.getGroupAccessControl());
                    }
                    if (portDTO.getUserAccessControl() != null) {
                        ((RootGroupPort) outputPort).setUserAccessControl(portDTO.getUserAccessControl());
                    }
                } else {
                    outputPort = createLocalOutputPort(portDTO.getId(), portDTO.getName());
                }

                outputPort.setPosition(toPosition(portDTO.getPosition()));
                outputPort.setProcessGroup(group);
                outputPort.setComments(portDTO.getComments());
                group.addOutputPort(outputPort);
            }

            //
            // Instantiate the processors
            //
            for (final ProcessorDTO processorDTO : dto.getProcessors()) {
                final ProcessorNode procNode = createProcessor(processorDTO.getType(), processorDTO.getId());

                procNode.setPosition(toPosition(processorDTO.getPosition()));
                procNode.setProcessGroup(group);

                final ProcessorConfigDTO config = processorDTO.getConfig();
                procNode.setComments(config.getComments());
                if (config.isLossTolerant() != null) {
                    procNode.setLossTolerant(config.isLossTolerant());
                }
                procNode.setName(processorDTO.getName());

                procNode.setYieldPeriod(config.getYieldDuration());
                procNode.setPenalizationPeriod(config.getPenaltyDuration());
                procNode.setBulletinLevel(LogLevel.valueOf(config.getBulletinLevel()));
                procNode.setAnnotationData(config.getAnnotationData());
                procNode.setStyle(processorDTO.getStyle());

                if (config.getRunDurationMillis() != null) {
                    procNode.setRunDuration(config.getRunDurationMillis(), TimeUnit.MILLISECONDS);
                }

                if (config.getSchedulingStrategy() != null) {
                    procNode.setSchedulingStrategy(SchedulingStrategy.valueOf(config.getSchedulingStrategy()));
                }

                // ensure that the scheduling strategy is set prior to these values
                procNode.setMaxConcurrentTasks(config.getConcurrentlySchedulableTaskCount());
                procNode.setScheduldingPeriod(config.getSchedulingPeriod());

                final Set<Relationship> relationships = new HashSet<>();
                if (processorDTO.getRelationships() != null) {
                    for (final RelationshipDTO rel : processorDTO.getRelationships()) {
                        if (rel.isAutoTerminate()) {
                            relationships.add(procNode.getRelationship(rel.getName()));
                        }
                    }
                    procNode.setAutoTerminatedRelationships(relationships);
                }

                if (config.getProperties() != null) {
                    for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
                        if (entry.getValue() != null) {
                            procNode.setProperty(entry.getKey(), entry.getValue());
                        }
                    }
                }

                group.addProcessor(procNode);
            }

            //
            // Instantiate Remote Process Groups
            //
            for (final RemoteProcessGroupDTO remoteGroupDTO : dto.getRemoteProcessGroups()) {
                final RemoteProcessGroup remoteGroup = createRemoteProcessGroup(remoteGroupDTO.getId(), remoteGroupDTO.getTargetUri());
                remoteGroup.setComments(remoteGroupDTO.getComments());
                remoteGroup.setPosition(toPosition(remoteGroupDTO.getPosition()));
                remoteGroup.setCommunicationsTimeout(remoteGroupDTO.getCommunicationsTimeout());
                remoteGroup.setYieldDuration(remoteGroupDTO.getYieldDuration());
                remoteGroup.setProcessGroup(group);

                // set the input/output ports
                if (remoteGroupDTO.getContents() != null) {
                    final RemoteProcessGroupContentsDTO contents = remoteGroupDTO.getContents();

                    // ensure there input ports
                    if (contents.getInputPorts() != null) {
                        remoteGroup.setInputPorts(convertRemotePort(contents.getInputPorts()));
                    }

                    // ensure there are output ports
                    if (contents.getOutputPorts() != null) {
                        remoteGroup.setOutputPorts(convertRemotePort(contents.getOutputPorts()));
                    }
                }

                group.addRemoteProcessGroup(remoteGroup);
            }

            // 
            // Instantiate ProcessGroups
            //
            for (final ProcessGroupDTO groupDTO : dto.getProcessGroups()) {
                final ProcessGroup childGroup = createProcessGroup(groupDTO.getId());
                childGroup.setParent(group);
                childGroup.setPosition(toPosition(groupDTO.getPosition()));
                childGroup.setComments(groupDTO.getComments());
                childGroup.setName(groupDTO.getName());
                group.addProcessGroup(childGroup);

                final FlowSnippetDTO contents = groupDTO.getContents();

                // we want this to be recursive, so we will create a new template that contains only
                // the contents of this child group and recursively call ourselves.
                final FlowSnippetDTO childTemplateDTO = new FlowSnippetDTO();
                childTemplateDTO.setConnections(contents.getConnections());
                childTemplateDTO.setInputPorts(contents.getInputPorts());
                childTemplateDTO.setLabels(contents.getLabels());
                childTemplateDTO.setOutputPorts(contents.getOutputPorts());
                childTemplateDTO.setProcessGroups(contents.getProcessGroups());
                childTemplateDTO.setProcessors(contents.getProcessors());
                childTemplateDTO.setFunnels(contents.getFunnels());
                childTemplateDTO.setRemoteProcessGroups(contents.getRemoteProcessGroups());
                instantiateSnippet(childGroup, childTemplateDTO);
            }

            //
            // Instantiate Connections
            //
            for (final ConnectionDTO connectionDTO : dto.getConnections()) {
                final ConnectableDTO sourceDTO = connectionDTO.getSource();
                final ConnectableDTO destinationDTO = connectionDTO.getDestination();
                final Connectable source;
                final Connectable destination;

                // locate the source and destination connectable. if this is a remote port 
                // we need to locate the remote process groups. otherwise we need to 
                // find the connectable given its parent group.
                // NOTE: (getConnectable returns ANY connectable, when the parent is
                // not this group only input ports or output ports should be returned. if something 
                // other than a port is returned, an exception will be thrown when adding the 
                // connection below.)
                // see if the source connectable is a remote port
                if (ConnectableType.REMOTE_OUTPUT_PORT.name().equals(sourceDTO.getType())) {
                    final RemoteProcessGroup remoteGroup = group.getRemoteProcessGroup(sourceDTO.getGroupId());
                    source = remoteGroup.getOutputPort(sourceDTO.getId());
                } else {
                    final ProcessGroup sourceGroup = getConnectableParent(group, sourceDTO.getGroupId());
                    source = sourceGroup.getConnectable(sourceDTO.getId());
                }

                // see if the destination connectable is a remote port
                if (ConnectableType.REMOTE_INPUT_PORT.name().equals(destinationDTO.getType())) {
                    final RemoteProcessGroup remoteGroup = group.getRemoteProcessGroup(destinationDTO.getGroupId());
                    destination = remoteGroup.getInputPort(destinationDTO.getId());
                } else {
                    final ProcessGroup destinationGroup = getConnectableParent(group, destinationDTO.getGroupId());
                    destination = destinationGroup.getConnectable(destinationDTO.getId());
                }

                // determine the selection relationships for this connection
                final Set<String> relationships = new HashSet<>();
                if (connectionDTO.getSelectedRelationships() != null) {
                    relationships.addAll(connectionDTO.getSelectedRelationships());
                }

                final Connection connection = createConnection(connectionDTO.getId(), connectionDTO.getName(), source, destination, relationships);

                if (connectionDTO.getBends() != null) {
                    final List<Position> bendPoints = new ArrayList<>();
                    for (final PositionDTO bend : connectionDTO.getBends()) {
                        bendPoints.add(new Position(bend.getX(), bend.getY()));
                    }
                    connection.setBendPoints(bendPoints);
                }

                final FlowFileQueue queue = connection.getFlowFileQueue();
                queue.setBackPressureDataSizeThreshold(connectionDTO.getBackPressureDataSizeThreshold());
                queue.setBackPressureObjectThreshold(connectionDTO.getBackPressureObjectThreshold());
                queue.setFlowFileExpiration(connectionDTO.getFlowFileExpiration());

                final List<String> prioritizers = connectionDTO.getPrioritizers();
                if (prioritizers != null) {
                    final List<String> newPrioritizersClasses = new ArrayList<>(prioritizers);
                    final List<FlowFilePrioritizer> newPrioritizers = new ArrayList<>();
                    for (final String className : newPrioritizersClasses) {
                        try {
                            newPrioritizers.add(createPrioritizer(className));
                        } catch (final ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                            throw new IllegalArgumentException("Unable to set prioritizer " + className + ": " + e);
                        }
                    }
                    queue.setPriorities(newPrioritizers);
                }

                connection.setProcessGroup(group);
                group.addConnection(connection);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Converts a set of ports into a set of remote process group ports.
     *
     * @param ports
     * @return
     */
    private Set<RemoteProcessGroupPortDescriptor> convertRemotePort(final Set<RemoteProcessGroupPortDTO> ports) {
        Set<RemoteProcessGroupPortDescriptor> remotePorts = null;
        if (ports != null) {
            remotePorts = new LinkedHashSet<>(ports.size());
            for (RemoteProcessGroupPortDTO port : ports) {
                final StandardRemoteProcessGroupPortDescriptor descriptor = new StandardRemoteProcessGroupPortDescriptor();
                descriptor.setId(port.getId());
                descriptor.setName(port.getName());
                descriptor.setComments(port.getComments());
                descriptor.setTargetRunning(port.isTargetRunning());
                descriptor.setConnected(port.isConnected());
                descriptor.setConcurrentlySchedulableTaskCount(port.getConcurrentlySchedulableTaskCount());
                descriptor.setTransmitting(port.isTransmitting());
                descriptor.setUseCompression(port.getUseCompression());
                remotePorts.add(descriptor);
            }
        }
        return remotePorts;
    }

    /**
     * Returns the parent of the specified Connectable. This only considers this
     * group and any direct child sub groups.
     *
     * @param parentGroupId
     * @return
     */
    private ProcessGroup getConnectableParent(final ProcessGroup group, final String parentGroupId) {
        if (areGroupsSame(group.getIdentifier(), parentGroupId)) {
            return group;
        } else {
            return group.getProcessGroup(parentGroupId);
        }
    }

    /**
     * <p>
     * Verifies that the given DTO is valid, according to the following:
     *
     * <ul>
     * <li>None of the ID's in any component of the DTO can be used in this
     * flow.</li>
     * <li>The ProcessGroup to which the template's contents will be added must
     * not contain any InputPort or OutputPort with the same name as one of the
     * corresponding components in the root level of the template.</li>
     * <li>All Processors' classes must exist in this instance.</li>
     * <li>All Flow File Prioritizers' classes must exist in this instance.</li>
     * </ul>
     * </p>
     *
     * <p>
     * If any of the above statements does not hold true, an
     * {@link IllegalStateException} or a
     * {@link ProcessorInstantiationException} will be thrown.
     * </p>
     *
     * @param group
     * @param templateContents
     */
    private void validateSnippetContents(final ProcessGroup group, final FlowSnippetDTO templateContents) {
        // validate the names of Input Ports
        for (final PortDTO port : templateContents.getInputPorts()) {
            if (group.getInputPortByName(port.getName()) != null) {
                throw new IllegalStateException("ProcessGroup already has an Input Port with name " + port.getName());
            }
        }

        // validate the names of Output Ports
        for (final PortDTO port : templateContents.getOutputPorts()) {
            if (group.getOutputPortByName(port.getName()) != null) {
                throw new IllegalStateException("ProcessGroup already has an Output Port with name " + port.getName());
            }
        }

        // validate that all Processor Types and Prioritizer Types are valid
        final List<String> processorClasses = new ArrayList<>();
        for (final Class<?> c : ExtensionManager.getExtensions(Processor.class)) {
            processorClasses.add(c.getName());
        }
        final List<String> prioritizerClasses = new ArrayList<>();
        for (final Class<?> c : ExtensionManager.getExtensions(FlowFilePrioritizer.class)) {
            prioritizerClasses.add(c.getName());
        }

        final Set<ProcessorDTO> allProcs = new HashSet<>();
        final Set<ConnectionDTO> allConns = new HashSet<>();
        allProcs.addAll(templateContents.getProcessors());
        allConns.addAll(templateContents.getConnections());
        for (final ProcessGroupDTO childGroup : templateContents.getProcessGroups()) {
            allProcs.addAll(findAllProcessors(childGroup));
            allConns.addAll(findAllConnections(childGroup));
        }

        for (final ProcessorDTO proc : allProcs) {
            if (!processorClasses.contains(proc.getType())) {
                throw new IllegalStateException("Invalid Processor Type: " + proc.getType());
            }
        }

        for (final ConnectionDTO conn : allConns) {
            final List<String> prioritizers = conn.getPrioritizers();
            if (prioritizers != null) {
                for (final String prioritizer : prioritizers) {
                    if (!prioritizerClasses.contains(prioritizer)) {
                        throw new IllegalStateException("Invalid FlowFile Prioritizer Type: " + prioritizer);
                    }
                }
            }
        }
    }

    /**
     * Recursively finds all ProcessorDTO's
     *
     * @param group
     * @return
     */
    private Set<ProcessorDTO> findAllProcessors(final ProcessGroupDTO group) {
        final Set<ProcessorDTO> procs = new HashSet<>();
        for (final ProcessorDTO dto : group.getContents().getProcessors()) {
            procs.add(dto);
        }

        for (final ProcessGroupDTO childGroup : group.getContents().getProcessGroups()) {
            procs.addAll(findAllProcessors(childGroup));
        }
        return procs;
    }

    /**
     * Recursively finds all ConnectionDTO's
     *
     * @param group
     * @return
     */
    private Set<ConnectionDTO> findAllConnections(final ProcessGroupDTO group) {
        final Set<ConnectionDTO> conns = new HashSet<>();
        for (final ConnectionDTO dto : group.getContents().getConnections()) {
            conns.add(dto);
        }

        for (final ProcessGroupDTO childGroup : group.getContents().getProcessGroups()) {
            conns.addAll(findAllConnections(childGroup));
        }
        return conns;
    }

    //
    // Processor access
    //
    /**
     * Indicates whether or not the two ID's point to the same ProcessGroup. If
     * either id is null, will return <code>false</code.
     *
     * @param id1
     * @param id2
     * @return
     */
    public boolean areGroupsSame(final String id1, final String id2) {
        if (id1 == null || id2 == null) {
            return false;
        } else if (id1.equals(id2)) {
            return true;
        } else {
            final String comparable1 = (id1.equals(ROOT_GROUP_ID_ALIAS) ? getRootGroupId() : id1);
            final String comparable2 = (id2.equals(ROOT_GROUP_ID_ALIAS) ? getRootGroupId() : id2);
            return (comparable1.equals(comparable2));
        }
    }

    public FlowFilePrioritizer createPrioritizer(final String type) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        FlowFilePrioritizer prioritizer;

        final ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassLoader detectedClassLoaderForType = ExtensionManager.getClassLoader(type);
            final Class<?> rawClass;
            if (detectedClassLoaderForType == null) {
                // try to find from the current class loader
                rawClass = Class.forName(type);
            } else {
                // try to find from the registered classloader for that type
                rawClass = Class.forName(type, true, ExtensionManager.getClassLoader(type));
            }

            Thread.currentThread().setContextClassLoader(detectedClassLoaderForType);
            final Class<? extends FlowFilePrioritizer> prioritizerClass = rawClass.asSubclass(FlowFilePrioritizer.class);
            final Object processorObj = prioritizerClass.newInstance();
            prioritizer = prioritizerClass.cast(processorObj);

            return prioritizer;
        } finally {
            if (ctxClassLoader != null) {
                Thread.currentThread().setContextClassLoader(ctxClassLoader);
            }
        }
    }

    //
    // InputPort access
    //
    public PortDTO updateInputPort(final String parentGroupId, final PortDTO dto) {
        final ProcessGroup parentGroup = lookupGroup(parentGroupId);
        final Port port = parentGroup.getInputPort(dto.getId());
        if (port == null) {
            throw new IllegalStateException("No Input Port with ID " + dto.getId() + " is known as a child of ProcessGroup with ID " + parentGroupId);
        }

        final String name = dto.getName();
        if (dto.getPosition() != null) {
            port.setPosition(toPosition(dto.getPosition()));
        }

        if (name != null) {
            port.setName(name);
        }

        return createDTO(port);
    }

    private PortDTO createDTO(final Port port) {
        if (port == null) {
            return null;
        }

        final PortDTO dto = new PortDTO();
        dto.setId(port.getIdentifier());
        dto.setPosition(new PositionDTO(port.getPosition().getX(), port.getPosition().getY()));
        dto.setName(port.getName());
        dto.setParentGroupId(port.getProcessGroup().getIdentifier());

        return dto;
    }

    //
    // OutputPort access
    //
    public PortDTO updateOutputPort(final String parentGroupId, final PortDTO dto) {
        final ProcessGroup parentGroup = lookupGroup(parentGroupId);
        final Port port = parentGroup.getOutputPort(dto.getId());
        if (port == null) {
            throw new IllegalStateException("No Output Port with ID " + dto.getId() + " is known as a child of ProcessGroup with ID " + parentGroupId);
        }

        final String name = dto.getName();
        if (name != null) {
            port.setName(name);
        }

        if (dto.getPosition() != null) {
            port.setPosition(toPosition(dto.getPosition()));
        }

        return createDTO(port);
    }

    //
    // Processor/Prioritizer/Filter Class Access
    //
    @SuppressWarnings("rawtypes")
    public Set<Class> getFlowFileProcessorClasses() {
        return ExtensionManager.getExtensions(Processor.class);
    }

    @SuppressWarnings("rawtypes")
    public Set<Class> getFlowFileComparatorClasses() {
        return ExtensionManager.getExtensions(FlowFilePrioritizer.class);
    }

    /**
     * Returns the ProcessGroup with the given ID
     *
     * @param id
     * @return the process group or null if not group is found
     */
    private ProcessGroup lookupGroup(final String id) {
        final ProcessGroup group = getGroup(id);
        if (group == null) {
            throw new IllegalStateException("No Group with ID " + id + " exists");
        }
        return group;
    }

    /**
     * Returns the ProcessGroup with the given ID
     *
     * @param id
     * @return the process group or null if not group is found
     */
    public ProcessGroup getGroup(final String id) {
        requireNonNull(id);
        final ProcessGroup root;
        readLock.lock();
        try {
            root = rootGroup;
        } finally {
            readLock.unlock();
        }

        final String searchId = id.equals(ROOT_GROUP_ID_ALIAS) ? getRootGroupId() : id;
        return (root == null) ? null : root.findProcessGroup(searchId);
    }

    @Override
    public ProcessGroupStatus getControllerStatus() {
        return getGroupStatus(getRootGroupId());
    }

    public ProcessGroupStatus getGroupStatus(final String groupId) {
        return getGroupStatus(groupId, getProcessorStats());
    }

    public ProcessGroupStatus getGroupStatus(final String groupId, final RepositoryStatusReport statusReport) {
        final ProcessGroup group = getGroup(groupId);
        return getGroupStatus(group, statusReport);
    }

    public ProcessGroupStatus getGroupStatus(final ProcessGroup group, final RepositoryStatusReport statusReport) {
        if (group == null) {
            return null;
        }

        final ProcessGroupStatus status = new ProcessGroupStatus();
        status.setId(group.getIdentifier());
        status.setName(group.getName());
        status.setCreationTimestamp(new Date().getTime());
        int activeGroupThreads = 0;
        long bytesRead = 0L;
        long bytesWritten = 0L;
        int queuedCount = 0;
        long queuedContentSize = 0L;
        int flowFilesIn = 0;
        long bytesIn = 0L;
        int flowFilesOut = 0;
        long bytesOut = 0L;
        int flowFilesReceived = 0;
        long bytesReceived = 0L;
        int flowFilesSent = 0;
        long bytesSent = 0L;

        // set status for processors
        final Collection<ProcessorStatus> processorStatusCollection = new ArrayList<>();
        status.setProcessorStatus(processorStatusCollection);
        for (final ProcessorNode procNode : group.getProcessors()) {
            final ProcessorStatus procStat = getProcessorStatus(statusReport, procNode);
            processorStatusCollection.add(procStat);
            activeGroupThreads += procStat.getActiveThreadCount();
            bytesRead += procStat.getBytesRead();
            bytesWritten += procStat.getBytesWritten();

            flowFilesReceived += procStat.getFlowFilesReceived();
            bytesReceived += procStat.getBytesReceived();
            flowFilesSent += procStat.getFlowFilesSent();
            bytesSent += procStat.getBytesSent();
        }

        // set status for local child groups     
        final Collection<ProcessGroupStatus> localChildGroupStatusCollection = new ArrayList<>();
        status.setProcessGroupStatus(localChildGroupStatusCollection);
        for (final ProcessGroup childGroup : group.getProcessGroups()) {
            final ProcessGroupStatus childGroupStatus = getGroupStatus(childGroup, statusReport);
            localChildGroupStatusCollection.add(childGroupStatus);
            activeGroupThreads += childGroupStatus.getActiveThreadCount();
            bytesRead += childGroupStatus.getBytesRead();
            bytesWritten += childGroupStatus.getBytesWritten();
            queuedCount += childGroupStatus.getQueuedCount();
            queuedContentSize += childGroupStatus.getQueuedContentSize();

            flowFilesReceived += childGroupStatus.getFlowFilesReceived();
            bytesReceived += childGroupStatus.getBytesReceived();
            flowFilesSent += childGroupStatus.getFlowFilesSent();
            bytesSent += childGroupStatus.getBytesSent();
        }

        // set status for remote child groups
        final Collection<RemoteProcessGroupStatus> remoteProcessGroupStatusCollection = new ArrayList<>();
        status.setRemoteProcessGroupStatus(remoteProcessGroupStatusCollection);
        for (final RemoteProcessGroup remoteGroup : group.getRemoteProcessGroups()) {
            final RemoteProcessGroupStatus remoteStatus = createRemoteGroupStatus(remoteGroup, statusReport);
            if (remoteStatus != null) {
                remoteProcessGroupStatusCollection.add(remoteStatus);

                flowFilesReceived += remoteStatus.getReceivedCount();
                bytesReceived += remoteStatus.getReceivedContentSize();
                flowFilesSent += remoteStatus.getSentCount();
                bytesSent += remoteStatus.getSentContentSize();
            }
        }

        // connection status
        final Collection<ConnectionStatus> connectionStatusCollection = new ArrayList<>();
        status.setConnectionStatus(connectionStatusCollection);

        // get the connection and remote port status
        for (final Connection conn : group.getConnections()) {
            final ConnectionStatus connStatus = new ConnectionStatus();
            connStatus.setId(conn.getIdentifier());
            connStatus.setGroupId(conn.getProcessGroup().getIdentifier());
            connStatus.setSourceId(conn.getSource().getIdentifier());
            connStatus.setSourceName(conn.getSource().getName());
            connStatus.setDestinationId(conn.getDestination().getIdentifier());
            connStatus.setDestinationName(conn.getDestination().getName());

            final FlowFileEvent connectionStatusReport = statusReport.getReportEntry(conn.getIdentifier());
            if (connectionStatusReport != null) {
                connStatus.setInputBytes(connectionStatusReport.getContentSizeIn());
                connStatus.setInputCount(connectionStatusReport.getFlowFilesIn());
                connStatus.setOutputBytes(connectionStatusReport.getContentSizeOut());
                connStatus.setOutputCount(connectionStatusReport.getFlowFilesOut());
            }

            if (StringUtils.isNotBlank(conn.getName())) {
                connStatus.setName(conn.getName());
            } else if (conn.getRelationships() != null && !conn.getRelationships().isEmpty()) {
                final Collection<String> relationships = new ArrayList<>(conn.getRelationships().size());
                for (final Relationship relationship : conn.getRelationships()) {
                    relationships.add(relationship.getName());
                }
                connStatus.setName(StringUtils.join(relationships, ", "));
            }

            final QueueSize queueSize = conn.getFlowFileQueue().size();
            final int connectionQueuedCount = queueSize.getObjectCount();
            final long connectionQueuedBytes = queueSize.getByteCount();
            if (connectionQueuedCount > 0) {
                connStatus.setQueuedBytes(connectionQueuedBytes);
                connStatus.setQueuedCount(connectionQueuedCount);
            }
            connectionStatusCollection.add(connStatus);
            queuedCount += connectionQueuedCount;
            queuedContentSize += connectionQueuedBytes;

            final Connectable source = conn.getSource();
            if (ConnectableType.REMOTE_OUTPUT_PORT.equals(source.getConnectableType())) {
                final RemoteGroupPort remoteOutputPort = (RemoteGroupPort) source;
                activeGroupThreads += processScheduler.getActiveThreadCount(remoteOutputPort);
            }

            final Connectable destination = conn.getDestination();
            if (ConnectableType.REMOTE_INPUT_PORT.equals(destination.getConnectableType())) {
                final RemoteGroupPort remoteInputPort = (RemoteGroupPort) destination;
                activeGroupThreads += processScheduler.getActiveThreadCount(remoteInputPort);
            }
        }

        // status for input ports
        final Collection<PortStatus> inputPortStatusCollection = new ArrayList<>();
        status.setInputPortStatus(inputPortStatusCollection);

        final Set<Port> inputPorts = group.getInputPorts();
        for (final Port port : inputPorts) {
            final PortStatus portStatus = new PortStatus();
            portStatus.setId(port.getIdentifier());
            portStatus.setGroupId(port.getProcessGroup().getIdentifier());
            portStatus.setName(port.getName());
            portStatus.setActiveThreadCount(processScheduler.getActiveThreadCount(port));

            // determine the run status
            if (ScheduledState.RUNNING.equals(port.getScheduledState())) {
                portStatus.setRunStatus(RunStatus.Running);
            } else if (ScheduledState.DISABLED.equals(port.getScheduledState())) {
                portStatus.setRunStatus(RunStatus.Disabled);
            } else if (!port.isValid()) {
                portStatus.setRunStatus(RunStatus.Invalid);
            } else {
                portStatus.setRunStatus(RunStatus.Stopped);
            }

            // special handling for root group ports
            if (port instanceof RootGroupPort) {
                final RootGroupPort rootGroupPort = (RootGroupPort) port;
                portStatus.setTransmitting(rootGroupPort.isTransmitting());
            }

            final FlowFileEvent entry = statusReport.getReportEntries().get(port.getIdentifier());
            if (entry == null) {
                portStatus.setInputBytes(0L);
                portStatus.setInputCount(0);
                portStatus.setOutputBytes(0L);
                portStatus.setOutputCount(0);
            } else {
                final int processedCount = entry.getFlowFilesOut();
                final long numProcessedBytes = entry.getContentSizeOut();
                portStatus.setOutputBytes(numProcessedBytes);
                portStatus.setOutputCount(processedCount);

                final int inputCount = entry.getFlowFilesIn();
                final long inputBytes = entry.getContentSizeIn();
                portStatus.setInputBytes(inputBytes);
                portStatus.setInputCount(inputCount);

                flowFilesIn += inputCount;
                bytesIn += inputBytes;
                bytesWritten += entry.getBytesWritten();

                flowFilesReceived += entry.getFlowFilesReceived();
                bytesReceived += entry.getBytesReceived();
            }

            inputPortStatusCollection.add(portStatus);
            activeGroupThreads += portStatus.getActiveThreadCount();
        }

        // status for output ports
        final Collection<PortStatus> outputPortStatusCollection = new ArrayList<>();
        status.setOutputPortStatus(outputPortStatusCollection);

        final Set<Port> outputPorts = group.getOutputPorts();
        for (final Port port : outputPorts) {
            final PortStatus portStatus = new PortStatus();
            portStatus.setId(port.getIdentifier());
            portStatus.setGroupId(port.getProcessGroup().getIdentifier());
            portStatus.setName(port.getName());
            portStatus.setActiveThreadCount(processScheduler.getActiveThreadCount(port));

            // determine the run status
            if (ScheduledState.RUNNING.equals(port.getScheduledState())) {
                portStatus.setRunStatus(RunStatus.Running);
            } else if (ScheduledState.DISABLED.equals(port.getScheduledState())) {
                portStatus.setRunStatus(RunStatus.Disabled);
            } else if (!port.isValid()) {
                portStatus.setRunStatus(RunStatus.Invalid);
            } else {
                portStatus.setRunStatus(RunStatus.Stopped);
            }

            // special handling for root group ports
            if (port instanceof RootGroupPort) {
                final RootGroupPort rootGroupPort = (RootGroupPort) port;
                portStatus.setTransmitting(rootGroupPort.isTransmitting());
            }

            final FlowFileEvent entry = statusReport.getReportEntries().get(port.getIdentifier());
            if (entry == null) {
                portStatus.setInputBytes(0L);
                portStatus.setInputCount(0);
                portStatus.setOutputBytes(0L);
                portStatus.setOutputCount(0);
            } else {
                final int processedCount = entry.getFlowFilesOut();
                final long numProcessedBytes = entry.getContentSizeOut();
                portStatus.setOutputBytes(numProcessedBytes);
                portStatus.setOutputCount(processedCount);

                final int inputCount = entry.getFlowFilesIn();
                final long inputBytes = entry.getContentSizeIn();
                portStatus.setInputBytes(inputBytes);
                portStatus.setInputCount(inputCount);

                bytesRead += entry.getBytesRead();

                flowFilesOut += entry.getFlowFilesOut();
                bytesOut += entry.getContentSizeOut();

                flowFilesSent = entry.getFlowFilesSent();
                bytesSent += entry.getBytesSent();
            }

            outputPortStatusCollection.add(portStatus);
            activeGroupThreads += portStatus.getActiveThreadCount();
        }

        for (final Funnel funnel : group.getFunnels()) {
            activeGroupThreads += processScheduler.getActiveThreadCount(funnel);
        }

        status.setActiveThreadCount(activeGroupThreads);
        status.setBytesRead(bytesRead);
        status.setBytesWritten(bytesWritten);
        status.setQueuedCount(queuedCount);
        status.setQueuedContentSize(queuedContentSize);
        status.setInputContentSize(bytesIn);
        status.setInputCount(flowFilesIn);
        status.setOutputContentSize(bytesOut);
        status.setOutputCount(flowFilesOut);
        status.setFlowFilesReceived(flowFilesReceived);
        status.setBytesReceived(bytesReceived);
        status.setFlowFilesSent(flowFilesSent);
        status.setBytesSent(bytesSent);

        return status;
    }

    private RemoteProcessGroupStatus createRemoteGroupStatus(final RemoteProcessGroup remoteGroup, final RepositoryStatusReport statusReport) {
        int receivedCount = 0;
        long receivedContentSize = 0L;
        int sentCount = 0;
        long sentContentSize = 0L;
        int activeThreadCount = 0;
        int activePortCount = 0;
        int inactivePortCount = 0;

        final RemoteProcessGroupStatus status = new RemoteProcessGroupStatus();
        status.setGroupId(remoteGroup.getProcessGroup().getIdentifier());
        status.setName(remoteGroup.getName());
        status.setTargetUri(remoteGroup.getTargetUri().toString());

        long lineageMillis = 0L;
        int flowFilesRemoved = 0;
        int flowFilesTransferred = 0;
        for (final Port port : remoteGroup.getInputPorts()) {
            // determine if this input port is connected
            final boolean isConnected = port.hasIncomingConnection();

            // we only want to conside remote ports that we are connected to
            if (isConnected) {
                if (port.isRunning()) {
                    activePortCount++;
                } else {
                    inactivePortCount++;
                }

                activeThreadCount += processScheduler.getActiveThreadCount(port);
            }

            final FlowFileEvent portEvent = statusReport.getReportEntry(port.getIdentifier());
            if (portEvent != null) {
                lineageMillis += portEvent.getAggregateLineageMillis();
                flowFilesRemoved += portEvent.getFlowFilesRemoved();
                flowFilesTransferred += portEvent.getFlowFilesOut();
                sentCount += portEvent.getFlowFilesSent();
                sentContentSize += portEvent.getBytesSent();
            }
        }

        for (final Port port : remoteGroup.getOutputPorts()) {
            // determine if this output port is connected
            final boolean isConnected = !port.getConnections().isEmpty();

            // we only want to conside remote ports that we are connected from
            if (isConnected) {
                if (port.isRunning()) {
                    activePortCount++;
                } else {
                    inactivePortCount++;
                }

                activeThreadCount += processScheduler.getActiveThreadCount(port);
            }

            final FlowFileEvent portEvent = statusReport.getReportEntry(port.getIdentifier());
            if (portEvent != null) {
                receivedCount += portEvent.getFlowFilesReceived();
                receivedContentSize += portEvent.getBytesReceived();
            }
        }

        status.setId(remoteGroup.getIdentifier());
        status.setTransmissionStatus(remoteGroup.isTransmitting() ? TransmissionStatus.Transmitting : TransmissionStatus.NotTransmitting);
        status.setActiveThreadCount(activeThreadCount);
        status.setReceivedContentSize(receivedContentSize);
        status.setReceivedCount(receivedCount);
        status.setSentContentSize(sentContentSize);
        status.setSentCount(sentCount);
        status.setActiveRemotePortCount(activePortCount);
        status.setInactiveRemotePortCount(inactivePortCount);

        final int flowFilesOutOrRemoved = flowFilesTransferred + flowFilesRemoved;
        status.setAverageLineageDuration(flowFilesOutOrRemoved == 0 ? 0 : lineageMillis / flowFilesOutOrRemoved, TimeUnit.MILLISECONDS);

        if (remoteGroup.getAuthorizationIssue() != null) {
            status.setAuthorizationIssues(Arrays.asList(remoteGroup.getAuthorizationIssue()));
        }

        return status;
    }

    private ProcessorStatus getProcessorStatus(final RepositoryStatusReport report, final ProcessorNode procNode) {
        final ProcessorStatus status = new ProcessorStatus();
        status.setId(procNode.getIdentifier());
        status.setGroupId(procNode.getProcessGroup().getIdentifier());
        status.setName(procNode.getName());
        status.setType(procNode.getProcessor().getClass().getSimpleName());

        final FlowFileEvent entry = report.getReportEntries().get(procNode.getIdentifier());
        if (entry == null) {
            status.setInputBytes(0L);
            status.setInputCount(0);
            status.setOutputBytes(0L);
            status.setOutputCount(0);
            status.setBytesWritten(0L);
            status.setBytesRead(0L);
            status.setProcessingNanos(0);
            status.setInvocations(0);
            status.setAverageLineageDuration(0L);
        } else {
            final int processedCount = entry.getFlowFilesOut();
            final long numProcessedBytes = entry.getContentSizeOut();
            status.setOutputBytes(numProcessedBytes);
            status.setOutputCount(processedCount);

            final int inputCount = entry.getFlowFilesIn();
            final long inputBytes = entry.getContentSizeIn();
            status.setInputBytes(inputBytes);
            status.setInputCount(inputCount);

            final long readBytes = entry.getBytesRead();
            status.setBytesRead(readBytes);

            final long writtenBytes = entry.getBytesWritten();
            status.setBytesWritten(writtenBytes);

            status.setProcessingNanos(entry.getProcessingNanoseconds());
            status.setInvocations(entry.getInvocations());

            status.setAverageLineageDuration(entry.getAverageLineageMillis());

            status.setFlowFilesReceived(entry.getFlowFilesReceived());
            status.setBytesReceived(entry.getBytesReceived());
            status.setFlowFilesSent(entry.getFlowFilesSent());
            status.setBytesSent(entry.getBytesSent());
        }

        // determine the run status and get any validation errors... must check
        // is valid when not disabled since a processors validity could change due 
        // to environmental conditions (property configured with a file path and 
        // the file being externally removed)
        if (ScheduledState.DISABLED.equals(procNode.getScheduledState())) {
            status.setRunStatus(RunStatus.Disabled);
        } else if (!procNode.isValid()) {
            status.setRunStatus(RunStatus.Invalid);
        } else if (ScheduledState.RUNNING.equals(procNode.getScheduledState())) {
            status.setRunStatus(RunStatus.Running);
        } else {
            status.setRunStatus(RunStatus.Stopped);
        }

        status.setActiveThreadCount(processScheduler.getActiveThreadCount(procNode));

        return status;
    }

    public void startProcessor(final String parentGroupId, final String processorId) {
        final ProcessGroup group = lookupGroup(parentGroupId);
        final ProcessorNode node = group.getProcessor(processorId);
        if (node == null) {
            throw new IllegalStateException("Cannot find ProcessorNode with ID " + processorId + " within ProcessGroup with ID " + parentGroupId);
        }

        writeLock.lock();
        try {
            if (initialized.get()) {
                group.startProcessor(node);
            } else {
                startConnectablesAfterInitialization.add(node);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public void startConnectable(final Connectable connectable) {
        final ProcessGroup group = requireNonNull(connectable).getProcessGroup();

        writeLock.lock();
        try {
            if (initialized.get()) {
                switch (requireNonNull(connectable).getConnectableType()) {
                    case FUNNEL:
                        group.startFunnel((Funnel) connectable);
                        break;
                    case INPUT_PORT:
                    case REMOTE_INPUT_PORT:
                        group.startInputPort((Port) connectable);
                        break;
                    case OUTPUT_PORT:
                    case REMOTE_OUTPUT_PORT:
                        group.startOutputPort((Port) connectable);
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            } else {
                startConnectablesAfterInitialization.add(connectable);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void startTransmitting(final RemoteGroupPort remoteGroupPort) {
        writeLock.lock();
        try {
            if (initialized.get()) {
                remoteGroupPort.getRemoteProcessGroup().startTransmitting(remoteGroupPort);
            } else {
                startRemoteGroupPortsAfterInitialization.add(remoteGroupPort);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void stopProcessor(final String parentGroupId, final String processorId) {
        final ProcessGroup group = lookupGroup(parentGroupId);
        final ProcessorNode node = group.getProcessor(processorId);
        if (node == null) {
            throw new IllegalStateException("Cannot find ProcessorNode with ID " + processorId + " within ProcessGroup with ID " + parentGroupId);
        }
        group.stopProcessor(node);
    }

    public void stopAllProcessors() {
        stopProcessGroup(getRootGroupId());
    }

    public void startProcessGroup(final String groupId) {
        lookupGroup(groupId).startProcessing();
    }

    public void stopProcessGroup(final String groupId) {
        lookupGroup(groupId).stopProcessing();
    }

    public ReportingTaskNode createReportingTask(final String type) throws ReportingTaskInstantiationException {
        return createReportingTask(type, true);
    }
    
    public ReportingTaskNode createReportingTask(final String type, final boolean firstTimeAdded) throws ReportingTaskInstantiationException {
    	return createReportingTask(type, UUID.randomUUID().toString(), firstTimeAdded);
    }
    
    public ReportingTaskNode createReportingTask(final String type, final String id, final boolean firstTimeAdded) throws ReportingTaskInstantiationException {
        if (type == null || id == null) {
            throw new NullPointerException();
        }
        
        ReportingTask task = null;
        final ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassLoader detectedClassLoader = ExtensionManager.getClassLoader(type);
            final Class<?> rawClass;
            if (detectedClassLoader == null) {
                rawClass = Class.forName(type);
            } else {
                rawClass = Class.forName(type, false, detectedClassLoader);
            }

            Thread.currentThread().setContextClassLoader(detectedClassLoader);
            final Class<? extends ReportingTask> reportingTaskClass = rawClass.asSubclass(ReportingTask.class);
            final Object reportingTaskObj = reportingTaskClass.newInstance();
            task = reportingTaskClass.cast(reportingTaskObj);
        } catch (final ClassNotFoundException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException t) {
            throw new ReportingTaskInstantiationException(type, t);
        } finally {
            if (ctxClassLoader != null) {
                Thread.currentThread().setContextClassLoader(ctxClassLoader);
            }
        }

        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(controllerServiceProvider);
        final ReportingTaskNode taskNode = new StandardReportingTaskNode(task, id, this, processScheduler, validationContextFactory);
        
        if ( firstTimeAdded ) {
            try (final NarCloseable x = NarCloseable.withNarLoader()) {
                ReflectionUtils.invokeMethodsWithAnnotation(OnAdded.class, task);
            } catch (final Exception e) {
                throw new ProcessorLifeCycleException("Failed to invoke On-Added Lifecycle methods of " + task, e);
            }
        }
        
        reportingTasks.put(id, taskNode);
        return taskNode;
    }

    public ReportingTaskNode getReportingTaskNode(final String taskId) {
        return reportingTasks.get(taskId);
    }

    public void startReportingTask(final ReportingTaskNode reportingTaskNode) {
        if (isTerminated()) {
            throw new IllegalStateException("Cannot start reporting task " + reportingTaskNode + " because the controller is terminated");
        }

        reportingTaskNode.verifyCanStart();
       	processScheduler.schedule(reportingTaskNode);
    }

    
    public void stopReportingTask(final ReportingTaskNode reportingTaskNode) {
        if (isTerminated()) {
            return;
        }

        reportingTaskNode.verifyCanStop();
        processScheduler.unschedule(reportingTaskNode);
    }

    public void removeReportingTask(final ReportingTaskNode reportingTaskNode) {
        final ReportingTaskNode existing = reportingTasks.get(reportingTaskNode.getIdentifier());
        if ( existing == null || existing != reportingTaskNode ) {
            throw new IllegalStateException("Reporting Task " + reportingTaskNode + " does not exist in this Flow");
        }
        
        reportingTaskNode.verifyCanDelete();
        
        try (final NarCloseable x = NarCloseable.withNarLoader()) {
            ReflectionUtils.quietlyInvokeMethodsWithAnnotation(OnRemoved.class, reportingTaskNode.getReportingTask(), reportingTaskNode.getConfigurationContext());
        }
        
        reportingTasks.remove(reportingTaskNode.getIdentifier());
    }
    
    Collection<ReportingTaskNode> getReportingTasks() {
        return reportingTasks.values();
    }

    /**
     * Recursively stops all Processors and Reporting Tasks that are referencing the given Controller Service,
     * as well as disabling any Controller Service that references this Controller Service (and stops
     * all Reporting Task or Controller Service that is referencing it, and so on).
     * @param serviceNode
     */
    public void deactiveReferencingComponents(final ControllerServiceNode serviceNode) {
    	final ControllerServiceReference reference = serviceNode.getReferences();
    	
    	final Set<ConfiguredComponent> components = reference.getActiveReferences();
    	for (final ConfiguredComponent component : components) {
    		if ( component instanceof ControllerServiceNode ) {
    			deactiveReferencingComponents((ControllerServiceNode) component);
    			
    			if (isControllerServiceEnabled(serviceNode.getIdentifier())) {
    				disableControllerService(serviceNode);
    			}
    		} else if ( component instanceof ReportingTaskNode ) {
    			final ReportingTaskNode taskNode = (ReportingTaskNode) component;
    			if (taskNode.isRunning()) {
    				stopReportingTask((ReportingTaskNode) component);
    			}
    		} else if ( component instanceof ProcessorNode ) {
    			final ProcessorNode procNode = (ProcessorNode) component;
    			if ( procNode.isRunning() ) {
    				stopProcessor(procNode.getProcessGroup().getIdentifier(), procNode.getIdentifier());
    			}
    		}
    	}
    }
    
    
    /**
     * <p>
     * Starts any enabled Processors and Reporting Tasks that are referencing this Controller Service. If other Controller
     * Services reference this Controller Service, will also enable those services and 'active' any components referencing
     * them.
     * </p>
     * 
     * <p>
     * NOTE: If any component cannot be started, an IllegalStateException will be thrown an no more components will
     * be activated. This method provides no atomicity.
     * </p>
     * 
     * @param serviceNode
     */
    public void activateReferencingComponents(final ControllerServiceNode serviceNode) {
    	final ControllerServiceReference ref = serviceNode.getReferences();
    	final Set<ConfiguredComponent> components = ref.getReferencingComponents();
    	
    	// First, activate any other controller services. We do this first so that we can
    	// avoid the situation where Processor X depends on Controller Services Y and Z; and
    	// Controller Service Y depends on Controller Service Z. In this case, if we first attempted
    	// to start Processor X, we would fail because Controller Service Y is disabled. THis way, we
    	// can recursively enable everything.
    	for ( final ConfiguredComponent component : components ) {
    		if (component instanceof ControllerServiceNode) {
    			final ControllerServiceNode componentNode = (ControllerServiceNode) component;
    			enableControllerService(componentNode);
    			activateReferencingComponents(componentNode);
    		}
    	}
    	
    	for ( final ConfiguredComponent component : components ) {
    		if (component instanceof ProcessorNode) {
    			final ProcessorNode procNode = (ProcessorNode) component;
    			if ( !procNode.isRunning() ) {
    				startProcessor(procNode.getProcessGroup().getIdentifier(), procNode.getIdentifier());
    			}
    		} else if (component instanceof ReportingTaskNode) {
    			final ReportingTaskNode taskNode = (ReportingTaskNode) component;
    			if ( !taskNode.isRunning() ) {
    				startReportingTask(taskNode);
    			}
    		}
    	}
    }
    
    @Override
    public ControllerServiceNode createControllerService(final String type, final String id, final boolean firstTimeAdded) {
        return controllerServiceProvider.createControllerService(type, id, firstTimeAdded);
    }
    
    public void enableReportingTask(final ReportingTaskNode reportingTaskNode) {
        reportingTaskNode.verifyCanEnable();
        processScheduler.enableReportingTask(reportingTaskNode);
    }
    
    public void disableReportingTask(final ReportingTaskNode reportingTaskNode) {
        reportingTaskNode.verifyCanDisable();
        processScheduler.disableReportingTask(reportingTaskNode);
    }
    
    @Override
    public void enableControllerService(final ControllerServiceNode serviceNode) {
        serviceNode.verifyCanEnable();
        controllerServiceProvider.enableControllerService(serviceNode);
    }
    
    @Override
    public void disableControllerService(final ControllerServiceNode serviceNode) {
        serviceNode.verifyCanDisable();
        controllerServiceProvider.disableControllerService(serviceNode);
    }

    @Override
    public ControllerService getControllerService(final String serviceIdentifier) {
        return controllerServiceProvider.getControllerService(serviceIdentifier);
    }

    @Override
    public ControllerServiceNode getControllerServiceNode(final String serviceIdentifier) {
        return controllerServiceProvider.getControllerServiceNode(serviceIdentifier);
    }

    @Override
    public boolean isControllerServiceEnabled(final ControllerService service) {
        return controllerServiceProvider.isControllerServiceEnabled(service);
    }

    @Override
    public boolean isControllerServiceEnabled(final String serviceIdentifier) {
        return controllerServiceProvider.isControllerServiceEnabled(serviceIdentifier);
    }

    @Override
    public String getControllerServiceName(final String serviceIdentifier) {
    	return controllerServiceProvider.getControllerServiceName(serviceIdentifier);
    }

    public void removeControllerService(final ControllerServiceNode serviceNode) {
        controllerServiceProvider.removeControllerService(serviceNode);
    }
    
    @Override
    public Set<ControllerServiceNode> getAllControllerServices() {
    	return controllerServiceProvider.getAllControllerServices();
    }
    
    //
    // Counters
    //
    public List<Counter> getCounters() {
        final List<Counter> counters = new ArrayList<>();

        final CounterRepository counterRepo = counterRepositoryRef.get();
        for (final Counter counter : counterRepo.getCounters()) {
            counters.add(counter);
        }

        return counters;
    }

    public Counter resetCounter(final String identifier) {
        final CounterRepository counterRepo = counterRepositoryRef.get();
        final Counter resetValue = counterRepo.resetCounter(identifier);
        heartbeat();
        return resetValue;
    }

    //
    // Access to controller status
    //
    public QueueSize getTotalFlowFileCount(final ProcessGroup group) {
        int count = 0;
        long contentSize = 0L;

        for (final Connection connection : group.getConnections()) {
            final QueueSize size = connection.getFlowFileQueue().size();
            count += size.getObjectCount();
            contentSize += size.getByteCount();
        }
        for (final ProcessGroup childGroup : group.getProcessGroups()) {
            final QueueSize size = getTotalFlowFileCount(childGroup);
            count += size.getObjectCount();
            contentSize += size.getByteCount();
        }

        return new QueueSize(count, contentSize);
    }

    public int getActiveThreadCount() {
        return getGroupStatus(getRootGroupId()).getActiveThreadCount();
    }

    private RepositoryStatusReport getProcessorStats() {
        // processed in last 5 minutes
        return getProcessorStats(System.currentTimeMillis() - 300000);
    }

    private RepositoryStatusReport getProcessorStats(final long since) {
        return flowFileEventRepository.reportTransferEvents(since);
    }

    //
    // Clustering methods
    //
    /**
     * Starts heartbeating to the cluster. May only be called if the instance
     * was constructed for a clustered environment.
     *
     * @throws IllegalStateException
     */
    public void startHeartbeating() throws IllegalStateException {
        if (!configuredForClustering) {
            throw new IllegalStateException("Unable to start heartbeating because heartbeating is not configured.");
        }

        writeLock.lock();
        try {

            stopHeartbeating();

            bulletinFuture = clusterTaskExecutor.scheduleWithFixedDelay(new BulletinsTask(protocolSender), 250, 2000, TimeUnit.MILLISECONDS);

            final HeartbeatMessageGeneratorTask heartbeatMessageGeneratorTask = new HeartbeatMessageGeneratorTask();
            heartbeatMessageGeneratorTaskRef.set(heartbeatMessageGeneratorTask);
            heartbeatGeneratorFuture = clusterTaskExecutor.scheduleWithFixedDelay(heartbeatMessageGeneratorTask, 0, heartbeatDelaySeconds, TimeUnit.SECONDS);

            heartbeatSenderFuture = clusterTaskExecutor.scheduleWithFixedDelay(new HeartbeatSendTask(protocolSender), 250, 250, TimeUnit.MILLISECONDS);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Notifies controller that the sending of heartbeats should be temporarily
     * suspended. This method does not cancel any background tasks as does
     * {@link #stopHeartbeating()} and does not require any lock on the
     * FlowController. Background tasks will still generate heartbeat messages
     * and any background task currently in the process of sending a Heartbeat
     * to the cluster will continue.
     */
    public void suspendHeartbeats() {
        heartbeatsSuspended.set(true);
    }

    /**
     * Notifies controller that the sending of heartbeats should be re-enabled.
     * This method does not submit any background tasks to take affect as does
     * {@link #startHeartbeating()} and does not require any lock on the
     * FlowController.
     */
    public void resumeHeartbeats() {
        heartbeatsSuspended.set(false);
    }

    /**
     * Stops heartbeating to the cluster. May only be called if the instance was
     * constructed for a clustered environment. If the controller was not
     * heartbeating, then this method has no effect.
     *
     * @throws IllegalStateException
     */
    public void stopHeartbeating() throws IllegalStateException {

        if (!configuredForClustering) {
            throw new IllegalStateException("Unable to stop heartbeating because heartbeating is not configured.");
        }

        writeLock.lock();
        try {
            if (!isHeartbeating()) {
                return;
            }

            if (heartbeatGeneratorFuture != null) {
                heartbeatGeneratorFuture.cancel(false);
            }

            if (heartbeatSenderFuture != null) {
                heartbeatSenderFuture.cancel(false);
            }

            if (bulletinFuture != null) {
                bulletinFuture.cancel(false);
            }
        } finally {
            writeLock.unlock();
        }

    }

    /**
     * Returns true if the instance is heartbeating; false otherwise.
     *
     * @return
     */
    public boolean isHeartbeating() {
        readLock.lock();
        try {
            return heartbeatGeneratorFuture != null && !heartbeatGeneratorFuture.isCancelled()
                    && heartbeatSenderFuture != null && !heartbeatSenderFuture.isCancelled();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns the number of seconds to wait between successive heartbeats.
     *
     * @return
     */
    public int getHeartbeatDelaySeconds() {
        readLock.lock();
        try {
            return heartbeatDelaySeconds;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * The node identifier of this instance.
     *
     * @return the node identifier or null if no identifier is set
     */
    public NodeIdentifier getNodeId() {
        readLock.lock();
        try {
            return nodeId;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Sets the node identifier for this instance.
     *
     * @param nodeId the node identifier, which may be null
     */
    public void setNodeId(final NodeIdentifier nodeId) {
        writeLock.lock();
        try {
            this.nodeId = nodeId;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * @return true if this instance is clustered; false otherwise. Clustered
     * means that a node is either connected or trying to connect to the
     * cluster.
     */
    public boolean isClustered() {
        readLock.lock();
        try {
            return clustered;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns the DN of the Cluster Manager that we are currently connected to,
     * if available. This will return null if the instance is not clustered or
     * if the instance is clustered but the NCM's DN is not available - for
     * instance, if cluster communications are not secure.
     *
     * @return
     */
    public String getClusterManagerDN() {
        readLock.lock();
        try {
            return clusterManagerDN;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Sets whether this instance is clustered. Clustered means that a node is
     * either connected or trying to connect to the cluster.
     *
     * @param clustered
     * @param clusterInstanceId if clustered is true, indicates the InstanceID
     * of the Cluster Manager
     */
    public void setClustered(final boolean clustered, final String clusterInstanceId) {
        setClustered(clustered, clusterInstanceId, null);
    }

    /**
     * Sets whether this instance is clustered. Clustered means that a node is
     * either connected or trying to connect to the cluster.
     *
     * @param clustered
     * @param clusterInstanceId if clustered is true, indicates the InstanceID
     * of the Cluster Manager
     * @param clusterManagerDn the DN of the NCM
     */
    public void setClustered(final boolean clustered, final String clusterInstanceId, final String clusterManagerDn) {
        writeLock.lock();
        try {
            // verify whether the this node's clustered status is changing
            boolean isChanging = false;
            if (this.clustered != clustered) {
                isChanging = true;
            }

            // mark the new cluster status
            this.clustered = clustered;
            if (clusterManagerDn != null) {
                this.clusterManagerDN = clusterManagerDn;
            }
            eventDrivenWorkerQueue.setClustered(clustered);

            if (clusterInstanceId != null) {
                this.instanceId = clusterInstanceId;
            }

            // update the bulletin repository
            if (isChanging) {
                if (clustered) {
                    nodeBulletinSubscriber.set(new NodeBulletinProcessingStrategy());
                    bulletinRepository.overrideDefaultBulletinProcessing(nodeBulletinSubscriber.get());
                } else {
                    bulletinRepository.restoreDefaultBulletinProcessing();
                }

                final List<RemoteProcessGroup> remoteGroups = getGroup(getRootGroupId()).findAllRemoteProcessGroups();
                for (final RemoteProcessGroup remoteGroup : remoteGroups) {
                    remoteGroup.reinitialize(clustered);
                }
            }

            // update the heartbeat bean
            this.heartbeatBeanRef.set(new HeartbeatBean(rootGroup, primary, connected));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * @return true if this instance is the primary node in the cluster; false
     * otherwise
     */
    public boolean isPrimary() {
        rwLock.readLock().lock();
        try {
            return primary;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setPrimary(final boolean primary) {
        rwLock.writeLock().lock();
        try {
            // no update, so return
            if (this.primary == primary) {
                return;
            }

            LOG.info("Setting primary flag from '" + this.primary + "' to '" + primary + "'");

            // update primary
            this.primary = primary;
            eventDrivenWorkerQueue.setPrimary(primary);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    static boolean areEqual(final String a, final String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == b) {
            return true;
        }

        if (a == null || b == null) {
            return false;
        }

        return a.equals(b);
    }

    static boolean areEqual(final Long a, final Long b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == b) {
            return true;
        }

        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    public ContentAvailability getContentAvailability(final ProvenanceEventRecord event) {
        final String replayFailure = getReplayFailureReason(event);

        return new ContentAvailability() {
            @Override
            public String getReasonNotReplayable() {
                return replayFailure;
            }

            @Override
            public boolean isContentSame() {
                return areEqual(event.getPreviousContentClaimContainer(), event.getContentClaimContainer())
                        && areEqual(event.getPreviousContentClaimSection(), event.getContentClaimSection())
                        && areEqual(event.getPreviousContentClaimIdentifier(), event.getContentClaimIdentifier())
                        && areEqual(event.getPreviousContentClaimOffset(), event.getContentClaimOffset())
                        && areEqual(event.getPreviousFileSize(), event.getFileSize());
            }

            @Override
            public boolean isInputAvailable() {
                try {
                    return contentRepository.isAccessible(createClaim(event.getPreviousContentClaimContainer(), event.getPreviousContentClaimSection(), event.getPreviousContentClaimIdentifier()));
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public boolean isOutputAvailable() {
                try {
                    return contentRepository.isAccessible(createClaim(event.getContentClaimContainer(), event.getContentClaimSection(), event.getContentClaimIdentifier()));
                } catch (IOException e) {
                    return false;
                }
            }

            private ContentClaim createClaim(final String container, final String section, final String identifier) {
                if (container == null || section == null || identifier == null) {
                    return null;
                }

                return new ContentClaim() {
                    @Override
                    public int compareTo(final ContentClaim o) {
                        return 0;
                    }

                    @Override
                    public String getId() {
                        return identifier;
                    }

                    @Override
                    public String getContainer() {
                        return container;
                    }

                    @Override
                    public String getSection() {
                        return section;
                    }

                    @Override
                    public boolean isLossTolerant() {
                        return false;
                    }
                };
            }

            @Override
            public boolean isReplayable() {
                return replayFailure == null;
            }
        };
    }

    public InputStream getContent(final ProvenanceEventRecord provEvent, final ContentDirection direction, final String requestor, final String requestUri) throws IOException {
        requireNonNull(provEvent);
        requireNonNull(direction);
        requireNonNull(requestor);
        requireNonNull(requestUri);

        final ContentClaim claim;
        final Long offset;
        final long size;
        if (direction == ContentDirection.INPUT) {
            if (provEvent.getPreviousContentClaimContainer() == null || provEvent.getPreviousContentClaimSection() == null || provEvent.getPreviousContentClaimIdentifier() == null) {
                throw new IllegalArgumentException("Input Content Claim not specified");
            }

            claim = contentClaimManager.newContentClaim(provEvent.getPreviousContentClaimContainer(), provEvent.getPreviousContentClaimSection(), provEvent.getPreviousContentClaimIdentifier(), false);
            offset = provEvent.getPreviousContentClaimOffset();
            size = provEvent.getPreviousFileSize();
        } else {
            if (provEvent.getContentClaimContainer() == null || provEvent.getContentClaimSection() == null || provEvent.getContentClaimIdentifier() == null) {
                throw new IllegalArgumentException("Output Content Claim not specified");
            }

            claim = contentClaimManager.newContentClaim(provEvent.getContentClaimContainer(), provEvent.getContentClaimSection(), provEvent.getContentClaimIdentifier(), false);
            offset = provEvent.getContentClaimOffset();
            size = provEvent.getFileSize();
        }

        final InputStream rawStream = contentRepository.read(claim);
        if (offset != null) {
            StreamUtils.skip(rawStream, offset.longValue());
        }

        // Register a Provenance Event to indicate that we replayed the data.
        final ProvenanceEventRecord sendEvent = new StandardProvenanceEventRecord.Builder()
                .setEventType(ProvenanceEventType.SEND)
                .setFlowFileUUID(provEvent.getFlowFileUuid())
                .setAttributes(provEvent.getAttributes(), Collections.<String, String>emptyMap())
                .setCurrentContentClaim(claim.getContainer(), claim.getSection(), claim.getId(), offset, size)
                .setTransitUri(requestUri)
                .setEventTime(System.currentTimeMillis())
                .setFlowFileEntryDate(provEvent.getFlowFileEntryDate())
                .setLineageStartDate(provEvent.getLineageStartDate())
                .setComponentType(getName())
                .setComponentId(getRootGroupId())
                .setDetails("Download of " + (direction == ContentDirection.INPUT ? "Input" : "Output") + " Content requested by " + requestor + " for Provenance Event " + provEvent.getEventId())
                .build();

        provenanceEventRepository.registerEvent(sendEvent);

        return new LimitedInputStream(rawStream, size);
    }

    private String getReplayFailureReason(final ProvenanceEventRecord event) {
        // Check that the event is a valid type.
        final ProvenanceEventType type = event.getEventType();
        if (type == ProvenanceEventType.JOIN) {
            return "Cannot replay events that are created from multiple parents";
        }

        // Make sure event has the Content Claim info
        final Long contentSize = event.getPreviousFileSize();
        final String contentClaimId = event.getPreviousContentClaimIdentifier();
        final String contentClaimSection = event.getPreviousContentClaimSection();
        final String contentClaimContainer = event.getPreviousContentClaimContainer();

        if (contentSize == null || contentClaimId == null || contentClaimSection == null || contentClaimContainer == null) {
            return "Cannot replay data from Provenance Event because the event does not contain the required Content Claim";
        }

        try {
            if (!contentRepository.isAccessible(contentClaimManager.newContentClaim(contentClaimContainer, contentClaimSection, contentClaimId, false))) {
                return "Content is no longer available in Content Repository";
            }
        } catch (final IOException ioe) {
            return "Failed to determine whether or not content was available in Content Repository due to " + ioe.toString();
        }

        // Make sure that the source queue exists 
        if (event.getSourceQueueIdentifier() == null) {
            return "Cannot replay data from Provenance Event because the event does not specify the Source FlowFile Queue";
        }

        final List<Connection> connections = getGroup(getRootGroupId()).findAllConnections();
        FlowFileQueue queue = null;
        for (final Connection connection : connections) {
            if (event.getSourceQueueIdentifier().equals(connection.getIdentifier())) {
                queue = connection.getFlowFileQueue();
                break;
            }
        }

        if (queue == null) {
            return "Cannot replay data from Provenance Event because the Source FlowFile Queue with ID " + event.getSourceQueueIdentifier() + " no longer exists";
        }

        return null;
    }

    public ProvenanceEventRecord replayFlowFile(final long provenanceEventRecordId, final String requestor) throws IOException {
        final ProvenanceEventRecord record = provenanceEventRepository.getEvent(provenanceEventRecordId);
        if (record == null) {
            throw new IllegalStateException("Cannot find Provenance Event with ID " + provenanceEventRecordId);
        }

        return replayFlowFile(record, requestor);
    }

    public ProvenanceEventRecord replayFlowFile(final ProvenanceEventRecord event, final String requestor) throws IOException {
        if (event == null) {
            throw new NullPointerException();
        }

        // Check that the event is a valid type.
        final ProvenanceEventType type = event.getEventType();
        if (type == ProvenanceEventType.JOIN) {
            throw new IllegalArgumentException("Cannot replay events that are created from multiple parents");
        }

        // Make sure event has the Content Claim info
        final Long contentSize = event.getPreviousFileSize();
        final String contentClaimId = event.getPreviousContentClaimIdentifier();
        final String contentClaimSection = event.getPreviousContentClaimSection();
        final String contentClaimContainer = event.getPreviousContentClaimContainer();

        if (contentSize == null || contentClaimId == null || contentClaimSection == null || contentClaimContainer == null) {
            throw new IllegalArgumentException("Cannot replay data from Provenance Event because the event does not contain the required Content Claim");
        }

        // Make sure that the source queue exists 
        if (event.getSourceQueueIdentifier() == null) {
            throw new IllegalArgumentException("Cannot replay data from Provenance Event because the event does not specify the Source FlowFile Queue");
        }

        final List<Connection> connections = getGroup(getRootGroupId()).findAllConnections();
        FlowFileQueue queue = null;
        for (final Connection connection : connections) {
            if (event.getSourceQueueIdentifier().equals(connection.getIdentifier())) {
                queue = connection.getFlowFileQueue();
                break;
            }
        }

        if (queue == null) {
            throw new IllegalStateException("Cannot replay data from Provenance Event because the Source FlowFile Queue with ID " + event.getSourceQueueIdentifier() + " no longer exists");
        }

        // Create the ContentClaim
        final ContentClaim claim = contentClaimManager.newContentClaim(event.getPreviousContentClaimContainer(), event.getPreviousContentClaimSection(), event.getPreviousContentClaimIdentifier(), false);

        // Increment Claimant Count, since we will now be referencing the Content Claim
        contentClaimManager.incrementClaimantCount(claim);

        if (!contentRepository.isAccessible(claim)) {
            contentClaimManager.decrementClaimantCount(claim);
            throw new IllegalStateException("Cannot replay data from Provenance Event because the data is no longer available in the Content Repository");
        }

        final long claimOffset = event.getPreviousContentClaimOffset() == null ? 0L : event.getPreviousContentClaimOffset().longValue();
        final String parentUUID = event.getFlowFileUuid();

        // Create the FlowFile Record
        final Set<String> lineageIdentifiers = new HashSet<>();
        lineageIdentifiers.addAll(event.getLineageIdentifiers());
        lineageIdentifiers.add(parentUUID);

        final String newFlowFileUUID = UUID.randomUUID().toString();
        final FlowFileRecord flowFileRecord = new StandardFlowFileRecord.Builder()
                // Copy relevant info from source FlowFile
                .addAttributes(event.getPreviousAttributes())
                .contentClaim(claim)
                .contentClaimOffset(claimOffset)
                .entryDate(System.currentTimeMillis())
                .id(flowFileRepository.getNextFlowFileSequence())
                .lineageIdentifiers(lineageIdentifiers)
                .lineageStartDate(event.getLineageStartDate())
                .size(contentSize.longValue())
                // Create a new UUID and add attributes indicating that this is a replay
                .addAttribute("flowfile.replay", "true")
                .addAttribute("flowfile.replay.timestamp", String.valueOf(new Date()))
                .addAttribute(CoreAttributes.UUID.key(), newFlowFileUUID)
                // remove attributes that may have existed on the source FlowFile that we don't want to exist on the new FlowFile
                .removeAttributes(CoreAttributes.DISCARD_REASON.key(), CoreAttributes.ALTERNATE_IDENTIFIER.key())
                // build the record
                .build();

        // Register a Provenance Event to indicate that we replayed the data.
        final ProvenanceEventRecord replayEvent = new StandardProvenanceEventRecord.Builder()
                .setEventType(ProvenanceEventType.REPLAY)
                .addChildUuid(newFlowFileUUID)
                .addParentUuid(parentUUID)
                .setFlowFileUUID(parentUUID)
                .setAttributes(Collections.<String, String>emptyMap(), flowFileRecord.getAttributes())
                .setCurrentContentClaim(event.getContentClaimSection(), event.getContentClaimContainer(), event.getContentClaimIdentifier(), event.getContentClaimOffset(), event.getFileSize())
                .setDetails("Replay requested by " + requestor)
                .setEventTime(System.currentTimeMillis())
                .setFlowFileEntryDate(System.currentTimeMillis())
                .setLineageStartDate(event.getLineageStartDate())
                .setComponentType(event.getComponentType())
                .setComponentId(event.getComponentId())
                .build();
        provenanceEventRepository.registerEvent(replayEvent);

        // Update the FlowFile Repository to indicate that we have added the FlowFile to the flow
        final StandardRepositoryRecord record = new StandardRepositoryRecord(queue, flowFileRecord);
        record.setDestination(queue);
        flowFileRepository.updateRepository(Collections.<RepositoryRecord>singleton(record));

        // Enqueue the data
        queue.put(flowFileRecord);

        return replayEvent;
    }

    public boolean isConnected() {
        rwLock.readLock().lock();
        try {
            return connected;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setConnected(final boolean connected) {
        rwLock.writeLock().lock();
        try {
            this.connected = connected;

            // update the heartbeat bean
            this.heartbeatBeanRef.set(new HeartbeatBean(rootGroup, primary, connected));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void heartbeat() {
        if (!isClustered()) {
            return;
        }
        if (this.shutdown) {
            return;
        }

        final HeartbeatMessageGeneratorTask task = heartbeatMessageGeneratorTaskRef.get();
        if (task != null) {
            task.run();
        }
    }

    private class BulletinsTask implements Runnable {

        private final NodeProtocolSender protocolSender;
        private final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.US);

        public BulletinsTask(NodeProtocolSender protocolSender) {
            if (protocolSender == null) {
                throw new IllegalArgumentException("NodeProtocolSender may not be null.");
            }
            this.protocolSender = protocolSender;
        }

        @Override
        public void run() {
            try {
                final NodeBulletinsMessage message = createBulletinsMessage();
                if (message == null) {
                    return;
                }

                protocolSender.sendBulletins(message);
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            String.format(
                                    "Sending bulletins to cluster manager at %s",
                                    dateFormatter.format(new Date())
                            )
                    );
                }

            } catch (final UnknownServiceAddressException usae) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(usae.getMessage());
                }
            } catch (final Exception ex) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Failed to send bulletins to cluster manager due to: " + ex, ex);
                }
            }
        }

        private boolean isIllegalXmlChar(final char c) {
            return c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D;
        }

        private boolean containsIllegalXmlChars(final Bulletin bulletin) {
            final String message = bulletin.getMessage();
            for (int i = 0; i < message.length(); i++) {
                final char c = message.charAt(i);
                if (isIllegalXmlChar(c)) {
                    return true;
                }
            }

            return false;
        }

        private String stripIllegalXmlChars(final String value) {
            final StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                sb.append(isIllegalXmlChar(c) ? '?' : c);
            }

            return sb.toString();
        }

        private NodeBulletinsMessage createBulletinsMessage() {
            final Set<Bulletin> nodeBulletins = nodeBulletinSubscriber.get().getBulletins();
            final Set<Bulletin> escapedNodeBulletins = new HashSet<>(nodeBulletins.size());

            // ensure there are some bulletins to report
            if (nodeBulletins.isEmpty()) {
                return null;
            }

            for (final Bulletin bulletin : nodeBulletins) {
                final Bulletin escapedBulletin;
                if (containsIllegalXmlChars(bulletin)) {
                    final String escapedBulletinMessage = stripIllegalXmlChars(bulletin.getMessage());

                    if (bulletin.getGroupId() == null) {
                        escapedBulletin = BulletinFactory.createBulletin(bulletin.getCategory(), bulletin.getLevel(), escapedBulletinMessage);
                    } else {
                        escapedBulletin = BulletinFactory.createBulletin(bulletin.getGroupId(), bulletin.getSourceId(), bulletin.getSourceName(), bulletin.getCategory(), bulletin.getLevel(), escapedBulletinMessage);
                    }
                } else {
                    escapedBulletin = bulletin;
                }

                escapedNodeBulletins.add(escapedBulletin);
            }

            // create the bulletin payload
            final BulletinsPayload payload = new BulletinsPayload();
            payload.setBulletins(escapedNodeBulletins);

            // create bulletin message
            final NodeBulletins bulletins = new NodeBulletins(getNodeId(), payload.marshal());
            final NodeBulletinsMessage message = new NodeBulletinsMessage();
            message.setBulletins(bulletins);

            return message;
        }
    }

    private class HeartbeatSendTask implements Runnable {

        private final NodeProtocolSender protocolSender;
        private final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.US);

        public HeartbeatSendTask(final NodeProtocolSender protocolSender) {
            if (protocolSender == null) {
                throw new IllegalArgumentException("NodeProtocolSender may not be null.");
            }
            this.protocolSender = protocolSender;
        }

        @Override
        public void run() {
            try {
                if (heartbeatsSuspended.get()) {
                    return;
                }

                final HeartbeatMessageGeneratorTask task = heartbeatMessageGeneratorTaskRef.get();
                if (task == null) {
                    return;
                }

                final HeartbeatMessage message = task.getHeartbeatMessage();
                if (message == null) {
                    heartbeatLogger.debug("No heartbeat to send");
                    return;
                }

                final long sendStart = System.nanoTime();
                protocolSender.heartbeat(message);
                final long sendNanos = System.nanoTime() - sendStart;
                final long sendMillis = TimeUnit.NANOSECONDS.toMillis(sendNanos);

                heartbeatLogger.info("Heartbeat created at {} and sent at {}; send took {} millis",
                        dateFormatter.format(new Date(message.getHeartbeat().getCreatedTimestamp())),
                        dateFormatter.format(new Date()),
                        sendMillis);
            } catch (final UnknownServiceAddressException usae) {
                if (heartbeatLogger.isDebugEnabled()) {
                    heartbeatLogger.debug(usae.getMessage());
                }
            } catch (final Throwable ex) {
                heartbeatLogger.warn("Failed to send heartbeat to cluster manager due to: " + ex);
                if (heartbeatLogger.isDebugEnabled()) {
                    heartbeatLogger.warn("", ex);
                }
            }
        }
    }

    private class HeartbeatMessageGeneratorTask implements Runnable {

        private AtomicReference<HeartbeatMessage> heartbeatMessageRef = new AtomicReference<>();

        @Override
        public void run() {
            final HeartbeatMessage heartbeatMessage = createHeartbeatMessage();
            if (heartbeatMessage != null) {
                heartbeatMessageRef.set(heartbeatMessage);
            }
        }

        public HeartbeatMessage getHeartbeatMessage() {
            return heartbeatMessageRef.getAndSet(null);
        }

        private HeartbeatMessage createHeartbeatMessage() {
            try {
                final HeartbeatBean bean = heartbeatBeanRef.get();
                if (bean == null) {
                    return null;
                }

                final ProcessGroupStatus procGroupStatus = getGroupStatus(bean.getRootGroup(), getProcessorStats());
                // create heartbeat payload
                final HeartbeatPayload hbPayload = new HeartbeatPayload();
                hbPayload.setSystemStartTime(systemStartTime);
                hbPayload.setActiveThreadCount(procGroupStatus.getActiveThreadCount());

                final QueueSize queueSize = getTotalFlowFileCount(bean.getRootGroup());
                hbPayload.setTotalFlowFileCount(queueSize.getObjectCount());
                hbPayload.setTotalFlowFileBytes(queueSize.getByteCount());

                hbPayload.setCounters(getCounters());
                hbPayload.setSystemDiagnostics(getSystemDiagnostics());
                hbPayload.setProcessGroupStatus(procGroupStatus);
                hbPayload.setSiteToSitePort(remoteInputSocketPort);
                hbPayload.setSiteToSiteSecure(isSiteToSiteSecure);

                // create heartbeat message
                final Heartbeat heartbeat = new Heartbeat(getNodeId(), bean.isPrimary(), bean.isConnected(), hbPayload.marshal());
                final HeartbeatMessage message = new HeartbeatMessage();
                message.setHeartbeat(heartbeat);

                heartbeatLogger.debug("Generated heartbeat");

                return message;
            } catch (final Throwable ex) {
                LOG.warn("Failed to create heartbeat due to: " + ex, ex);
                return null;
            }
        }
    }

    private void updateRemoteProcessGroups() {
        final List<RemoteProcessGroup> remoteGroups = getGroup(getRootGroupId()).findAllRemoteProcessGroups();
        for (final RemoteProcessGroup remoteGroup : remoteGroups) {
            try {
                remoteGroup.refreshFlowContents();
            } catch (final CommunicationsException | ClientHandlerException e) {
                LOG.warn("Unable to communicate with remote instance {} due to {}", remoteGroup, e.toString());
                if (LOG.isDebugEnabled()) {
                    LOG.warn("", e);
                }
            }
        }
    }

    @Override
    public List<ProvenanceEventRecord> getProvenanceEvents(long firstEventId, int maxRecords) throws IOException {
        return new ArrayList<ProvenanceEventRecord>(provenanceEventRepository.getEvents(firstEventId, maxRecords));
    }

    public void setClusterManagerRemoteSiteInfo(final Integer managerListeningPort, final Boolean commsSecure) {
        writeLock.lock();
        try {
            clusterManagerRemoteSitePort = managerListeningPort;
            clusterManagerRemoteSiteCommsSecure = commsSecure;
        } finally {
            writeLock.unlock();
        }
    }

    public Integer getClusterManagerRemoteSiteListeningPort() {
        readLock.lock();
        try {
            return clusterManagerRemoteSitePort;
        } finally {
            readLock.unlock();
        }
    }

    public Boolean isClusterManagerRemoteSiteCommsSecure() {
        readLock.lock();
        try {
            return clusterManagerRemoteSiteCommsSecure;
        } finally {
            readLock.unlock();
        }
    }

    public Integer getRemoteSiteListeningPort() {
        return remoteInputSocketPort;
    }

    public Boolean isRemoteSiteCommsSecure() {
        return isSiteToSiteSecure;
    }

    public ProcessScheduler getProcessScheduler() {
        return processScheduler;
    }

    @Override
    public Set<String> getControllerServiceIdentifiers(final Class<? extends ControllerService> serviceType) {
        return controllerServiceProvider.getControllerServiceIdentifiers(serviceType);
    }

    @Override
    public ProvenanceEventRepository getProvenanceRepository() {
        return provenanceEventRepository;
    }

    public StatusHistoryDTO getConnectionStatusHistory(final String connectionId) {
        return getConnectionStatusHistory(connectionId, null, null, Integer.MAX_VALUE);
    }

    public StatusHistoryDTO getConnectionStatusHistory(final String connectionId, final Date startTime, final Date endTime, final int preferredDataPoints) {
        return StatusHistoryUtil.createStatusHistoryDTO(componentStatusRepository.getConnectionStatusHistory(connectionId, startTime, endTime, preferredDataPoints));
    }

    public StatusHistoryDTO getProcessorStatusHistory(final String processorId) {
        return getProcessorStatusHistory(processorId, null, null, Integer.MAX_VALUE);
    }

    public StatusHistoryDTO getProcessorStatusHistory(final String processorId, final Date startTime, final Date endTime, final int preferredDataPoints) {
        return StatusHistoryUtil.createStatusHistoryDTO(componentStatusRepository.getProcessorStatusHistory(processorId, startTime, endTime, preferredDataPoints));
    }

    public StatusHistoryDTO getProcessGroupStatusHistory(final String processGroupId) {
        return getProcessGroupStatusHistory(processGroupId, null, null, Integer.MAX_VALUE);
    }

    public StatusHistoryDTO getProcessGroupStatusHistory(final String processGroupId, final Date startTime, final Date endTime, final int preferredDataPoints) {
        return StatusHistoryUtil.createStatusHistoryDTO(componentStatusRepository.getProcessGroupStatusHistory(processGroupId, startTime, endTime, preferredDataPoints));
    }

    public StatusHistoryDTO getRemoteProcessGroupStatusHistory(final String remoteGroupId) {
        return getRemoteProcessGroupStatusHistory(remoteGroupId, null, null, Integer.MAX_VALUE);
    }

    public StatusHistoryDTO getRemoteProcessGroupStatusHistory(final String remoteGroupId, final Date startTime, final Date endTime, final int preferredDataPoints) {
        return StatusHistoryUtil.createStatusHistoryDTO(componentStatusRepository.getRemoteProcessGroupStatusHistory(remoteGroupId, startTime, endTime, preferredDataPoints));
    }

    @Override
    public Collection<FlowFileQueue> getAllQueues() {
        final Collection<Connection> connections = getGroup(getRootGroupId()).findAllConnections();
        final List<FlowFileQueue> queues = new ArrayList<>(connections.size());
        for (final Connection connection : connections) {
            queues.add(connection.getFlowFileQueue());
        }

        return queues;
    }

    private static class HeartbeatBean {

        private final ProcessGroup rootGroup;
        private final boolean primary;
        private final boolean connected;

        public HeartbeatBean(final ProcessGroup rootGroup, final boolean primary, final boolean connected) {
            this.rootGroup = rootGroup;
            this.primary = primary;
            this.connected = connected;
        }

        public ProcessGroup getRootGroup() {
            return rootGroup;
        }

        public boolean isPrimary() {
            return primary;
        }

        public boolean isConnected() {
            return connected;
        }
    }

}
