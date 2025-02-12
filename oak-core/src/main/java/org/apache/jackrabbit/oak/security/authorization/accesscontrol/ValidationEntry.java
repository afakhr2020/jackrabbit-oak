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
package org.apache.jackrabbit.oak.security.authorization.accesscontrol;

import org.apache.jackrabbit.oak.spi.security.authorization.restriction.Restriction;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeBits;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

public class ValidationEntry {

    protected final String principalName;
    protected final PrivilegeBits privilegeBits;
    protected final boolean isAllow;
    protected final Set<Restriction> restrictions;
    // optional index not used for equality/hashcode
    protected final int index;

    public ValidationEntry(@NotNull String principalName, @NotNull PrivilegeBits privilegeBits, boolean isAllow, @NotNull Set<Restriction> restrictions) {
        this.principalName = principalName;
        this.privilegeBits = privilegeBits;
        this.isAllow = isAllow;
        this.restrictions = restrictions;
        this.index = -1;
    }

    public ValidationEntry(@NotNull String principalName, @NotNull PrivilegeBits privilegeBits, boolean isAllow, @NotNull Set<Restriction> restrictions, int index) {
        this.principalName = principalName;
        this.privilegeBits = privilegeBits;
        this.isAllow = isAllow;
        this.restrictions = restrictions;
        this.index = index;
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalName, privilegeBits, restrictions, isAllow);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof ValidationEntry) {
            ValidationEntry other = (ValidationEntry) o;
            return isAllow ==  other.isAllow
                    && Objects.equals(principalName, other.principalName)
                    && privilegeBits.equals(other.privilegeBits)
                    && restrictions.equals(other.restrictions);
        }
        return false;
    }
}