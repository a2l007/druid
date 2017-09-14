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

package io.druid.server.security;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.druid.guice.ManageLifecycle;
import io.druid.java.util.common.lifecycle.LifecycleStart;

import java.util.List;
import java.util.Map;

@ManageLifecycle
public class AuthenticatorMapper
{
  private Map<String, Authenticator> authenticatorMap;
  private Authenticator escalatingAuthenticator;

  public AuthenticatorMapper(
      Map<String, Authenticator> authenticatorMap,
      String escalatingAuthenticatorName
  )
  {
    this.authenticatorMap = authenticatorMap;
    this.escalatingAuthenticator = authenticatorMap.get(escalatingAuthenticatorName);
    Preconditions.checkNotNull(
        escalatingAuthenticator,
        "Could not find escalating authenticator with name: %s",
        escalatingAuthenticatorName
    );
  }

  public Authenticator getAuthenticator(String namespace)
  {
    return authenticatorMap.get(namespace);
  }

  public Authenticator getEscalatingAuthenticator()
  {
    return escalatingAuthenticator;
  }

  public List<Authenticator> getAuthenticatorChain()
  {
    return Lists.newArrayList(authenticatorMap.values());
  }

  @LifecycleStart
  public void start()
  {
    for (Authenticator authenticator : authenticatorMap.values()) {
      //authenticator.start();
    }
  }

  @LifecycleStart
  public void stop()
  {
    for (Authenticator authenticator : authenticatorMap.values()) {
      //authenticator.stop();
    }
  }
}