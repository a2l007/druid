/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.lookup;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Inject;
import com.metamx.emitter.EmittingLogger;
import com.metamx.http.client.HttpClient;
import com.metamx.http.client.Request;
import com.metamx.http.client.response.StatusResponseHandler;
import com.metamx.http.client.response.StatusResponseHolder;
import io.druid.client.coordinator.Coordinator;
import io.druid.client.selector.Server;
import io.druid.concurrent.Execs;
import io.druid.concurrent.LifecycleLock;
import io.druid.curator.discovery.ServerDiscoverySelector;
import io.druid.guice.ManageLifecycle;
import io.druid.guice.annotations.Global;
import io.druid.guice.annotations.Json;
import io.druid.java.util.common.ISE;
import io.druid.java.util.common.StringUtils;
import io.druid.java.util.common.lifecycle.LifecycleStart;
import io.druid.java.util.common.lifecycle.LifecycleStop;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * This class provide a basic {@link LookupExtractorFactory} references manager.
 * It allows basic operations fetching, listing, adding and deleting of {@link LookupExtractor} objects
 * It is be used by queries to fetch the lookup reference.
 * It is used by Lookup configuration manager to add/remove or list lookups configuration via HTTP or other protocols.
 * It does periodic snap shot of the list of lookup in order to bootstrap nodes after restart.
 */
@ManageLifecycle
public class LookupReferencesManager
{
  private static final EmittingLogger LOG = new EmittingLogger(LookupReferencesManager.class);

  // Lookups state (loaded/to-be-loaded/to-be-dropped etc) is managed by immutable LookupUpdateState instance.
  // Any update to state is done by creating updated LookupUpdateState instance and atomically setting that
  // into the ref here.
  // this allows getAllLookupsState() to provide a consistent view without using locks.
  @VisibleForTesting
  final AtomicReference<LookupUpdateState> stateRef = new AtomicReference<>();

  @VisibleForTesting
  final LookupSnapshotTaker lookupSnapshotTaker;

  @VisibleForTesting
  final LifecycleLock lifecycleLock = new LifecycleLock();

  @VisibleForTesting
  Thread mainThread;

  //for unit testing only
  private final boolean testMode;

  private final ServerDiscoverySelector selector;

  private final HttpClient httpClient;

  private final ObjectMapper jsonMapper;

  private static final StatusResponseHandler RESPONSE_HANDLER = new StatusResponseHandler(Charsets.UTF_8);

  private static final TypeReference<Map<String, LookupExtractorFactoryContainer>> LOOKUPS_ALL_REFERENCE =
      new TypeReference<Map<String, LookupExtractorFactoryContainer>>()
      {
      };

  private LookupListeningAnnouncerConfig lookupListeningAnnouncerConfig;

  private LookupConfig lookupConfig;

  @Inject
  public LookupReferencesManager(
      LookupConfig lookupConfig, @Json ObjectMapper objectMapper,
      @Coordinator ServerDiscoverySelector selector, @Global HttpClient client,
      LookupListeningAnnouncerConfig lookupListeningAnnouncerConfig
  )
  {
    this(lookupConfig, objectMapper, selector, client, lookupListeningAnnouncerConfig, false);
  }

  @VisibleForTesting
  LookupReferencesManager(
      LookupConfig lookupConfig, ObjectMapper objectMapper, ServerDiscoverySelector selector, HttpClient client,
      LookupListeningAnnouncerConfig lookupListeningAnnouncerConfig, boolean testMode
  )
  {
    if (Strings.isNullOrEmpty(lookupConfig.getSnapshotWorkingDir())) {
      this.lookupSnapshotTaker = null;
    } else {
      this.lookupSnapshotTaker = new LookupSnapshotTaker(objectMapper, lookupConfig.getSnapshotWorkingDir());
    }
    this.selector = selector;
    this.httpClient = client;
    this.jsonMapper = objectMapper;
    this.lookupListeningAnnouncerConfig = lookupListeningAnnouncerConfig;
    this.lookupConfig = lookupConfig;
    this.testMode = testMode;
  }

