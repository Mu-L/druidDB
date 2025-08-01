/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.tests.indexer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import org.apache.druid.indexing.overlord.supervisor.SupervisorStateManager;
import org.apache.druid.indexing.seekablestream.supervisor.LagAggregator;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.segment.incremental.RowIngestionMetersTotals;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.clients.TaskResponseObject;
import org.apache.druid.testing.utils.DruidClusterAdminClient;
import org.apache.druid.testing.utils.EventSerializer;
import org.apache.druid.testing.utils.ITRetryUtil;
import org.apache.druid.testing.utils.JsonEventSerializer;
import org.apache.druid.testing.utils.StreamAdminClient;
import org.apache.druid.testing.utils.StreamEventWriter;
import org.apache.druid.testing.utils.StreamGenerator;
import org.apache.druid.testing.utils.WikipediaStreamEventStreamGenerator;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.testng.Assert;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractStreamIndexingTest extends AbstractIndexerTest
{
  static final DateTime FIRST_EVENT_TIME = DateTimes.of(1994, 4, 29, 1, 0);
  // format for the querying interval
  static final DateTimeFormatter INTERVAL_FMT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:'00Z'");
  // format for the expected timestamp in a query response
  static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.000Z'");
  static final int EVENTS_PER_SECOND = 6;
  static final int TOTAL_NUMBER_OF_SECOND = 10;

  private static final Logger LOG = new Logger(AbstractStreamIndexingTest.class);
  // Since this integration test can be terminated or be killed un-expectedly, this tag is added to all streams created
  // to help make stream clean up easier. (Normally, streams should be cleanup automattically by the teardown method)
  // The value to this tag is a timestamp that can be used by a lambda function to remove unused stream.
  private static final String STREAM_EXPIRE_TAG = "druid-ci-expire-after";
  private static final int STREAM_SHARD_COUNT = 2;
  protected static final long CYCLE_PADDING_MS = 100;
  private static final int LONG_DURATION_SUPERVISOR_MILLIS = 600 * 1000;

  private static final String QUERIES_FILE = "/stream/queries/stream_index_queries.json";
  private static final String SUPERVISOR_SPEC_TEMPLATE_FILE = "supervisor_spec_template.json";
  private static final String SUPERVISOR_WITH_AUTOSCALER_SPEC_TEMPLATE_FILE = "supervisor_with_autoscaler_spec_template.json";
  private static final String SUPERVISOR_WITH_IDLE_BEHAVIOUR_ENABLED_SPEC_TEMPLATE_FILE =
      "supervisor_with_idle_behaviour_enabled_spec_template.json";

  private static final String SUPERVISOR_LONG_DURATION_TEMPLATE_FILE =
      "supervisor_with_long_duration.json";

  protected static final String DATA_RESOURCE_ROOT = "/stream/data";
  protected static final String SUPERVISOR_SPEC_TEMPLATE_PATH =
      String.join("/", DATA_RESOURCE_ROOT, SUPERVISOR_SPEC_TEMPLATE_FILE);
  protected static final String SUPERVISOR_WITH_AUTOSCALER_SPEC_TEMPLATE_PATH =
          String.join("/", DATA_RESOURCE_ROOT, SUPERVISOR_WITH_AUTOSCALER_SPEC_TEMPLATE_FILE);
  protected static final String SUPERVISOR_WITH_IDLE_BEHAVIOUR_ENABLED_SPEC_TEMPLATE_PATH =
      String.join("/", DATA_RESOURCE_ROOT, SUPERVISOR_WITH_IDLE_BEHAVIOUR_ENABLED_SPEC_TEMPLATE_FILE);

  protected static final String SUPERVISOR_WITH_LONG_DURATION_TEMPLATE_PATH =
      String.join("/", DATA_RESOURCE_ROOT, SUPERVISOR_LONG_DURATION_TEMPLATE_FILE);

  protected static final String SERIALIZER_SPEC_DIR = "serializer";
  protected static final String INPUT_FORMAT_SPEC_DIR = "input_format";
  protected static final String INPUT_ROW_PARSER_SPEC_DIR = "parser";

  protected static final String SERIALIZER = "serializer";
  protected static final String INPUT_FORMAT = "inputFormat";
  protected static final String INPUT_ROW_PARSER = "parser";

  protected static final String JSON_INPUT_FORMAT_PATH =
      String.join("/", DATA_RESOURCE_ROOT, "json", INPUT_FORMAT_SPEC_DIR, "input_format.json");

  protected static final List<String> DEFAULT_DIMENSIONS = ImmutableList.of(
      "page",
      "language",
      "user",
      "unpatrolled",
      "newPage",
      "robot",
      "anonymous",
      "namespace",
      "continent",
      "country",
      "region",
      "city"
  );

  @Inject
  private DruidClusterAdminClient druidClusterAdminClient;

  private StreamAdminClient streamAdminClient;

  abstract StreamAdminClient createStreamAdminClient(IntegrationTestingConfig config) throws Exception;

  /**
   * Create an event writer for an underlying stream. {@code transactionEnabled} should not be null if the stream
   * supports transactions. It is ignored otherwise.
   */
  abstract StreamEventWriter createStreamEventWriter(
      IntegrationTestingConfig config,
      @Nullable Boolean transactionEnabled
  ) throws Exception;

  abstract Function<String, String> generateStreamIngestionPropsTransform(
      String supervisorId,
      String streamName,
      String fullDatasourceName,
      String parserType,
      String parserOrInputFormat,
      List<String> dimensions,
      Map<String, Object> context,
      LagAggregator lagAggregator,
      IntegrationTestingConfig config
  );

  abstract Function<String, String> generateStreamQueryPropsTransform(String streamName, String fullDatasourceName);

  public abstract String getTestNamePrefix();

  protected void doBeforeClass() throws Exception
  {
    streamAdminClient = createStreamAdminClient(config);
  }

  private static String getOnlyResourcePath(String resourceRoot) throws IOException
  {
    return String.join("/", resourceRoot, Iterables.getOnlyElement(listResources(resourceRoot)));
  }

  protected static List<String> listDataFormatResources() throws IOException
  {
    return listResources(DATA_RESOURCE_ROOT)
        .stream()
        .filter(resource -> new File(DATA_RESOURCE_ROOT, resource).isDirectory()) // include only subdirs
        .collect(Collectors.toList());
  }

  /**
   * Returns a map of key to path to spec. The returned map contains at least 2 specs and one of them
   * should be a {@link #SERIALIZER} spec.
   */
  protected static Map<String, String> findTestSpecs(String resourceRoot) throws IOException
  {
    final List<String> specDirs = listResources(resourceRoot);
    final Map<String, String> map = new HashMap<>();
    for (String eachSpec : specDirs) {
      if (SERIALIZER_SPEC_DIR.equals(eachSpec)) {
        map.put(SERIALIZER, getOnlyResourcePath(String.join("/", resourceRoot, SERIALIZER_SPEC_DIR)));
      } else if (INPUT_ROW_PARSER_SPEC_DIR.equals(eachSpec)) {
        map.put(INPUT_ROW_PARSER, getOnlyResourcePath(String.join("/", resourceRoot, INPUT_ROW_PARSER_SPEC_DIR)));
      } else if (INPUT_FORMAT_SPEC_DIR.equals(eachSpec)) {
        map.put(INPUT_FORMAT, getOnlyResourcePath(String.join("/", resourceRoot, INPUT_FORMAT_SPEC_DIR)));
      }
    }
    if (!map.containsKey(SERIALIZER_SPEC_DIR)) {
      throw new IAE("Failed to find serializer spec under [%s]. Found resources are %s", resourceRoot, map);
    }
    if (map.size() == 1) {
      throw new IAE("Failed to find input format or parser spec under [%s]. Found resources are %s", resourceRoot, map);
    }
    return map;
  }

  protected Closeable createResourceCloser(GeneratedTestConfig generatedTestConfig)
  {
    return Closer.create().register(() -> doMethodTeardown(generatedTestConfig));
  }

  protected void doTestIndexDataStableState(
      @Nullable Boolean transactionEnabled,
      String serializerPath,
      String parserType,
      String specPath
  ) throws Exception
  {
    final EventSerializer serializer = jsonMapper.readValue(getResourceAsStream(serializerPath), EventSerializer.class);
    final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
        serializer,
        EVENTS_PER_SECOND,
        CYCLE_PADDING_MS
    );
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(parserType, getResourceAsString(specPath));
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      // Start data generator
      final long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          TOTAL_NUMBER_OF_SECOND,
          FIRST_EVENT_TIME
      );
      verifyIngestedData(generatedTestConfig, numWritten);
    }
  }

  protected void doTestIndexDataHandoffEarly(
      @Nullable Boolean transactionEnabled
  ) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
          .apply(getResourceAsString(SUPERVISOR_WITH_LONG_DURATION_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");

      // Start generating half of the data
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 2;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
          new JsonEventSerializer(jsonMapper),
          EVENTS_PER_SECOND,
          CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateFirstRound,
          FIRST_EVENT_TIME
      );

      // Make sure we consume the data written
      long numWrittenHalf = numWritten;
      ITRetryUtil.retryUntilTrue(
          () ->
              numWrittenHalf == this.queryHelper.countRows(
                  generatedTestConfig.getFullDatasourceName(),
                  Intervals.ETERNITY,
                  name -> new LongSumAggregatorFactory(name, "count")
              ),
          StringUtils.format(
              "dataSource[%s] consumed [%,d] events, expected [%,d]",
              generatedTestConfig.getFullDatasourceName(),
              this.queryHelper.countRows(
                  generatedTestConfig.getFullDatasourceName(),
                  Intervals.ETERNITY,
                  name -> new LongSumAggregatorFactory(name, "count")
              ),
              numWritten
          )
      );

      // Trigger early handoff
      StatusResponseHolder response = indexer.handoffTaskGroupEarly(
          generatedTestConfig.getFullDatasourceName(),
          jsonMapper.writeValueAsString(
              ImmutableMap.of(
                  "taskGroupIds", ImmutableList.of(0)
              )
          )
      );
      Assert.assertEquals(response.getStatus().getCode(), 200);

      // Load the rest of the data
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateRemaining,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );

      // Make sure we consume the rest of the data
      long numWrittenAll = numWritten;
      ITRetryUtil.retryUntilTrue(
          () ->
              numWrittenAll == this.queryHelper.countRows(
                  generatedTestConfig.getFullDatasourceName(),
                  Intervals.ETERNITY,
                  name -> new LongSumAggregatorFactory(name, "count")
              ),
          StringUtils.format(
              "dataSource[%s] consumed [%,d] events, expected [%,d]",
              generatedTestConfig.getFullDatasourceName(),
              this.queryHelper.countRows(
                  generatedTestConfig.getFullDatasourceName(),
                  Intervals.ETERNITY,
                  name -> new LongSumAggregatorFactory(name, "count")
              ),
              numWritten
          )
      );

      // Wait for the early handoff task to complete and cheeck its duration
      ITRetryUtil.retryUntilTrue(
          () -> (!indexer.getCompleteTasksForDataSource(generatedTestConfig.getFullDatasourceName()).isEmpty()),
          "Waiting for Task Completion"
      );

      List<TaskResponseObject> completedTasks = indexer.getCompleteTasksForDataSource(generatedTestConfig.getFullDatasourceName());
      Assert.assertEquals(completedTasks.stream().filter(taskResponseObject -> taskResponseObject.getDuration() < LONG_DURATION_SUPERVISOR_MILLIS).count(), 1);
    }
  }

  void doTestIndexDataWithLosingCoordinator(@Nullable Boolean transactionEnabled) throws Exception
  {
    testIndexWithLosingNodeHelper(
        () -> druidClusterAdminClient.restartCoordinatorContainer(),
        () -> druidClusterAdminClient.waitUntilCoordinatorReady(),
        transactionEnabled
    );
  }

  void doTestIndexDataWithLosingOverlord(@Nullable Boolean transactionEnabled) throws Exception
  {
    testIndexWithLosingNodeHelper(
        () -> druidClusterAdminClient.restartOverlordContainer(),
        () -> druidClusterAdminClient.waitUntilIndexerReady(),
        transactionEnabled
    );
  }

  void doTestIndexDataWithLosingHistorical(@Nullable Boolean transactionEnabled) throws Exception
  {
    testIndexWithLosingNodeHelper(
        () -> druidClusterAdminClient.restartHistoricalContainer(),
        () -> druidClusterAdminClient.waitUntilHistoricalReady(),
        transactionEnabled
    );
  }

  protected void doTestIndexDataWithStartStopSupervisor(@Nullable Boolean transactionEnabled) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      // Start generating half of the data
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 2;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
          new JsonEventSerializer(jsonMapper),
          EVENTS_PER_SECOND,
          CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateFirstRound,
          FIRST_EVENT_TIME
      );
      // Verify supervisor is healthy before suspension
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Suspend the supervisor
      indexer.suspendSupervisor(generatedTestConfig.getSupervisorId());
      // Start generating remainning half of the data
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateRemaining,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );
      // Resume the supervisor
      indexer.resumeSupervisor(generatedTestConfig.getSupervisorId());
      // Verify supervisor is healthy after suspension
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Verify that supervisor can catch up with the stream
      verifyIngestedData(generatedTestConfig, numWritten);
    }
  }

  protected void doTestIndexDataWithAutoscaler(@Nullable Boolean transactionEnabled) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
            INPUT_FORMAT,
            getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
            final Closeable closer = createResourceCloser(generatedTestConfig);
            final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
              .apply(getResourceAsString(SUPERVISOR_WITH_AUTOSCALER_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      String dataSource = generatedTestConfig.getFullDatasourceName();
      // Start generating half of the data
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 2;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
              new JsonEventSerializer(jsonMapper),
              EVENTS_PER_SECOND,
              CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
              generatedTestConfig.getStreamName(),
              streamEventWriter,
              secondsToGenerateFirstRound,
              FIRST_EVENT_TIME
      );
      // Verify supervisor is healthy before suspension
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
              true,
              10000,
              30,
              "Waiting for supervisor to be healthy"
      );

      // wait for autoScaling task numbers from 1 to 2.
      ITRetryUtil.retryUntil(
          () -> indexer.getRunningTasks()
                       .stream()
                       .filter(taskResponseObject -> taskResponseObject.getId().contains(dataSource))
                       .count() == 2,
              true,
              10000,
              50,
              "waiting for autoScaling task numbers from 1 to 2"
      );

      // Start generating remainning half of the data
      numWritten += streamGenerator.run(
              generatedTestConfig.getStreamName(),
              streamEventWriter,
              secondsToGenerateRemaining,
              FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );

      // Verify that supervisor can catch up with the stream
      verifyIngestedData(generatedTestConfig, numWritten);
    }
  }

  protected void doTestIndexDataWithIdleConfigEnabled(@Nullable Boolean transactionEnabled) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_WITH_IDLE_BEHAVIOUR_ENABLED_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      String dataSource = generatedTestConfig.getFullDatasourceName();
      // Start generating half of the data
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 2;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
          new JsonEventSerializer(jsonMapper),
          EVENTS_PER_SECOND,
          CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateFirstRound,
          FIRST_EVENT_TIME
      );
      // Verify supervisor is healthy before suspension
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );

      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.IDLE.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be idle"
      );

      // wait for no more creation of indexing tasks.
      ITRetryUtil.retryUntil(
          () -> indexer.getRunningTasks()
                       .stream()
                       .noneMatch(taskResponseObject -> taskResponseObject.getId().contains(dataSource)),
          true,
          10000,
          50,
          "wait for no more creation of indexing tasks"
      );

      indexer.shutdownSupervisor(generatedTestConfig.getSupervisorId());
      indexer.submitSupervisor(taskSpec);

      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.IDLE.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be idle"
      );
      ITRetryUtil.retryUntil(
          () -> indexer.getRunningTasks()
                       .stream()
                       .noneMatch(taskResponseObject -> taskResponseObject.getId().contains(dataSource)),
          true,
          1000,
          10,
          "wait for no more creation of indexing tasks"
      );

      // Start generating remainning half of the data
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateRemaining,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );

      // Verify that supervisor can catch up with the stream
      verifyIngestedData(generatedTestConfig, numWritten);
    }
  }

  protected void doTestTerminatedSupervisorAutoCleanup(@Nullable Boolean transactionEnabled) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig1 = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    final GeneratedTestConfig generatedTestConfig2 = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer1 = createResourceCloser(generatedTestConfig1);
        final Closeable closer2 = createResourceCloser(generatedTestConfig2);
    ) {
      final String taskSpec1 = generatedTestConfig1.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec1: [%s]\n", taskSpec1);
      final String taskSpec2 = generatedTestConfig2.getStreamIngestionPropsTransform()
                                                   .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec2: [%s]\n", taskSpec2);
      // Start both supervisors
      generatedTestConfig1.setSupervisorId(indexer.submitSupervisor(taskSpec1));
      generatedTestConfig2.setSupervisorId(indexer.submitSupervisor(taskSpec2));
      LOG.info("Submitted supervisors");

      // Sleep for 10 secs to make sure that at least one cycle of supervisor auto cleanup duty ran
      Thread.sleep(10000);

      // Verify that supervisor specs exist
      List<Object> specs1 = indexer.getSupervisorHistory(generatedTestConfig1.getSupervisorId());
      Assert.assertNotNull(specs1);
      Assert.assertFalse(specs1.isEmpty());

      List<Object> specs2 = indexer.getSupervisorHistory(generatedTestConfig2.getSupervisorId());
      Assert.assertNotNull(specs2);
      Assert.assertFalse(specs2.isEmpty());

      // Supervisor 1 should still be active while supervisor 2 is now terminated
      LOG.info("Terminating supervisor 2");
      indexer.terminateSupervisor(generatedTestConfig2.getSupervisorId());

      // Verify that auto cleanup eventually removes supervisor spec after termination
      ITRetryUtil.retryUntil(
          () -> {
              try {
                indexer.getSupervisorHistory(generatedTestConfig2.getSupervisorId());
                LOG.warn("Supervisor history should not exist");
                return false;
              }
              catch (ISE e) {
                if (e.getMessage().contains("Not Found")) {
                  return true;
                }
                throw e;
              }
          },
          true,
          10000,
          30,
          "Waiting for supervisor spec 2 to be auto clean"
      );
      // Verify that supervisor 1 spec was not remove
      specs1 = indexer.getSupervisorHistory(generatedTestConfig1.getSupervisorId());
      Assert.assertNotNull(specs1);
      Assert.assertFalse(specs1.isEmpty());
    }
  }

  protected void doTestIndexDataWithStreamReshardSplit(@Nullable Boolean transactionEnabled) throws Exception
  {
    // Reshard the stream from STREAM_SHARD_COUNT to STREAM_SHARD_COUNT * 2
    testIndexWithStreamReshardHelper(transactionEnabled, STREAM_SHARD_COUNT * 2);
  }

  protected void doTestIndexDataWithStreamReshardMerge() throws Exception
  {
    // Reshard the stream from STREAM_SHARD_COUNT to STREAM_SHARD_COUNT / 2
    testIndexWithStreamReshardHelper(null, STREAM_SHARD_COUNT / 2);
  }

  private void testIndexWithLosingNodeHelper(
      Runnable restartRunnable,
      Runnable waitForReadyRunnable,
      @Nullable Boolean transactionEnabled
  ) throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      // Start generating one third of the data (before restarting)
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 3;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
          new JsonEventSerializer(jsonMapper),
          EVENTS_PER_SECOND,
          CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateFirstRound,
          FIRST_EVENT_TIME
      );
      // Verify supervisor is healthy before restart
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Restart Druid process
      LOG.info("Restarting Druid process");
      restartRunnable.run();
      LOG.info("Restarted Druid process");
      // Start generating one third of the data (while restarting)
      int secondsToGenerateSecondRound = TOTAL_NUMBER_OF_SECOND / 3;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateSecondRound;
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateSecondRound,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );
      // Wait for Druid process to be available
      LOG.info("Waiting for Druid process to be available");
      waitForReadyRunnable.run();
      LOG.info("Druid process is now available");
      // Start generating remaining data (after restarting)
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateRemaining,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound + secondsToGenerateSecondRound)
      );
      // Verify supervisor is healthy
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Verify that supervisor ingested all data
      verifyIngestedData(generatedTestConfig, numWritten);
    }
  }

  private void testIndexWithStreamReshardHelper(@Nullable Boolean transactionEnabled, int newShardCount)
      throws Exception
  {
    final GeneratedTestConfig generatedTestConfig = new GeneratedTestConfig(
        INPUT_FORMAT,
        getResourceAsString(JSON_INPUT_FORMAT_PATH)
    );
    try (
        final Closeable closer = createResourceCloser(generatedTestConfig);
        final StreamEventWriter streamEventWriter = createStreamEventWriter(config, transactionEnabled)
    ) {
      final String taskSpec = generatedTestConfig.getStreamIngestionPropsTransform()
                                                 .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
      LOG.info("supervisorSpec: [%s]\n", taskSpec);
      // Start supervisor
      generatedTestConfig.setSupervisorId(indexer.submitSupervisor(taskSpec));
      LOG.info("Submitted supervisor");
      // Start generating one third of the data (before resharding)
      int secondsToGenerateRemaining = TOTAL_NUMBER_OF_SECOND;
      int secondsToGenerateFirstRound = TOTAL_NUMBER_OF_SECOND / 3;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateFirstRound;
      final StreamGenerator streamGenerator = new WikipediaStreamEventStreamGenerator(
          new JsonEventSerializer(jsonMapper),
          EVENTS_PER_SECOND,
          CYCLE_PADDING_MS
      );
      long numWritten = streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateFirstRound,
          FIRST_EVENT_TIME
      );
      // Verify supervisor is healthy before resahrding
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Reshard the supervisor by split from STREAM_SHARD_COUNT to newShardCount and waits until the resharding starts
      streamAdminClient.updatePartitionCount(generatedTestConfig.getStreamName(), newShardCount, true);
      // Start generating one third of the data (while resharding)
      int secondsToGenerateSecondRound = TOTAL_NUMBER_OF_SECOND / 3;
      secondsToGenerateRemaining = secondsToGenerateRemaining - secondsToGenerateSecondRound;
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateSecondRound,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound)
      );
      // Wait for stream to finish resharding
      ITRetryUtil.retryUntil(
          () -> streamAdminClient.isStreamActive(generatedTestConfig.getStreamName()),
          true,
          10000,
          30,
          "Waiting for stream to finish resharding"
      );
      ITRetryUtil.retryUntil(
          () -> streamAdminClient.verfiyPartitionCountUpdated(
              generatedTestConfig.getStreamName(),
              STREAM_SHARD_COUNT,
              newShardCount
          ),
          true,
          10000,
          30,
          "Waiting for stream to finish resharding"
      );
      // Start generating remaining data (after resharding)
      numWritten += streamGenerator.run(
          generatedTestConfig.getStreamName(),
          streamEventWriter,
          secondsToGenerateRemaining,
          FIRST_EVENT_TIME.plusSeconds(secondsToGenerateFirstRound + secondsToGenerateSecondRound)
      );
      // Verify supervisor is healthy after resahrding
      ITRetryUtil.retryUntil(
          () -> SupervisorStateManager.BasicState.RUNNING.equals(indexer.getSupervisorStatus(generatedTestConfig.getSupervisorId())),
          true,
          10000,
          30,
          "Waiting for supervisor to be healthy"
      );
      // Verify that supervisor can catch up with the stream
      verifyIngestedData(generatedTestConfig, numWritten);
    }
    // Verify that event thrown away count was not incremented by the reshard
    List<TaskResponseObject> completedTasks = indexer.getCompleteTasksForDataSource(generatedTestConfig.getFullDatasourceName());
    for (TaskResponseObject task : completedTasks) {
      try {
        RowIngestionMetersTotals stats = indexer.getTaskStats(task.getId());
        Assert.assertEquals(0L, stats.getThrownAway());
      }
      catch (Exception e) {
        // Failed task may not have a task stats report. We can ignore it as the task did not consume any data
        if (!task.getStatus().isFailure()) {
          throw e;
        }
      }
    }
  }

  protected void verifyIngestedData(GeneratedTestConfig generatedTestConfig, long numWritten) throws Exception
  {
    // Wait for supervisor to consume events
    LOG.info("Waiting for stream indexing tasks to consume events");

    ITRetryUtil.retryUntilTrue(
        () ->
          numWritten == this.queryHelper.countRows(
              generatedTestConfig.getFullDatasourceName(),
              Intervals.ETERNITY,
              name -> new LongSumAggregatorFactory(name, "count")
          ),
        StringUtils.format(
            "dataSource[%s] consumed [%,d] events, expected [%,d]",
            generatedTestConfig.getFullDatasourceName(),
            this.queryHelper.countRows(
                generatedTestConfig.getFullDatasourceName(),
                Intervals.ETERNITY,
                name -> new LongSumAggregatorFactory(name, "count")
            ),
            numWritten
        )
    );

    // Query data
    final String querySpec = generatedTestConfig.getStreamQueryPropsTransform()
                                                .apply(getResourceAsString(QUERIES_FILE));
    // this query will probably be answered from the indexing tasks but possibly from 2 historical segments / 2 indexing
    this.queryHelper.testQueriesFromString(querySpec);

    // All data written to stream within 10 secs.
    // Each task duration is 30 secs. Hence, one task will be able to consume all data from the stream.
    LOG.info("Waiting for all indexing tasks to finish");
    ITRetryUtil.retryUntilTrue(
        () -> (indexer.getCompleteTasksForDataSource(generatedTestConfig.getFullDatasourceName()).size() > 0),
        "Waiting for Task Completion"
    );

    // wait for segments to be handed off
    ITRetryUtil.retryUntilTrue(
        () -> coordinator.areSegmentsLoaded(generatedTestConfig.getFullDatasourceName()),
        "Real-time generated segments loaded"
    );

    // this query will be answered by at least 1 historical segment, most likely 2, and possibly up to all 4
    this.queryHelper.testQueriesFromString(querySpec);
  }

  long getSumOfEventSequence(int numEvents)
  {
    return (numEvents * (1 + numEvents)) / 2;
  }

  private void doMethodTeardown(GeneratedTestConfig generatedTestConfig)
  {
    if (generatedTestConfig.getSupervisorId() != null) {
      try {
        LOG.info("Terminating supervisor");
        indexer.terminateSupervisor(generatedTestConfig.getSupervisorId());
        // Shutdown all tasks of supervisor
        List<TaskResponseObject> runningTasks = indexer.getUncompletedTasksForDataSource(generatedTestConfig.getFullDatasourceName());
        for (TaskResponseObject task : runningTasks) {
          indexer.shutdownTask(task.getId());
        }
      }
      catch (Exception e) {
        // Best effort cleanup as the supervisor may have already been cleanup
        LOG.warn(e, "Failed to cleanup supervisor. This might be expected depending on the test method");
      }
    }
    try {
      unloader(generatedTestConfig.getFullDatasourceName());
    }
    catch (Exception e) {
      // Best effort cleanup as the datasource may have already been cleanup
      LOG.warn(e, "Failed to cleanup datasource. This might be expected depending on the test method");
    }
    try {
      streamAdminClient.deleteStream(generatedTestConfig.getStreamName());
    }
    catch (Exception e) {
      // Best effort cleanup as the stream may have already been cleanup
      LOG.warn(e, "Failed to cleanup stream. This might be expected depending on the test method");
    }
  }

  /**
   * Test ingestion with multiple supervisors writing to the same datasource.
   * This test creates multiple supervisors (specified by supervisorCount) that all write to the same datasource.
   * Each supervisor reads from its own stream and processes a distinct subset of events.
   * The total number of events across all streams equals the standard test event count.
   *
   * @param transactionEnabled Whether to enable transactions (null for streams that don't support transactions)
   * @param numSupervisors     Number of supervisors to create
   * @throws Exception if an error occurs
   */
  protected void doTestMultiSupervisorIndexDataStableState(
      @Nullable Boolean transactionEnabled,
      int numSupervisors
  ) throws Exception
  {

    final String dataSource = getTestNamePrefix() + "_test_" + UUID.randomUUID();
    final String fullDatasourceName = dataSource + config.getExtraDatasourceNameSuffix();

    final List<GeneratedTestConfig> testConfigs = new ArrayList<>(numSupervisors);
    final List<StreamEventWriter> streamEventWriters = new ArrayList<>(numSupervisors);
    final List<Closeable> resourceClosers = new ArrayList<>(numSupervisors);

    try {
      for (int i = 0; i < numSupervisors; ++i) {
        final String supervisorId = fullDatasourceName + "_supervisor_" + i;
        GeneratedTestConfig testConfig = new GeneratedTestConfig(
            INPUT_FORMAT,
            getResourceAsString(JSON_INPUT_FORMAT_PATH),
            fullDatasourceName
        );
        testConfig.setSupervisorId(supervisorId);

        testConfigs.add(testConfig);
        Closeable closer = createResourceCloser(testConfig);
        resourceClosers.add(closer);

        StreamEventWriter writer = createStreamEventWriter(config, transactionEnabled);
        streamEventWriters.add(writer);

        final String taskSpec = testConfig.getStreamIngestionPropsTransform()
                                          .apply(getResourceAsString(SUPERVISOR_SPEC_TEMPLATE_PATH));
        LOG.info("supervisorSpec for stream [%s]: [%s]", testConfig.getStreamName(), taskSpec);

        indexer.submitSupervisor(taskSpec);
        LOG.info("Submitted supervisor [%s] for stream [%s]", supervisorId, testConfig.getStreamName());
      }

      for (GeneratedTestConfig testConfig : testConfigs) {
        ITRetryUtil.retryUntilEquals(
            () -> indexer.getSupervisorStatus(testConfig.getSupervisorId()),
            SupervisorStateManager.BasicState.RUNNING,
            10_000,
            30,
            "State of supervisor[" + testConfig.getSupervisorId() + "]"
        );

        ITRetryUtil.retryUntil(
            () -> indexer.getRunningTasks()
                         .stream().anyMatch(taskResponseObject -> taskResponseObject.getId().contains(testConfig.getSupervisorId())),
            true,
            10000,
            50,
            "Waiting for supervisor [" + testConfig.getSupervisorId() + "]'s tasks to be running"
        );
      }

      int secondsPerSupervisor = TOTAL_NUMBER_OF_SECOND / numSupervisors;
      long totalEventsWritten = 0L;

      for (int i = 0; i < numSupervisors; ++i) {
        GeneratedTestConfig testConfig = testConfigs.get(i);
        StreamEventWriter writer = streamEventWriters.get(i);

        int startSecond = i * secondsPerSupervisor;
        int endSecond = (i == numSupervisors - 1) ? TOTAL_NUMBER_OF_SECOND : (i + 1) * secondsPerSupervisor;
        int secondsToGenerate = endSecond - startSecond;

        DateTime partitionStartTime = FIRST_EVENT_TIME.plusSeconds(startSecond);

        final StreamGenerator generator = new WikipediaStreamEventStreamGenerator(
            new JsonEventSerializer(jsonMapper),
            EVENTS_PER_SECOND,
            CYCLE_PADDING_MS
        );

        long numWritten = generator.run(
            testConfig.getStreamName(),
            writer,
            secondsToGenerate,
            partitionStartTime
        );

        totalEventsWritten += numWritten;
        LOG.info(
            "Generated [%d] events for stream [%s], partition [%d / %d]",
            numWritten,
            testConfig.getStreamName(),
            i + 1,
            numSupervisors
        );
      }

      verifyMultiStreamIngestedData(fullDatasourceName, totalEventsWritten);
    }
    finally {
      for (StreamEventWriter writer : streamEventWriters) {
        writer.close();
      }

      for (Closeable closer : resourceClosers) {
        closer.close();
      }

      try {
        unloader(fullDatasourceName).close();
      }
      catch (Exception e) {
        LOG.warn(e, "Failed to unload datasource [%s]", fullDatasourceName);
      }
    }
  }

  /**
   * Verify that all data from multiple supervisors was ingested correctly.
   * This method waits until the expected number of rows is available in the datasource.
   *
   * @param datasourceName    The name of the datasource
   * @param expectedTotalRows The expected number of rows
   * @throws Exception if an error occurs
   */
  private void verifyMultiStreamIngestedData(String datasourceName, long expectedTotalRows) throws Exception
  {
    LOG.info("Waiting for stream indexing tasks to consume events");

    ITRetryUtil.retryUntilTrue(
        () -> expectedTotalRows == this.queryHelper.countRows(
            datasourceName,
            Intervals.ETERNITY,
            name -> new LongSumAggregatorFactory(name, "count")
        ),
        StringUtils.format(
            "dataSource[%s] consumed [%,d] events, expected [%,d]",
            datasourceName,
            this.queryHelper.countRows(
                datasourceName,
                Intervals.ETERNITY,
                name -> new LongSumAggregatorFactory(name, "count")
            ),
            expectedTotalRows
        )
    );

    LOG.info("Running queries to verify data");

    final String querySpec = generateStreamQueryPropsTransform(
        "",
        datasourceName
    ).apply(getResourceAsString(QUERIES_FILE));

    // Query against MMs and/or historicals
    this.queryHelper.testQueriesFromString(querySpec);

    LOG.info("Waiting for stream indexing tasks to finish");
    ITRetryUtil.retryUntilTrue(
        () -> (!indexer.getCompleteTasksForDataSource(datasourceName).isEmpty()),
        "Waiting for all tasks to complete"
    );

    ITRetryUtil.retryUntilTrue(
        () -> (coordinator.areSegmentsLoaded(datasourceName)),
        "Waiting for segments to load"
    );

    // Query against historicals
    this.queryHelper.testQueriesFromString(querySpec);
  }

  protected class GeneratedTestConfig
  {
    private final String streamName;
    private final String fullDatasourceName;
    private final String parserType;
    private final String parserOrInputFormat;
    private final List<String> dimensions;

    private final Function<String, String> streamQueryPropsTransform;

    private String supervisorId;
    private LagAggregator lagAggregator;
    private Map<String, Object> context = Map.of();

    public GeneratedTestConfig(String parserType, String parserOrInputFormat) throws Exception
    {
      this(parserType, parserOrInputFormat, DEFAULT_DIMENSIONS);
    }

    public GeneratedTestConfig(String parserType, String parserOrInputFormat, String fullDatasourceName) throws Exception
    {
      this(parserType, parserOrInputFormat, DEFAULT_DIMENSIONS, fullDatasourceName);
    }

    public GeneratedTestConfig(String parserType, String parserOrInputFormat, List<String> dimensions) throws Exception
    {
      this(parserType, parserOrInputFormat, dimensions, getTestNamePrefix() + "_indexing_service_test_" + UUID.randomUUID() + config.getExtraDatasourceNameSuffix());
    }

    public GeneratedTestConfig(String parserType, String parserOrInputFormat, List<String> dimensions, String fullDatasourceName) throws Exception
    {
      this.parserType = parserType;
      this.parserOrInputFormat = parserOrInputFormat;
      this.dimensions = dimensions;
      this.streamName = getTestNamePrefix() + "_index_test_" + UUID.randomUUID();
      this.fullDatasourceName = fullDatasourceName;
      Map<String, String> tags = ImmutableMap.of(
          STREAM_EXPIRE_TAG,
          Long.toString(DateTimes.nowUtc().plusMinutes(30).getMillis())
      );
      streamAdminClient.createStream(streamName, STREAM_SHARD_COUNT, tags);
      ITRetryUtil.retryUntil(
          () -> streamAdminClient.isStreamActive(streamName),
          true,
          10000,
          30,
          "Wait for stream active"
      );
      streamQueryPropsTransform = generateStreamQueryPropsTransform(streamName, fullDatasourceName);
    }

    public GeneratedTestConfig withContext(Map<String, Object> context)
    {
      this.context = context;
      return this;
    }

    public GeneratedTestConfig withLagAggregator(LagAggregator lagAggregator)
    {
      this.lagAggregator = lagAggregator;
      return this;
    }

    public String getSupervisorId()
    {
      return supervisorId;
    }

    public void setSupervisorId(String supervisorId)
    {
      this.supervisorId = supervisorId;
    }

    public String getStreamName()
    {
      return streamName;
    }

    public String getFullDatasourceName()
    {
      return fullDatasourceName;
    }

    public Function<String, String> getStreamIngestionPropsTransform()
    {
      return generateStreamIngestionPropsTransform(
          supervisorId == null ? fullDatasourceName : supervisorId,
          streamName,
          fullDatasourceName,
          parserType,
          parserOrInputFormat,
          dimensions,
          context,
          lagAggregator,
          config
      );
    }

    public Function<String, String> getStreamQueryPropsTransform()
    {
      return streamQueryPropsTransform;
    }
  }
}
