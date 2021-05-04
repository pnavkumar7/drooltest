/*
 * Copyright 2010 JBoss Inc
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

package org.drools.workbench.models.guided.dtable.shared.model.legacy;

import java.util.ArrayList;
import java.util.List;

@Deprecated
public class GuidedDecisionTable {

    public static final int INTERNAL_ELEMENTS = 2;

    public String tableName;

    public String parentName;

    public List<MetadataCol> metadataCols = new ArrayList<MetadataCol>();

    public List<AttributeCol> attributeCols = new ArrayList<AttributeCol>();

    public List<ConditionCol> conditionCols = new ArrayList<ConditionCol>();

    public List<ActionCol> actionCols = new ArrayList<ActionCol>();

    public String[][] data = new String[ 0 ][ 0 ];

    public int descriptionWidth = -1;

    public String groupField;

}
