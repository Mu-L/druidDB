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

package org.apache.druid.sql.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.jersey.api.container.ContainerException;
import com.sun.jersey.api.core.HttpContext;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.commons.io.IOUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.http.ClientSqlQuery;
import org.apache.druid.server.initialization.jetty.HttpException;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * See {@link ClientSqlQuery} for the equivalent POJO class used on the client side to interact with the Broker.
 * Note: The fields {@link #resultFormat} and {@link #parameters} rely on Calcite data types,
 * preventing this class from being moved to the processing module for reuse.
 */
public class SqlQuery
{
  public static List<TypedValue> getParameterList(List<SqlParameter> parameters)
  {
    return parameters.stream()
                     // null params are not good!
                     // we pass them to the planner, so that it can generate a proper error message.
                     // see SqlParameterizerShuttle and RelParameterizerShuttle.
                     .map(p -> p == null ? null : p.getTypedValue())
                     .collect(Collectors.toList());
  }

  private final String query;
  private final ResultFormat resultFormat;
  private final boolean header;
  private final boolean typesHeader;
  private final boolean sqlTypesHeader;
  private final Map<String, Object> context;
  private final List<SqlParameter> parameters;

  @JsonCreator
  public SqlQuery(
      @JsonProperty("query") final String query,
      @JsonProperty("resultFormat") @Nullable final ResultFormat resultFormat,
      @JsonProperty("header") final boolean header,
      @JsonProperty("typesHeader") final boolean typesHeader,
      @JsonProperty("sqlTypesHeader") final boolean sqlTypesHeader,
      @JsonProperty("context") @Nullable final Map<String, Object> context,
      @JsonProperty("parameters") @Nullable final List<SqlParameter> parameters
  )
  {
    this.query = Preconditions.checkNotNull(query, "query");
    this.resultFormat = resultFormat == null ? ResultFormat.DEFAULT_RESULT_FORMAT : resultFormat;
    this.header = header;
    this.typesHeader = typesHeader;
    this.sqlTypesHeader = sqlTypesHeader;
    this.context = context == null ? ImmutableMap.of() : context;
    this.parameters = parameters == null ? ImmutableList.of() : parameters;

    if (typesHeader && !header) {
      throw new ISE("Cannot include 'typesHeader' without 'header'");
    }

    if (sqlTypesHeader && !header) {
      throw new ISE("Cannot include 'sqlTypesHeader' without 'header'");
    }
  }

  public SqlQuery withOverridenContext(Map<String, Object> overridenContext)
  {
    return new SqlQuery(
        getQuery(),
        getResultFormat(),
        includeHeader(),
        includeTypesHeader(),
        includeSqlTypesHeader(),
        overridenContext,
        getParameters()
    );
  }

  @JsonProperty
  public String getQuery()
  {
    return query;
  }

  @JsonProperty
  public ResultFormat getResultFormat()
  {
    return resultFormat;
  }

  @JsonProperty("header")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean includeHeader()
  {
    return header;
  }

  @JsonProperty("typesHeader")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean includeTypesHeader()
  {
    return typesHeader;
  }

  @JsonProperty("sqlTypesHeader")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean includeSqlTypesHeader()
  {
    return sqlTypesHeader;
  }

  @JsonProperty
  public Map<String, Object> getContext()
  {
    return context;
  }

  public QueryContext queryContext()
  {
    return QueryContext.of(context);
  }

  @JsonProperty
  public List<SqlParameter> getParameters()
  {
    return parameters;
  }

  public List<TypedValue> getParameterList()
  {
    return getParameterList(parameters);
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final SqlQuery sqlQuery = (SqlQuery) o;
    return header == sqlQuery.header &&
           typesHeader == sqlQuery.typesHeader &&
           sqlTypesHeader == sqlQuery.sqlTypesHeader &&
           Objects.equals(query, sqlQuery.query) &&
           resultFormat == sqlQuery.resultFormat &&
           Objects.equals(context, sqlQuery.context) &&
           Objects.equals(parameters, sqlQuery.parameters);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(query, resultFormat, header, typesHeader, sqlTypesHeader, context, parameters);
  }

  @Override
  public String toString()
  {
    return "SqlQuery{" +
           "query='" + query + '\'' +
           ", resultFormat=" + resultFormat +
           ", header=" + header +
           ", typesHeader=" + typesHeader +
           ", sqlTypesHeader=" + sqlTypesHeader +
           ", context=" + context +
           ", parameters=" + parameters +
           '}';
  }

  public SqlQuery withQueryContext(Map<String, Object> newContext)
  {
    return new SqlQuery(query, resultFormat, header, typesHeader, sqlTypesHeader, newContext, parameters);
  }

  /**
   * Extract SQL query object or SQL text from an HTTP Request
   */
  @FunctionalInterface
  interface ISqlQueryExtractor<T>
  {
    T extract() throws IOException;
  }

  /**
   * For BROKERs to use.
   * <p>
   * Brokers use com.sun.jersey upon Jetty for RESTful API, however jersey intØernally has special handling for x-www-form-urlencoded,
   * it's not able to get the data from the stream of HttpServletRequest for such content type.
   * So we use HttpContext to get the request entity/string instead of using HttpServletRequest.
   *
   * @throws HttpException if the content type is not supported or the SQL query is malformed
   */
  public static SqlQuery from(HttpContext httpContext) throws HttpException
  {
    return from(
        httpContext.getRequest().getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
        () -> {
          try {
            return httpContext.getRequest().getEntity(SqlQuery.class);
          }
          catch (ContainerException e) {
            if (e.getCause() instanceof JsonParseException) {
              throw new HttpException(
                  Response.Status.BAD_REQUEST,
                  StringUtils.format("Malformed SQL query wrapped in JSON: %s", e.getCause().getMessage())
              );
            } else {
              throw e;
            }
          }
        },
        () -> httpContext.getRequest().getEntity(String.class)
    );
  }

  /**
   * For Router to use
   *
   * @throws HttpException if the content type is not supported or the SQL query is malformed
   */
  public static SqlQuery from(HttpServletRequest request, ObjectMapper objectMapper) throws HttpException
  {
    return from(
        request.getContentType(),
        () -> objectMapper.readValue(request.getInputStream(), SqlQuery.class),
        () -> new String(IOUtils.toByteArray(request.getInputStream()), StandardCharsets.UTF_8)
    );
  }

  private static SqlQuery from(
      String contentType,
      ISqlQueryExtractor<SqlQuery> jsonQueryExtractor,
      ISqlQueryExtractor<String> rawQueryExtractor
  ) throws HttpException
  {
    try {
      if (MediaType.APPLICATION_JSON.equals(contentType)) {

        SqlQuery sqlQuery = jsonQueryExtractor.extract();
        if (sqlQuery == null) {
          throw new HttpException(Response.Status.BAD_REQUEST, "Empty query");
        }
        return sqlQuery;

      } else if (MediaType.TEXT_PLAIN.equals(contentType)) {

        String sql = rawQueryExtractor.extract().trim();
        if (sql.isEmpty()) {
          throw new HttpException(Response.Status.BAD_REQUEST, "Empty query");
        }

        return new SqlQuery(sql, null, false, false, false, null, null);

      } else if (MediaType.APPLICATION_FORM_URLENCODED.equals(contentType)) {

        String sql = rawQueryExtractor.extract().trim();
        if (sql.isEmpty()) {
          throw new HttpException(Response.Status.BAD_REQUEST, "Empty query");
        }

        try {
          sql = URLDecoder.decode(sql, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
          throw new HttpException(
              Response.Status.BAD_REQUEST,
              "Unable to decode URL-Encoded SQL query: " + e.getMessage()
          );
        }

        return new SqlQuery(sql, null, false, false, false, null, null);

      } else {
        throw new HttpException(
            Response.Status.UNSUPPORTED_MEDIA_TYPE,
            StringUtils.format(
                "Unsupported Content-Type: %s. Only application/json, text/plain or application/x-www-form-urlencoded is supported.",
                contentType
            )
        );
      }
    }
    catch (MismatchedInputException e) {
      if (e.getOriginalMessage().endsWith("end-of-input")) {
        throw new HttpException(Response.Status.BAD_REQUEST, "Empty query");
      } else {
        throw new HttpException(
            Response.Status.BAD_REQUEST,
            StringUtils.format("Malformed SQL query wrapped in JSON: %s", e.getMessage())
        );
      }
    }
    catch (JsonParseException e) {
      throw new HttpException(
          Response.Status.BAD_REQUEST,
          StringUtils.format("Malformed SQL query wrapped in JSON: %s", e.getMessage())
      );
    }
    catch (IOException e) {
      throw new HttpException(Response.Status.BAD_REQUEST, "Unable to read query from request: " + e.getMessage());
    }
  }
}
