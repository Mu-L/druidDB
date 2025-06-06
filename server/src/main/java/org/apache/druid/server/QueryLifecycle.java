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

package org.apache.druid.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import org.apache.druid.client.DirectDruidClient;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.SequenceWrapper;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.DefaultQueryConfig;
import org.apache.druid.query.DruidMetrics;
import org.apache.druid.query.GenericQueryMetricsFactory;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryInterruptedException;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryTimeoutException;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.policy.PolicyEnforcer;
import org.apache.druid.server.log.RequestLogger;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.AuthConfig;
import org.apache.druid.server.security.AuthenticationResult;
import org.apache.druid.server.security.AuthorizationResult;
import org.apache.druid.server.security.AuthorizationUtils;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Class that helps a Druid server (broker, historical, etc) manage the lifecycle of a query that it is handling. It
 * ensures that a query goes through the following stages, in the proper order:
 *
 * <ol>
 * <li>Initialization ({@link #initialize(Query)})</li>
 * <li>Authorization ({@link #authorize(HttpServletRequest)}</li>
 * <li>Execution ({@link #execute()}</li>
 * <li>Logging ({@link #emitLogsAndMetrics(Throwable, String, long)}</li>
 * </ol>
 * Alternatively, if the request is already authenticated and authorized, just call
 * {@link #runSimple(Query, AuthenticationResult, AuthorizationResult)}.
 *
 * <p>
 * This object is not thread-safe.
 */
public class QueryLifecycle
{
  private static final Logger log = new Logger(QueryLifecycle.class);

  private final QueryRunnerFactoryConglomerate conglomerate;
  private final QuerySegmentWalker texasRanger;
  private final GenericQueryMetricsFactory queryMetricsFactory;
  private final ServiceEmitter emitter;
  private final RequestLogger requestLogger;
  private final AuthorizerMapper authorizerMapper;
  private final DefaultQueryConfig defaultQueryConfig;
  private final AuthConfig authConfig;
  private final PolicyEnforcer policyEnforcer;
  private final long startMs;
  private final long startNs;

  private State state = State.NEW;
  private AuthenticationResult authenticationResult;
  private QueryToolChest toolChest;

  @MonotonicNonNull
  private Query<?> baseQuery;
  @MonotonicNonNull
  private Set<String> userContextKeys;

  public QueryLifecycle(
      final QueryRunnerFactoryConglomerate conglomerate,
      final QuerySegmentWalker texasRanger,
      final GenericQueryMetricsFactory queryMetricsFactory,
      final ServiceEmitter emitter,
      final RequestLogger requestLogger,
      final AuthorizerMapper authorizerMapper,
      final DefaultQueryConfig defaultQueryConfig,
      final AuthConfig authConfig,
      final PolicyEnforcer policyEnforcer,
      final long startMs,
      final long startNs
  )
  {
    this.conglomerate = conglomerate;
    this.texasRanger = texasRanger;
    this.queryMetricsFactory = queryMetricsFactory;
    this.emitter = emitter;
    this.requestLogger = requestLogger;
    this.authorizerMapper = authorizerMapper;
    this.defaultQueryConfig = defaultQueryConfig;
    this.authConfig = authConfig;
    this.policyEnforcer = policyEnforcer;
    this.startMs = startMs;
    this.startNs = startNs;
  }

  /**
   * For callers who have already authorized their query, and where simplicity is desired over flexibility. This method
   * does it all in one call. Logs and metrics are emitted when the Sequence is either fully iterated or throws an
   * exception.
   * <p>
   * The {@code state} transitions from NEW, to INITIALIZED, to AUTHORIZING, to AUTHORIZED, to EXECUTING, then DONE.
   *
   * @param query                the query
   * @param authenticationResult authentication result indicating identity of the requester
   * @param authorizationResult  authorization result of requester
   * @return results
   * @throws DruidException if the given authorizationResult deny access, which indicates a bug
   */
  public <T> QueryResponse<T> runSimple(
      final Query<T> query,
      final AuthenticationResult authenticationResult,
      final AuthorizationResult authorizationResult
  )
  {
    initialize(query);

    final Sequence<T> results;

    final QueryResponse<T> queryResponse;
    try {
      preAuthorized(authenticationResult, authorizationResult);
      queryResponse = execute();
      results = queryResponse.getResults();
    }
    catch (Throwable e) {
      emitLogsAndMetrics(e, null, -1);
      throw e;
    }

    /*
     * It seems extremely weird that the below code is wrapping the Sequence in order to emitLogsAndMetrics.
     * The Sequence was returned by the call to execute, it would be worthwile to figure out why this wrapping
     * cannot be moved into execute().  We leave this as an exercise for the future, however as this oddity
     * was discovered while just trying to expose HTTP response headers
     */
    return new QueryResponse<>(
        Sequences.wrap(
            results,
            new SequenceWrapper()
            {
              @Override
              public void after(final boolean isDone, final Throwable thrown)
              {
                emitLogsAndMetrics(thrown, null, -1);
              }
            }
        ),
        queryResponse.getResponseContext()
    );
  }

  /**
   * Initializes this object to execute a specific query. Does not actually execute the query.
   * <p>
   * The {@code state} transitions from NEW, to INITIALIZED.
   *
   * @param baseQuery the query
   * @throws DruidException if the current state is not NEW, which indicates a bug
   */
  public void initialize(final Query<?> baseQuery)
  {
    transition(State.NEW, State.INITIALIZED);

    userContextKeys = new HashSet<>(baseQuery.getContext().keySet());
    String queryId = baseQuery.getId();
    if (Strings.isNullOrEmpty(queryId)) {
      queryId = UUID.randomUUID().toString();
    }

    Map<String, Object> mergedUserAndConfigContext = QueryContexts.override(
        defaultQueryConfig.getContext(),
        baseQuery.getContext()
    );
    mergedUserAndConfigContext.put(BaseQuery.QUERY_ID, queryId);
    this.baseQuery = baseQuery.withOverriddenContext(mergedUserAndConfigContext);
    this.toolChest = conglomerate.getToolChest(this.baseQuery);
  }

  /**
   * Returns {@link AuthorizationResult} based on {@code DRUID_AUTHENTICATION_RESULT} in the given request, base query
   * would be transformed with restrictions on the AuthorizationResult.
   * <p>
   * The {@code state} transitions from INITIALIZED, to AUTHORIZING, then to AUTHORIZED or UNAUTHORIZED.
   * <p>
   * Note this won't throw exception if authorization deny access or impose policy restrictions. It is the caller's
   * responsibility to throw exception on denial and impose policy restriction.
   *
   * @param req HTTP request to be authorized. The auth-related fields in the HTTP request will be set.
   * @return authorization result denoting whether the query is authorized or not, along with policy restrictions
   * @throws IllegalStateException if the request was not authenticated
   * @throws DruidException        if the current state is not INITIALIZED, which indicates a bug
   */
  public AuthorizationResult authorize(HttpServletRequest req)
  {
    transition(State.INITIALIZED, State.AUTHORIZING);
    final Iterable<ResourceAction> resourcesToAuthorize = Iterables.concat(
        Iterables.transform(
            baseQuery.getDataSource().getTableNames(),
            AuthorizationUtils.DATASOURCE_READ_RA_GENERATOR
        ),
        Iterables.transform(
            authConfig.contextKeysToAuthorize(userContextKeys),
            contextParam -> new ResourceAction(new Resource(contextParam, ResourceType.QUERY_CONTEXT), Action.WRITE)
        )
    );
    return doAuthorize(
        AuthorizationUtils.authenticationResultFromRequest(req),
        AuthorizationUtils.authorizeAllResourceActions(
            req,
            resourcesToAuthorize,
            authorizerMapper
        )
    );
  }

  /**
   * Returns {@link AuthorizationResult} based on the given {@link AuthenticationResult}, base query would be
   * transformed with restrictions on the AuthorizationResult.
   * <p>
   * The {@code state} transitions from INITIALIZED, to AUTHORIZING, then to AUTHORIZED or UNAUTHORIZED.
   * <p>
   * Note this won't throw exception if authorization deny access or impose policy restrictions. It is the caller's
   * responsibility to throw exception on denial and impose policy restriction.
   * <p>
   * This method is to be used by the grpc-query-extension.
   *
   * @param authenticationResult authentication result indicating identity of the requester
   * @return authorization result denoting whether the query is authorized or not, along with policy restrictions.
   * @throws DruidException if the current state is not INITIALIZED, which indicates a bug
   */
  public AuthorizationResult authorize(AuthenticationResult authenticationResult)
  {
    transition(State.INITIALIZED, State.AUTHORIZING);
    final Iterable<ResourceAction> resourcesToAuthorize = Iterables.concat(
        Iterables.transform(
            baseQuery.getDataSource().getTableNames(),
            AuthorizationUtils.DATASOURCE_READ_RA_GENERATOR
        ),
        Iterables.transform(
            authConfig.contextKeysToAuthorize(userContextKeys),
            contextParam -> new ResourceAction(new Resource(contextParam, ResourceType.QUERY_CONTEXT), Action.WRITE)
        )
    );
    return doAuthorize(
        authenticationResult,
        AuthorizationUtils.authorizeAllResourceActions(
            authenticationResult,
            resourcesToAuthorize,
            authorizerMapper
        )
    );
  }

  private void preAuthorized(
      final AuthenticationResult authenticationResult,
      final AuthorizationResult authorizationResult
  )
  {
    // The authorization have already been checked previously (or skipped). This just follows the state transition
    // process, should not throw unauthorized error.
    transition(State.INITIALIZED, State.AUTHORIZING);
    doAuthorize(authenticationResult, authorizationResult);
    if (!state.equals(State.AUTHORIZED)) {
      throw DruidException.defensive("Unexpected state [%s], expecting [%s].", state, State.AUTHORIZED);
    }
  }

  private AuthorizationResult doAuthorize(
      final AuthenticationResult authenticationResult,
      final AuthorizationResult authorizationResult
  )
  {
    Preconditions.checkNotNull(authenticationResult, "authenticationResult");
    Preconditions.checkNotNull(authorizationResult, "authorizationResult");

    if (!authorizationResult.allowBasicAccess()) {
      // Not authorized; go straight to Jail, do not pass Go.
      transition(State.AUTHORIZING, State.UNAUTHORIZED);
    } else {
      transition(State.AUTHORIZING, State.AUTHORIZED);
      this.baseQuery = this.baseQuery.withDataSource(baseQuery.getDataSource()
                                                              .withPolicies(
                                                                  authorizationResult.getPolicyMap(),
                                                                  policyEnforcer
                                                              ));
    }

    this.authenticationResult = authenticationResult;
    return authorizationResult;
  }

  /**
   * Executes the query.
   * <p>
   * Note that query logs and metrics will not be emitted automatically when the Sequence is fully iterated withou. It
   * is the caller's responsibility to call {@link #emitLogsAndMetrics(Throwable, String, long)} to emit logs and
   * metrics.
   * <p>
   * The {@code state} transitions from AUTHORIZED, to EXECUTING.
   *
   * @return result sequence and response context
   * @throws DruidException if the current state is not AUTHORIZED, which indicates a bug
   */
  public <T> QueryResponse<T> execute()
  {
    transition(State.AUTHORIZED, State.EXECUTING);

    final ResponseContext responseContext = DirectDruidClient.makeResponseContextForQuery();

    @SuppressWarnings("unchecked")
    final Sequence<T> res = QueryPlus.wrap((Query<T>) baseQuery)
                                     .withIdentity(authenticationResult.getIdentity())
                                     .run(texasRanger, responseContext);

    return new QueryResponse<>(res == null ? Sequences.empty() : res, responseContext);
  }

  /**
   * Emits logs and metrics for this query.
   * <p>
   * The {@code state} transitions to DONE. The initial state can be anything, but it likely shouldn't be set to DONE.
   * <p>
   * If {@code baseQuery} is null, likely because {@link #initialize(Query)} was never call, do nothing.
   *
   * @param e             exception that occurred while processing this query
   * @param remoteAddress remote address, for logging; or null if unknown
   * @param bytesWritten  number of bytes written; will become a query/bytes metric if >= 0
   */
  @SuppressWarnings("unchecked")
  public void emitLogsAndMetrics(
      @Nullable final Throwable e,
      @Nullable final String remoteAddress,
      final long bytesWritten
  )
  {
    if (baseQuery == null) {
      // Never initialized, don't log or emit anything.
      return;
    }

    if (state == State.DONE) {
      log.warn("Tried to emit logs and metrics twice for query[%s]!", baseQuery.getId());
    }

    state = State.DONE;

    final boolean success = e == null;

    try {
      final long queryTimeNs = System.nanoTime() - startNs;

      QueryMetrics queryMetrics = DruidMetrics.makeRequestMetrics(
          queryMetricsFactory,
          toolChest,
          baseQuery,
          StringUtils.nullToEmptyNonDruidDataString(remoteAddress)
      );
      queryMetrics.success(success);
      queryMetrics.reportQueryTime(queryTimeNs);

      if (bytesWritten >= 0) {
        queryMetrics.reportQueryBytes(bytesWritten);
      }

      if (authenticationResult != null) {
        queryMetrics.identity(authenticationResult.getIdentity());
      }

      queryMetrics.emit(emitter);

      final Map<String, Object> statsMap = new LinkedHashMap<>();
      statsMap.put("query/time", TimeUnit.NANOSECONDS.toMillis(queryTimeNs));
      statsMap.put("query/bytes", bytesWritten);
      statsMap.put("success", success);

      if (authenticationResult != null) {
        statsMap.put("identity", authenticationResult.getIdentity());
      }

      if (e != null) {
        statsMap.put("exception", e.toString());
        if (shouldLogStackTrace(e, baseQuery.context())) {
          log.warn(e, "Exception while processing queryId [%s]", baseQuery.getId());
        } else {
          log.noStackTrace().warn(e, "Exception while processing queryId [%s]", baseQuery.getId());
        }
        if (e instanceof QueryInterruptedException || e instanceof QueryTimeoutException) {
          // Mimic behavior from QueryResource, where this code was originally taken from.
          statsMap.put("interrupted", true);
          statsMap.put("reason", e.toString());
        }
      }

      requestLogger.logNativeQuery(
          RequestLogLine.forNative(
              baseQuery,
              DateTimes.utc(startMs),
              StringUtils.nullToEmptyNonDruidDataString(remoteAddress),
              new QueryStats(statsMap)
          )
      );
    }
    catch (Exception ex) {
      log.error(ex, "Unable to log query [%s]!", baseQuery);
    }
  }

  @Nullable
  public Query<?> getQuery()
  {
    return baseQuery;
  }

  public String getQueryId()
  {
    return baseQuery.getId();
  }

  public String threadName(String currThreadName)
  {
    return StringUtils.format(
        "%s[%s_%s_%s]",
        currThreadName,
        baseQuery.getType(),
        baseQuery.getDataSource().getTableNames(),
        getQueryId()
    );
  }

  private boolean isSerializeDateTimeAsLong()
  {
    final QueryContext queryContext = baseQuery.context();
    final boolean shouldFinalize = queryContext.isFinalize(true);
    return queryContext.isSerializeDateTimeAsLong(false)
           || (!shouldFinalize && queryContext.isSerializeDateTimeAsLongInner(false));
  }

  public ObjectMapper newOutputWriter(ResourceIOReaderWriterFactory.ResourceIOReaderWriter ioReaderWriter)
  {
    return ioReaderWriter.getResponseWriter().newOutputWriter(
        getToolChest(),
        baseQuery,
        isSerializeDateTimeAsLong()
    );
  }

  public QueryToolChest getToolChest()
  {
    if (state.compareTo(State.INITIALIZED) < 0) {
      throw new ISE("Not yet initialized");
    }

    //noinspection unchecked
    return toolChest;
  }

  private void transition(final State from, final State to)
  {
    if (state != from) {
      throw DruidException.defensive("Cannot transition from[%s] to[%s], current state[%s].", from, to, state);
    }

    state = to;
  }

  enum State
  {
    NEW,
    INITIALIZED,
    AUTHORIZING,
    AUTHORIZED,
    EXECUTING,
    UNAUTHORIZED,
    DONE
  }

  /**
   * Returns whether stack traces should be logged for a particular exception thrown with a particular query context.
   * Stack traces are logged if {@link QueryContext#isDebug()}, or if the {@link DruidException.Persona} is
   * {@link DruidException.Persona#DEVELOPER} or {@link DruidException.Persona#OPERATOR}. The idea is that other
   * personas are meant to interact with the API, not with code or logs, so logging stack traces by default adds
   * clutter that is not very helpful.
   *
   * @param e            exception
   * @param queryContext query context
   */
  public static boolean shouldLogStackTrace(final Throwable e, final QueryContext queryContext)
  {
    if (queryContext.isDebug() || e.getMessage() == null) {
      return true;
    } else if (e instanceof DruidException) {
      final DruidException.Persona persona = ((DruidException) e).getTargetPersona();
      return persona == DruidException.Persona.OPERATOR || persona == DruidException.Persona.DEVELOPER;
    } else {
      return false;
    }
  }
}
