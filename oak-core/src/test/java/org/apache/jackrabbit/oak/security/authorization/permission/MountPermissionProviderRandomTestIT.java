/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.authorization.permission;

import java.security.Principal;
import java.util.Set;
import org.apache.jackrabbit.guava.common.collect.Iterators;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.security.internal.SecurityProviderBuilder;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.authorization.AuthorizationConfiguration;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.PermissionProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

public class MountPermissionProviderRandomTestIT extends AbstractPermissionRandomTestIT {

    private MountInfoProvider mountInfoProvider;

    @Override
    public void before() throws Exception {
        super.before();

        String[] mpxs = new String[] { Iterators.get(allowU.iterator(), allowU.size() / 2) };
        Mounts.Builder builder = Mounts.newBuilder();
        int i = 0;
        for (String p : mpxs) {
            builder.mount("m" + i, p);
            i++;
        }
        mountInfoProvider = builder.build();
    }

    @Override
    protected PermissionProvider candidatePermissionProvider(@NotNull Root root, @NotNull String workspaceName,
            @NotNull Set<Principal> principals) {
        SecurityProvider sp = SecurityProviderBuilder.newBuilder().build();
        AuthorizationConfiguration acConfig = MountUtils.bindMountInfoProvider(sp, mountInfoProvider);
        PermissionProvider composite = acConfig.getPermissionProvider(root, workspaceName, principals);
        Assert.assertTrue(composite instanceof MountPermissionProvider);
        return composite;
    }

}