  @LifecycleStart
  public void start()
  {
    if (!lifecycleLock.canStart()) {
      throw new ISE("can't start.");
    }
    try {
      LOG.info("LookupReferencesManager is starting.");
      loadAllLookupsAndInitStateRef();
      if (!testMode) {
        mainThread = Execs.makeThread(
            "LookupReferencesManager-MainThread",
            () -> {
              try {
                if (!lifecycleLock.awaitStarted()) {
                  LOG.error("WTF! lifecycle not started, lookup update notices will not be handled.");
                  return;
                }

                while (!Thread.interrupted() && lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS)) {
                  try {
                    handlePendingNotices();
                    LockSupport.parkNanos(LookupReferencesManager.this, TimeUnit.MINUTES.toNanos(1));
                  }
                  catch (Throwable t) {
                    LOG.makeAlert(t, "Error occured while lookup notice handling.").emit();
                  }
                }
              }
              catch (Throwable t) {
                LOG.error(t, "Error while waiting for lifecycle start. lookup updates notices will not be handled");
              }
              finally {
                LOG.info("Lookup Management loop exited, Lookup notices are not handled anymore.");
              }
            },
            true
        );

        mainThread.start();
      }

      LOG.info("LookupReferencesManager is started.");
      lifecycleLock.started();
    }
    finally {
      lifecycleLock.exitStart();
    }
  }

  @VisibleForTesting
  void handlePendingNotices()
  {
    if (stateRef.get().pendingNotices.isEmpty()) {
      return;
    }

    @SuppressWarnings("ArgumentParameterSwap")
    LookupUpdateState swappedState = atomicallyUpdateStateRef(
        oldState -> new LookupUpdateState(oldState.lookupMap, ImmutableList.of(), oldState.pendingNotices)
    );

    Map<String, LookupExtractorFactoryContainer> lookupMap = new HashMap<>(swappedState.lookupMap);
    for (Notice notice : swappedState.noticesBeingHandled) {
      try {
        notice.handle(lookupMap);
      }
      catch (Exception ex) {
        LOG.error(ex, "Exception occured while handling lookup notice [%s].", notice);
        LOG.makeAlert("Exception occured while handling lookup notice, with message [%s].", ex.getMessage()).emit();
      }
    }

    takeSnapshot(lookupMap);

    ImmutableMap<String, LookupExtractorFactoryContainer> immutableLookupMap = ImmutableMap.copyOf(lookupMap);

    atomicallyUpdateStateRef(
        oldState -> new LookupUpdateState(immutableLookupMap, oldState.pendingNotices, ImmutableList.of())
    );
  }

  @LifecycleStop
  public void stop()
  {
    if (!lifecycleLock.canStop()) {
      throw new ISE("can't stop.");
    }

    LOG.info("LookupReferencesManager is stopping.");

    if (!testMode) {
      mainThread.interrupt();

      try {
        mainThread.join();
      }
      catch (InterruptedException ex) {
        throw new ISE("failed to stop, mainThread couldn't finish.");
      }
    }

    for (Map.Entry<String, LookupExtractorFactoryContainer> e : stateRef.get().lookupMap.entrySet()) {
      try {
        LOG.info("Closing lookup [%s]", e.getKey());
        if (!e.getValue().getLookupExtractorFactory().close()) {
          LOG.error("Failed to close lookup [%s].", e.getKey());
        }
      }
      catch (Exception ex) {
        LOG.error(ex, "Failed to close lookup [%s].", e.getKey());
      }
    }

    LOG.info("LookupReferencesManager is stopped.");
  }

  public void add(String lookupName, LookupExtractorFactoryContainer lookupExtractorFactoryContainer)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS));
    addNotice(new LoadNotice(lookupName, lookupExtractorFactoryContainer));
  }

  public void remove(String lookupName)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS));
    addNotice(new DropNotice(lookupName));
  }

  private void addNotice(Notice notice)
  {
    atomicallyUpdateStateRef(
        oldState -> {
          if (oldState.pendingNotices.size() > 10000) { //don't let pendingNotices grow indefinitely
            throw new ISE("There are too many [%d] pendingNotices.", oldState.pendingNotices.size());
          }

          ImmutableList.Builder<Notice> builder = ImmutableList.builder();
          builder.addAll(oldState.pendingNotices);
          builder.add(notice);

          return new LookupUpdateState(
              oldState.lookupMap, builder.build(), oldState.noticesBeingHandled

          );
        }
    );
    LockSupport.unpark(mainThread);
  }

  @Nullable
  public LookupExtractorFactoryContainer get(String lookupName)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS));
    return stateRef.get().lookupMap.get(lookupName);
  }

  // Note that this should ensure that "toLoad" and "toDrop" are disjoint.
  public LookupsState<LookupExtractorFactoryContainer> getAllLookupsState()
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS));

    LookupUpdateState lookupUpdateState = stateRef.get();

    Map<String, LookupExtractorFactoryContainer> lookupsToLoad = new HashMap<>();
    Set<String> lookupsToDrop = new HashSet<>();

    updateToLoadAndDrop(lookupUpdateState.noticesBeingHandled, lookupsToLoad, lookupsToDrop);
    updateToLoadAndDrop(lookupUpdateState.pendingNotices, lookupsToLoad, lookupsToDrop);

    return new LookupsState<>(lookupUpdateState.lookupMap, lookupsToLoad, lookupsToDrop);
  }

  private void updateToLoadAndDrop(
      List<Notice> notices,
      Map<String, LookupExtractorFactoryContainer> lookupsToLoad,
      Set<String> lookupsToDrop
  )
  {
    for (Notice notice : notices) {
      if (notice instanceof LoadNotice) {
        LoadNotice loadNotice = (LoadNotice) notice;
        lookupsToLoad.put(loadNotice.lookupName, loadNotice.lookupExtractorFactoryContainer);
        lookupsToDrop.remove(loadNotice.lookupName);
      } else if (notice instanceof DropNotice) {
        DropNotice dropNotice = (DropNotice) notice;
        lookupsToDrop.add(dropNotice.lookupName);
        lookupsToLoad.remove(dropNotice.lookupName);
      } else {
        throw new ISE("Unknown Notice type [%s].", notice.getClass().getName());
      }
    }
  }

  private void takeSnapshot(Map<String, LookupExtractorFactoryContainer> lookupMap)
  {
    if (lookupSnapshotTaker != null) {
      List<LookupBean> lookups = new ArrayList<>(lookupMap.size());
      for (Map.Entry<String, LookupExtractorFactoryContainer> e : lookupMap.entrySet()) {
        lookups.add(new LookupBean(e.getKey(), null, e.getValue()));
      }

      lookupSnapshotTaker.takeSnapshot(lookups);
    }
  }

  private void loadAllLookupsAndInitStateRef()
  {
    if (!lookupConfig.getDisableLookupSync()) {
      String tier = lookupListeningAnnouncerConfig.getLookupTier();
      List<LookupBean> lookupBeanList = new ArrayList<>();
      // Check if the coordinator is accessible
      if (getCoordinatorUrl().isEmpty()) {
        if (lookupSnapshotTaker != null) {
          LOG.info("Coordinator is unavailable. Loading saved snapshot instead");
          lookupBeanList = getLookupListFromSnapshot();
        }
      } else {
        lookupBeanList = getLookupListFromCoordinator(tier);
        if (lookupBeanList == null) {
          LOG.info("Lookups not accessible from Coordinator. Loading saved snapshot instead");
          lookupBeanList = getLookupListFromSnapshot();
        }
      }
      if (!lookupBeanList.isEmpty()) {
        ImmutableMap.Builder<String, LookupExtractorFactoryContainer> builder = ImmutableMap.builder();
        ListeningScheduledExecutorService executorService = MoreExecutors.listeningDecorator(
            Executors.newScheduledThreadPool(
                lookupConfig.getNumLookupLoadingThreads(),
                Execs.makeThreadFactory("LookupReferencesManager--%s")
            )
        );
        try {
          List<ListenableFuture<Map.Entry>> futures = new ArrayList<>();
          LOG.info("Starting lookup loading process");
          for (LookupBean lookupBean : lookupBeanList) {
            futures.add(
                executorService.submit(
                    () -> {

                      LookupExtractorFactoryContainer container = lookupBean.getContainer();
                      if (container.getLookupExtractorFactory().start()) {
                        LOG.info(
                            "Started lookup [%s]:[%s]",
                            lookupBean.getName(),
                            container
                        );
                        return new AbstractMap.SimpleImmutableEntry<>(lookupBean.getName(), container);
                      } else {
                        LOG.error(
                            "Failed to start lookup [%s]:[%s]",
                            lookupBean.getName(),
                            container
                        );
                        return null;
                      }
                    }
                )
            );
          }
          final ListenableFuture<List<Map.Entry>> futureList = Futures.allAsList(futures);
          try {
            futureList.get()
                      .stream()
                      .filter(Objects::nonNull)
                      .forEach(builder::put);
          }
          catch (Exception ex) {
            futureList.cancel(true);
            throw ex;
          }
          stateRef.set(new LookupUpdateState(builder.build(), ImmutableList.of(), ImmutableList.of()));
        }
        catch (Exception e) {
          LOG.error(e, "Failed to finish lookup load process.");
        }
        executorService.shutdownNow();
      } else {
        LOG.info("No lookups to be loaded at this point");
        stateRef.set(new LookupUpdateState(ImmutableMap.of(), ImmutableList.of(), ImmutableList.of()));
      }
    } else {
      LOG.info("Lookup synchronization is disabled");
      stateRef.set(new LookupUpdateState(ImmutableMap.of(), ImmutableList.of(), ImmutableList.of()));
    }
  }

  private List<LookupBean> getLookupListFromCoordinator(String tier)
  {
    try {
      final StatusResponseHolder response = fetchLookupsForTier(tier);
      List<LookupBean> lookupBeanList = new ArrayList<>();
      if (!response.getStatus().equals(HttpResponseStatus.OK)) {
        LOG.error(
            "Error while fetching lookup code from Coordinator with status[%s] and content[%s]",
            response.getStatus(),
            response.getContent()
        );
        if (lookupSnapshotTaker != null) {
          LOG.info("Attempting to load saved snapshot");
          return null;
        }
      } else {
        // Older version of getSpecificTier returns a list of lookup names.
        // Lookup loading is performed via snapshot if older version is present.
        if (response.getContent().startsWith("[")) {
          return null;
        } else {
          Map<String, LookupExtractorFactoryContainer> lookupMap = jsonMapper.readValue(
              response.getContent(),
              LOOKUPS_ALL_REFERENCE
          );
          if (!lookupMap.isEmpty()) {
            for (Map.Entry<String, LookupExtractorFactoryContainer> e : lookupMap.entrySet()) {
              lookupBeanList.add(new LookupBean(e.getKey(), null, e.getValue()));
            }
          }
        }
      }
      return lookupBeanList;
    }
    catch (Exception e) {
      LOG.error(e, "Error while parsing lookup code for tier [%s] from response", tier);
      return null;
    }
  }

  private List<LookupBean> getLookupListFromSnapshot()
  {
    return lookupSnapshotTaker.pullExistingSnapshot();
  }

  private String getCoordinatorUrl()
  {
    try {
      final Server instance = selector.pick();
      if (instance == null) {
        LOG.info("Coordinator instance unavailable.");
        return "";
      }
      return new URI(
          instance.getScheme(),
          null,
          instance.getAddress(),
          instance.getPort(),
          "/druid/coordinator/v1",
          null,
          null
      ).toString();
    }
    catch (Exception e) {
      LOG.error("Error encountered while connecting to Coordinator");
    }
    return "";
  }

  private LookupUpdateState atomicallyUpdateStateRef(Function<LookupUpdateState, LookupUpdateState> fn)
  {
    while (true) {
      LookupUpdateState old = stateRef.get();
      LookupUpdateState newState = fn.apply(old);
      if (stateRef.compareAndSet(old, newState)) {
        return newState;
      }
    }
  }

  private StatusResponseHolder fetchLookupsForTier(String tier)
      throws ExecutionException, InterruptedException, MalformedURLException
  {
    return httpClient.go(
        new Request(
            HttpMethod.GET,
            new URL(
                StringUtils.format(
                    "%s/lookups/%s?detailed=true",
                    getCoordinatorUrl(),
                    tier
                )
            )
        ),
        RESPONSE_HANDLER
    ).get();
  }

  private LookupExtractorFactoryContainer getLookupEntryFromCode(String tier, String lookupCode)
  {
    final StatusResponseHolder lookupTierResponse;
    try {
      lookupTierResponse = httpClient.go(
          new Request(
              HttpMethod.GET,
              new URL(
                  StringUtils.format(
                      "%s/lookups/%s/%s",
                      getCoordinatorUrl(),
                      tier,
                      lookupCode
                  )
              )
          ),
          RESPONSE_HANDLER
      ).get();
      if (!lookupTierResponse.getStatus().equals(HttpResponseStatus.OK)) {
        throw new ISE(
            "Error while fetching lookup entries from Coordinator with status[%s] and content[%s]",
            lookupTierResponse.getStatus(),
            lookupTierResponse.getContent()
        );
      } else {
        final LookupExtractorFactoryContainer lookupData = jsonMapper.readValue(
            lookupTierResponse.getContent(),
            LookupExtractorFactoryContainer.class
        );
        return lookupData;
      }
    }
    catch (Exception ioe) {
      throw new ISE("Error while parsing lookup entries from response "
      );
    }
  }

  @VisibleForTesting
  interface Notice
  {
    void handle(Map<String, LookupExtractorFactoryContainer> lookupMap);
  }

  private static class LoadNotice implements Notice
  {
    private final String lookupName;
    private final LookupExtractorFactoryContainer lookupExtractorFactoryContainer;

    public LoadNotice(String lookupName, LookupExtractorFactoryContainer lookupExtractorFactoryContainer)
    {
      this.lookupName = lookupName;
      this.lookupExtractorFactoryContainer = lookupExtractorFactoryContainer;
    }

    @Override
    public void handle(Map<String, LookupExtractorFactoryContainer> lookupMap)
    {
      LookupExtractorFactoryContainer old = lookupMap.get(lookupName);
      if (old != null && !lookupExtractorFactoryContainer.replaces(old)) {
        LOG.warn(
            "got notice to load lookup [%s] that can't replace existing [%s].",
            lookupExtractorFactoryContainer,
            old
        );
        return;
      }

      if (!lookupExtractorFactoryContainer.getLookupExtractorFactory().start()) {
        throw new ISE(
            "start method returned false for lookup [%s]:[%s]",
            lookupName,
            lookupExtractorFactoryContainer
        );
      }

      old = lookupMap.put(lookupName, lookupExtractorFactoryContainer);

      LOG.debug("Loaded lookup [%s] with spec [%s].", lookupName, lookupExtractorFactoryContainer);

      if (old != null) {
        if (!old.getLookupExtractorFactory().close()) {
          throw new ISE("close method returned false for lookup [%s]:[%s]", lookupName, old);
        }
      }
    }

    @Override
    public String toString()
    {
      return "LoadNotice{" +
             "lookupName='" + lookupName + '\'' +
             ", lookupExtractorFactoryContainer=" + lookupExtractorFactoryContainer +
             '}';
    }
  }

  private static class DropNotice implements Notice
  {
    private final String lookupName;

    public DropNotice(String lookupName)
    {
      this.lookupName = lookupName;
    }

    @Override
    public void handle(Map<String, LookupExtractorFactoryContainer> lookupMap)
    {
      final LookupExtractorFactoryContainer lookupExtractorFactoryContainer = lookupMap.remove(lookupName);

      if (lookupExtractorFactoryContainer != null) {
        LOG.debug("Removed lookup [%s] with spec [%s].", lookupName, lookupExtractorFactoryContainer);

        if (!lookupExtractorFactoryContainer.getLookupExtractorFactory().close()) {
          throw new ISE(
              "close method returned false for lookup [%s]:[%s]",
              lookupName,
              lookupExtractorFactoryContainer
          );
        }
      }
    }

    @Override
    public String toString()
    {
      return "DropNotice{" +
             "lookupName='" + lookupName + '\'' +
             '}';
    }
  }

  private static class LookupUpdateState
  {
    private final ImmutableMap<String, LookupExtractorFactoryContainer> lookupMap;
    private final ImmutableList<Notice> pendingNotices;
    private final ImmutableList<Notice> noticesBeingHandled;

    LookupUpdateState(
        ImmutableMap<String, LookupExtractorFactoryContainer> lookupMap,
        ImmutableList<Notice> pendingNotices,
        ImmutableList<Notice> noticesBeingHandled
    )
    {
      this.lookupMap = lookupMap;
      this.pendingNotices = pendingNotices;
      this.noticesBeingHandled = noticesBeingHandled;
    }
  }
}
