/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.drools.persistence.jpa;

import org.kie.api.runtime.ExecutableRunner;
import org.drools.core.time.InternalSchedulerService;
import org.drools.core.time.Job;
import org.drools.core.time.JobContext;
import org.drools.core.time.JobHandle;
import org.drools.core.time.SelfRemovalJob;
import org.drools.core.time.Trigger;
import org.drools.core.time.impl.CommandServiceTimerJobFactoryManager;
import org.drools.core.time.impl.ThreadSafeTrackableTimeJobFactoryManager;
import org.drools.core.time.impl.TimerJobInstance;

public class JpaTimeJobFactoryManager
        extends ThreadSafeTrackableTimeJobFactoryManager
        implements CommandServiceTimerJobFactoryManager {

    private ExecutableRunner runner;

    public void setRunner(ExecutableRunner runner ) {
        this.runner = runner;
    }

    public ExecutableRunner getRunner() {
        return runner;
    }

    public TimerJobInstance createTimerJobInstance(Job job,
                                                   JobContext ctx,
                                                   Trigger trigger,
                                                   JobHandle handle,
                                                   InternalSchedulerService scheduler) {
        ctx.setJobHandle( handle );

        return new JpaTimerJobInstance( new SelfRemovalJob( job ),
                                        createJobContext( ctx ),
                                        trigger,
                                        handle,
                                        scheduler );
    }
}
