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

package org.apache.druid.msq.dart;

import com.google.common.collect.ImmutableList;
import org.apache.druid.msq.dart.worker.http.DartWorkerResource;
import org.apache.druid.msq.rpc.ResourcePermissionMapper;
import org.apache.druid.msq.rpc.WorkerResource;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.sql.http.SqlResource;

import java.util.List;

public class DartResourcePermissionMapper implements ResourcePermissionMapper
{
  /**
   * Permissions for admin APIs in {@link DartWorkerResource} and {@link WorkerResource}. Note that queries from
   * end users go through {@link SqlResource}, which wouldn't use these mappings.
   */
  @Override
  public List<ResourceAction> getAdminPermissions()
  {
    return ImmutableList.of(
        new ResourceAction(Resource.STATE_RESOURCE, Action.READ),
        new ResourceAction(Resource.STATE_RESOURCE, Action.WRITE)
    );
  }

  /**
   * Permissions for per-query APIs in {@link DartWorkerResource} and {@link WorkerResource}. Note that queries from
   * end users go through {@link SqlResource}, which wouldn't use these mappings.
   */
  @Override
  public List<ResourceAction> getQueryPermissions(String queryId)
  {
    return getAdminPermissions();
  }
}
