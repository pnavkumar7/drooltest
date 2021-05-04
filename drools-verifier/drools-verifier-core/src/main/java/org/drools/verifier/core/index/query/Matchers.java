/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.verifier.core.index.query;

import org.drools.verifier.core.index.matchers.ExactMatcher;
import org.drools.verifier.core.index.matchers.KeyMatcher;
import org.drools.verifier.core.index.matchers.Matcher;
import org.drools.verifier.core.maps.KeyDefinition;

public class Matchers
        extends KeyMatcher {

    public Matchers(final KeyDefinition keyDefinition) {
        super(keyDefinition);
    }

    public ExactMatcher is(final Comparable comparable) {
        return new ExactMatcher(keyDefinition,
                                comparable);
    }

    public Matcher any() {
        return new Matcher(keyDefinition);
    }

    public Matcher isNot(final Comparable comparable) {
        return new ExactMatcher(keyDefinition,
                                comparable,
                                true);
    }
}
