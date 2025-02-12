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

package org.apache.jackrabbit.oak.jcr.delegate;

import java.security.Principal;
import java.util.Iterator;
import java.util.Set;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlPolicy;
import org.apache.jackrabbit.api.security.authorization.PrivilegeCollection;
import org.apache.jackrabbit.oak.jcr.session.operation.SessionOperation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * This implementation of {@code JackrabbitAccessControlManager} delegates back to a
 * delegatee wrapping each call into a {@link SessionOperation} closure.
 *
 * @see SessionDelegate#perform(SessionOperation)
 */
public class JackrabbitAccessControlManagerDelegator implements JackrabbitAccessControlManager {
    
    private final JackrabbitAccessControlManager jackrabbitACManager;
    private final SessionDelegate delegate;
    private final AccessControlManagerDelegator jcrACManager;

    public JackrabbitAccessControlManagerDelegator(@NotNull SessionDelegate delegate, @NotNull JackrabbitAccessControlManager acManager) {
        this.jackrabbitACManager = acManager;
        this.delegate = delegate;
        this.jcrACManager = new AccessControlManagerDelegator(delegate, acManager);
    }

    @NotNull
    @Override
    public JackrabbitAccessControlPolicy[] getApplicablePolicies(@NotNull final Principal principal) throws RepositoryException {
        return delegate.perform(new SessionOperation<JackrabbitAccessControlPolicy[]>("getApplicablePolicies") {
            @Override
            public JackrabbitAccessControlPolicy @NotNull [] perform() throws RepositoryException {
                return jackrabbitACManager.getApplicablePolicies(principal);
            }
        });
    }

    @NotNull
    @Override
    public JackrabbitAccessControlPolicy[] getPolicies(@NotNull final Principal principal) throws RepositoryException {
        return delegate.perform(new SessionOperation<JackrabbitAccessControlPolicy[]>("getPolicies") {
            @Override
            public JackrabbitAccessControlPolicy @NotNull [] perform() throws RepositoryException {
                return jackrabbitACManager.getPolicies(principal);
            }
        });
    }

    @NotNull
    @Override
    public AccessControlPolicy[] getEffectivePolicies(@NotNull final Set<Principal> principals) throws RepositoryException {
        return delegate.perform(new SessionOperation<AccessControlPolicy[]>("getEffectivePolicies") {
            @Override
            public AccessControlPolicy @NotNull [] perform() throws RepositoryException {
                return jackrabbitACManager.getEffectivePolicies(principals);
            }
        });
    }

    @Override
    public @NotNull Iterator<AccessControlPolicy> getEffectivePolicies(@NotNull final Set<Principal> principals, @Nullable final String... absPaths) throws AccessDeniedException, AccessControlException, UnsupportedRepositoryOperationException, RepositoryException {
        return delegate.perform(new SessionOperation<>("getEffectivePolicies(Set,String...)") {
            @Override
            public @NotNull Iterator<AccessControlPolicy> perform() throws RepositoryException {
                return jackrabbitACManager.getEffectivePolicies(principals, absPaths);
            }
        });
    }

    @Override
    public boolean hasPrivileges(@Nullable final String absPath, @NotNull final Set<Principal> principals,
                                 @NotNull final Privilege[] privileges) throws RepositoryException {
        return delegate.perform(new SessionOperation<Boolean>("hasPrivileges") {
            @NotNull
            @Override
            public Boolean perform() throws RepositoryException {
                return jackrabbitACManager.hasPrivileges(absPath, principals, privileges);
            }
        });
    }

    @NotNull
    @Override
    public Privilege[] getPrivileges(@Nullable final String absPath, @NotNull final Set<Principal> principals) throws RepositoryException {
        return delegate.perform(new SessionOperation<Privilege[]>("getPrivileges") {
            @Override
            public Privilege @NotNull [] perform() throws RepositoryException {
                return jackrabbitACManager.getPrivileges(absPath, principals);
            }
        });
    }
    
    @Override
    public @NotNull PrivilegeCollection getPrivilegeCollection(@Nullable String absPath) throws RepositoryException {
        return delegate.perform(new SessionOperation<PrivilegeCollection>("getPrivilegeCollection") {
            @NotNull
            @Override
            public PrivilegeCollection perform() throws RepositoryException {
                return jackrabbitACManager.getPrivilegeCollection(absPath);
            }
        });
    }

    @Override
    public @NotNull PrivilegeCollection getPrivilegeCollection(@Nullable String absPath, @NotNull Set<Principal> principals) throws RepositoryException {
        return delegate.perform(new SessionOperation<PrivilegeCollection>("getPrivilegeCollection") {
            @NotNull
            @Override
            public PrivilegeCollection perform() throws RepositoryException {
                return jackrabbitACManager.getPrivilegeCollection(absPath, principals);
            }
        });
    }
    
    @Override
    public @NotNull PrivilegeCollection privilegeCollectionFromNames(@NotNull String... privilegeNames) throws RepositoryException {
        return delegate.perform(new SessionOperation<PrivilegeCollection>("privilegeCollectionFromNames") {
            @NotNull
            @Override
            public PrivilegeCollection perform() throws RepositoryException {
                return jackrabbitACManager.privilegeCollectionFromNames(privilegeNames);
            }
        });
    }

    @Override
    public Privilege[] getSupportedPrivileges(String absPath) throws RepositoryException {
        return jcrACManager.getSupportedPrivileges(absPath);
    }

    @Override
    public Privilege privilegeFromName(String privilegeName) throws RepositoryException {
        return jcrACManager.privilegeFromName(privilegeName);
    }

    @Override
    public boolean hasPrivileges(String absPath, Privilege[] privileges) throws RepositoryException {
        return jcrACManager.hasPrivileges(absPath, privileges);
    }

    @Override
    public Privilege[] getPrivileges(String absPath) throws RepositoryException {
        return jcrACManager.getPrivileges(absPath);
    }

    @Override
    public AccessControlPolicy[] getPolicies(String absPath) throws RepositoryException {
        return jcrACManager.getPolicies(absPath);
    }

    @Override
    public AccessControlPolicy[] getEffectivePolicies(String absPath) throws RepositoryException {
        return jcrACManager.getEffectivePolicies(absPath);
    }

    @Override
    public AccessControlPolicyIterator getApplicablePolicies(String absPath) throws RepositoryException {
        return jcrACManager.getApplicablePolicies(absPath);
    }

    @Override
    public void setPolicy(String absPath, AccessControlPolicy policy) throws RepositoryException {
        jcrACManager.setPolicy(absPath, policy);
    }

    @Override
    public void removePolicy(String absPath, AccessControlPolicy policy) throws RepositoryException {
        jcrACManager.removePolicy(absPath, policy);
    }
}
