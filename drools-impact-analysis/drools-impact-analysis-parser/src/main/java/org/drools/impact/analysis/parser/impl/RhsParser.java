/*
 * Copyright (c) 2020. Red Hat, Inc. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.impact.analysis.parser.impl;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.drools.compiler.compiler.PackageRegistry;
import org.drools.compiler.lang.descr.RuleDescr;
import org.drools.impact.analysis.model.Rule;
import org.drools.impact.analysis.model.right.ConsequenceAction;
import org.drools.impact.analysis.model.right.ModifiedProperty;
import org.drools.impact.analysis.model.right.ModifyAction;
import org.drools.modelcompiler.builder.generator.Consequence;
import org.drools.modelcompiler.builder.generator.DeclarationSpec;
import org.drools.modelcompiler.builder.generator.RuleContext;

import static org.drools.core.util.StringUtils.ucFirst;
import static org.drools.impact.analysis.parser.impl.ParserUtil.literalToValue;
import static org.drools.impact.analysis.parser.impl.ParserUtil.literalType;

public class RhsParser {

    private final PackageRegistry pkgRegistry;

    public RhsParser( PackageRegistry pkgRegistry ) {
        this.pkgRegistry = pkgRegistry;
    }

    public void parse( RuleDescr ruleDescr, RuleContext context, Rule rule ) {
        BlockStmt ruleVariablesBlock = new BlockStmt();
        MethodCallExpr consequenceExpr = new Consequence(context).createCall( ruleDescr, ruleDescr.getConsequence().toString(), ruleVariablesBlock, false );

        consequenceExpr.findAll(MethodCallExpr.class).stream()
                .filter( m -> m.getScope().map( s -> s.toString().equals( "drools" ) ).orElse( false ) )
                .map( m -> processStatement( context, consequenceExpr, m, ruleVariablesBlock ) )
                .filter( Objects::nonNull )
                .forEach( a -> rule.getRhs().addAction( a ) );
    }

    private ConsequenceAction processStatement( RuleContext context, MethodCallExpr consequenceExpr, MethodCallExpr statement, BlockStmt ruleVariablesBlock ) {
        ConsequenceAction.Type type = decodeAction( statement.getNameAsString() );
        if (type == null) {
            return null;
        }
        if (type == ConsequenceAction.Type.MODIFY) {
            return processModify(context, consequenceExpr, statement, ruleVariablesBlock);
        }
        return processAction(context, consequenceExpr, statement, type);
    }

    private ConsequenceAction processAction( RuleContext context, MethodCallExpr consequenceExpr, MethodCallExpr statement, ConsequenceAction.Type type ) {
        Expression actionArg = statement.getArgument( 0 );
        Class<?> actionClass = null;
        if (actionArg.isNameExpr()) {
            actionClass = context.getDeclarationById( actionArg.toString() ).map( DeclarationSpec::getDeclarationClass ).orElseGet( () -> {
                AssignExpr assignExpr = consequenceExpr.findAll( AssignExpr.class ).stream()
                        .filter( assign -> assign.getTarget().isVariableDeclarationExpr() && (( VariableDeclarationExpr ) assign.getTarget()).getVariable( 0 ).toString().equals( actionArg.toString() ) )
                        .findFirst().orElseThrow( () -> new RuntimeException("Unknown mask: " + actionArg.toString()) );
                String className = assignExpr.getTarget().asVariableDeclarationExpr().getVariable( 0 ).getType().asString();
                try {
                    return pkgRegistry.getTypeResolver().resolveType( className );
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException( e );
                }
            } );
        } else if (actionArg.isLiteralExpr()) {
            actionClass = literalType(actionArg.asLiteralExpr());
        } else if (actionArg.isObjectCreationExpr()) {
            try {
                actionClass = pkgRegistry.getTypeResolver().resolveType( actionArg.asObjectCreationExpr().getType().asString() );
            } catch (ClassNotFoundException e) {
                throw new RuntimeException( e );
            }
        }
        return new ConsequenceAction(type, actionClass);
    }

    private ModifyAction processModify( RuleContext context, MethodCallExpr consequenceExpr, MethodCallExpr statement, BlockStmt ruleVariablesBlock ) {
        String modifiedId = statement.getArgument( 0 ).toString();
        Class<?> modifiedClass = context.getDeclarationById( modifiedId ).orElseThrow( () -> new RuntimeException("Unknown declaration: " + modifiedId) ).getDeclarationClass();

        ModifyAction action = new ModifyAction(modifiedClass);

        if (statement.getArguments().size() > 1) {
            String maskId = statement.getArgument( 1 ).toString();
            AssignExpr maskAssignExpr = ruleVariablesBlock.findAll( AssignExpr.class ).stream()
                    .filter( assign -> (( VariableDeclarationExpr ) assign.getTarget()).getVariable( 0 ).toString().equals( maskId ) )
                    .findFirst().orElseThrow( () -> new RuntimeException("Unknown mask: " + maskId) );

            MethodCallExpr maskMethod = (( MethodCallExpr ) maskAssignExpr.getValue());

            List<MethodCallExpr> modifyingExprs = consequenceExpr.findAll(MethodCallExpr.class).stream()
                    .filter( m -> m.getScope().map( s -> s.toString().equals( modifiedId ) || s.toString().equals( "(" + modifiedId + ")" ) ).orElse( false ) )
                    .collect( Collectors.toList());

            for (int i = 1; i < maskMethod.getArguments().size(); i++) {
                String property = maskMethod.getArgument( i ).asStringLiteralExpr().asString();
                String setter = "set" + ucFirst(property);
                MethodCallExpr setterExpr = modifyingExprs.stream()
                        .filter( m -> m.getNameAsString().equals( setter ) )
                        .filter( m -> m.getArguments().size() == 1 )
                        .findFirst().orElse( null );

                Object value = null;
                if (setterExpr != null && setterExpr.getArgument( 0 ).isLiteralExpr()) {
                    value = literalToValue( setterExpr.getArgument( 0 ).asLiteralExpr() );
                }
                action.addModifiedProperty( new ModifiedProperty(property, value) );
            }
        }

        return action;
    }

    private ConsequenceAction.Type decodeAction(String name) {
        switch (name) {
            case "insert":
                return ConsequenceAction.Type.INSERT;
            case "delete":
                return ConsequenceAction.Type.DELETE;
            case "update":
                return ConsequenceAction.Type.MODIFY;
        }
        return null;
    }
}
