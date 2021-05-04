/*
 * Copyright 2005 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.core.spi;

import java.io.Serializable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

import org.drools.core.WorkingMemory;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.rule.Declaration;
import org.kie.internal.security.KiePolicyHelper;

public interface PredicateExpression
    extends
    Invoker {

    public Object createContext();

    public boolean evaluate(InternalFactHandle handle,
                            Tuple tuple,
                            Declaration[] previousDeclarations,
                            Declaration[] localDeclarations,
                            WorkingMemory workingMemory,
                            Object context ) throws Exception;

    public static boolean isCompiledInvoker(final PredicateExpression expression) {
        return (expression instanceof CompiledInvoker)
                || (expression instanceof SafePredicateExpression && ((SafePredicateExpression) expression).wrapsCompiledInvoker());
    }

    public class SafePredicateExpression implements PredicateExpression, Serializable {
        private static final long serialVersionUID = -4570820770000524010L;
        private PredicateExpression delegate;

        public SafePredicateExpression(PredicateExpression delegate) {
            this.delegate = delegate;
        }

        public Object createContext() {
            return AccessController.doPrivileged((PrivilegedAction<Object>) () -> delegate.createContext(), KiePolicyHelper.getAccessContext());
        }

        public boolean evaluate(final InternalFactHandle handle,
                final Tuple tuple,
                final Declaration[] previousDeclarations,
                final Declaration[] localDeclarations,
                final WorkingMemory workingMemory,
                final Object context) throws Exception {
            return AccessController.doPrivileged(
                    (PrivilegedExceptionAction<Boolean>) () -> delegate.evaluate(handle, tuple, previousDeclarations, localDeclarations, workingMemory, context), KiePolicyHelper.getAccessContext());
        }

        public boolean wrapsCompiledInvoker() {
            return this.delegate instanceof CompiledInvoker;
        }
    }
}
