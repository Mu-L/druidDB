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

package org.apache.druid.tests.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.server.coordination.ServerManagerForQueryErrorTest;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.clients.CoordinatorResourceTestClient;
import org.apache.druid.testing.clients.QueryResourceTestClient;
import org.apache.druid.testing.guice.DruidTestModuleFactory;
import org.apache.druid.testing.utils.ITRetryUtil;
import org.apache.druid.testing.utils.QueryResultVerifier;
import org.apache.druid.testing.utils.QueryWithResults;
import org.apache.druid.testing.utils.TestQueryHelper;
import org.apache.druid.tests.TestNGGroup;
import org.apache.druid.tests.indexer.AbstractIndexerTest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class tests the query retry on missing segments. A segment can be missing in a historical during a query if
 * the historical drops the segment after the broker issues the query to the historical. To mimic this case, this
 * test spawns a historical modified for testing. This historical announces all segments assigned, but doesn't serve
 * all of them always. Instead, it can report missing segments for some
 * segments. See {@link ServerManagerForQueryErrorTest} for more details.
 * <p>
 * To run this test properly, the test group must be specified as {@link TestNGGroup#QUERY_RETRY}.
 */
@Test(groups = TestNGGroup.QUERY_RETRY)
@Guice(moduleFactory = DruidTestModuleFactory.class)
public class ITQueryRetryTestOnMissingSegments
{
  private static final String TWITTERSTREAM_DATA_SOURCE = "twitterstream";
  private static final String QUERIES_RESOURCE = "/queries/twitterstream_queries_query_retry_test.json";

  /**
   * This enumeration represents an expectation after finishing running the test query.
   */
  private enum Expectation
  {
    /**
     * Expect that the test query succeed and with correct results.
     */
    ALL_SUCCESS,
    /**
     * Expect that the test query returns the 200 HTTP response, but will surely return incorrect result.
     */
    INCORRECT_RESULT,
    /**
     * Expect that the test query must return the 500 HTTP response.
     */
    QUERY_FAILURE
  }

  @Inject
  private CoordinatorResourceTestClient coordinatorClient;
  @Inject
  private TestQueryHelper queryHelper;
  @Inject
  private QueryResourceTestClient queryClient;
  @Inject
  private IntegrationTestingConfig config;
  @Inject
  private ObjectMapper jsonMapper;

  @BeforeMethod
  public void before()
  {
    // ensure that twitterstream segments are loaded completely
    ITRetryUtil.retryUntilTrue(
        () -> coordinatorClient.areSegmentsLoaded(TWITTERSTREAM_DATA_SOURCE), "twitterstream segments load"
    );
  }

  @Test
  public void testWithRetriesDisabledPartialResultDisallowed() throws Exception
  {
    // Since retry is disabled and partial result is not allowed, the query must fail.
    testQueries(buildQuery(0, false), Expectation.QUERY_FAILURE);
  }

  @Test
  public void testWithRetriesDisabledPartialResultAllowed() throws Exception
  {
    // Since retry is disabled but partial result is allowed, the query must succeed.
    // However, the query must return incorrect result.
    testQueries(buildQuery(0, true), Expectation.INCORRECT_RESULT);
  }

  @Test
  public void testWithRetriesEnabledPartialResultDisallowed() throws Exception
  {
    // Since retry is enabled, the query must succeed even though partial result is disallowed.
    // The retry count is set to 1 since on the first retry of the query (i.e second overall try), the historical
    // will start processing the segment and not call it missing.
    // The query must return correct results.
    testQueries(buildQuery(1, false), Expectation.ALL_SUCCESS);
  }

  @Test
  public void testFailureWhenLastSegmentIsMissingWithPartialResultsDisallowed() throws Exception
  {
    // Since retry is disabled and partial result is not allowed, the query must fail since the last segment
    // is missing/unavailable.
    testQueries(buildQuery(0, false, 2), Expectation.QUERY_FAILURE);
  }

  private void testQueries(String queryWithResultsStr, Expectation expectation) throws Exception
  {
    final List<QueryWithResults> queries = jsonMapper.readValue(
        queryWithResultsStr,
        new TypeReference<>() {}
    );
    testQueries(queries, expectation);
  }

  private void testQueries(List<QueryWithResults> queries, Expectation expectation) throws Exception
  {
    int querySuccess = 0;
    int queryFailure = 0;
    int resultMatches = 0;
    int resultMismatches = 0;

    for (QueryWithResults queryWithResult : queries) {
      final StatusResponseHolder responseHolder = queryClient
          .queryAsync(queryHelper.getQueryURL(config.getBrokerUrl()), queryWithResult.getQuery())
          .get();

      if (responseHolder.getStatus().getCode() == HttpResponseStatus.OK.getCode()) {
        querySuccess++;

        List<Map<String, Object>> result = jsonMapper.readValue(
            responseHolder.getContent(),
            new TypeReference<>() {}
        );
        if (!QueryResultVerifier.compareResults(
            result,
            queryWithResult.getExpectedResults(),
            queryWithResult.getFieldsToTest()
        ).isSuccess()) {
          if (expectation != Expectation.INCORRECT_RESULT) {
            throw new ISE(
                "Incorrect query results for query %s \n expectedResults: %s \n actualResults : %s",
                queryWithResult.getQuery(),
                jsonMapper.writeValueAsString(queryWithResult.getExpectedResults()),
                jsonMapper.writeValueAsString(result)
            );
          } else {
            resultMismatches++;
          }
        } else {
          resultMatches++;
        }
      } else if (responseHolder.getStatus().getCode() == HttpResponseStatus.INTERNAL_SERVER_ERROR.getCode() &&
                 expectation == Expectation.QUERY_FAILURE) {
        final Map<String, Object> response = jsonMapper.readValue(responseHolder.getContent(), Map.class);
        final String errorMessage = (String) response.get("errorMessage");
        Assert.assertNotNull(errorMessage, "errorMessage");
        Assert.assertTrue(errorMessage.contains("No results found for segments"));
        queryFailure++;
      } else {
        throw new ISE(
            "Unexpected failure, code: [%s], content: [%s]",
            responseHolder.getStatus(),
            responseHolder.getContent()
        );
      }
    }

    switch (expectation) {
      case ALL_SUCCESS:
        Assert.assertEquals(querySuccess, 1);
        Assert.assertEquals(queryFailure, 0);
        Assert.assertEquals(resultMatches, 1);
        Assert.assertEquals(resultMismatches, 0);
        break;
      case QUERY_FAILURE:
        Assert.assertEquals(querySuccess, 0);
        Assert.assertEquals(queryFailure, 1);
        Assert.assertEquals(resultMatches, 0);
        Assert.assertEquals(resultMismatches, 0);
        break;
      case INCORRECT_RESULT:
        Assert.assertEquals(querySuccess, 1);
        Assert.assertEquals(queryFailure, 0);
        Assert.assertEquals(resultMatches, 0);
        Assert.assertEquals(resultMismatches, 1);
        break;
      default:
        throw new ISE("Unknown expectation[%s]", expectation);
    }
  }

  private String buildQuery(int numRetriesOnMissingSegments, boolean allowPartialResults) throws IOException
  {
    return buildQuery(numRetriesOnMissingSegments, allowPartialResults, -1);
  }

  private String buildQuery(int numRetriesOnMissingSegments, boolean allowPartialResults, int unavailableSegmentIdx) throws IOException
  {
    return StringUtils.replace(
        AbstractIndexerTest.getResourceAsString(QUERIES_RESOURCE),
        "%%CONTEXT%%",
        jsonMapper.writeValueAsString(buildContext(numRetriesOnMissingSegments, allowPartialResults, unavailableSegmentIdx))
    );
  }

  private static Map<String, Object> buildContext(int numRetriesOnMissingSegments, boolean allowPartialResults, int unavailableSegmentIdx)
  {
    final Map<String, Object> context = new HashMap<>();
    // Disable cache so that each run hits historical.
    context.put(QueryContexts.USE_CACHE_KEY, false);
    context.put(QueryContexts.USE_RESULT_LEVEL_CACHE_KEY, false);
    context.put(QueryContexts.NUM_RETRIES_ON_MISSING_SEGMENTS_KEY, numRetriesOnMissingSegments);
    context.put(QueryContexts.RETURN_PARTIAL_RESULTS_KEY, allowPartialResults);
    context.put(ServerManagerForQueryErrorTest.QUERY_RETRY_TEST_CONTEXT_KEY, true);
    context.put(ServerManagerForQueryErrorTest.QUERY_RETRY_UNAVAILABLE_SEGMENT_IDX_KEY, unavailableSegmentIdx);
    return context;
  }
}
