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

package org.apache.druid.segment;

import org.apache.druid.query.policy.Policy;

/**
 * A {@link Segment} wrapper with a {@link Policy} restriction that is not applied. Instead, it relies on the
 * caller to apply the policy.
 * <p>
 * This class is useful when a query engine needs direct access to interfaces that cannot have policies applied
 * transparently. For example, {@link RestrictedSegment} returns null for {@link #as(Class)} when attempting to use
 * {@link QueryableIndex} because it cannot apply policies transparently to a {@link QueryableIndex}. To use one, a
 * query engine needs to obtain a {@link BypassRestrictedSegment} and apply the policies itself.
 */
class BypassRestrictedSegment extends WrappedSegment
{
  protected final Policy policy;

  public BypassRestrictedSegment(
      Segment delegate,
      Policy policy
  )
  {
    super(delegate);
    this.policy = policy;
  }

  public Policy getPolicy()
  {
    return policy;
  }

  @Override
  public <T> T as(Class<T> clazz)
  {
    return delegate.as(clazz);
  }

  @Override
  public String getDebugString()
  {
    return "bypassrestricted->" + delegate.getDebugString();
  }
}
