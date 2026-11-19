/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis.rules;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.type.NestedScopeField;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedObject;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.rule.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that every {@code NESTED_OBJECT} names a mapped nested collection the rows can read, and that it is the whole value of an
 * {@code EVAL} column. {@link #scope} runs while attributes are still unresolved, because name resolution cannot see nested
 * fields. The reference stays in the plan for the nested functions that read through it, until {@link RemoveNestedReferences}.
 */
public final class BindNestedObject extends Rule<LogicalPlan, LogicalPlan> {
    /** Points the name inside {@code NESTED_OBJECT} at the private nested schema. Leaves every other name unresolved. */
    public static LogicalPlan scope(LogicalPlan plan, List<Attribute> childrenOutput) {
        return plan.transformExpressionsOnlyUp(NestedObject.class, object -> {
            List<Attribute> schema = BindNested.privateSchema(plan.children(), childrenOutput);
            return object.transformUp(UnresolvedAttribute.class, attribute -> {
                Attribute field = BindNested.nestedField(attribute, schema);
                return field == null ? attribute : field;
            });
        });
    }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        if (plan.anyMatch(p -> objects(p).isEmpty() == false) == false) {
            return plan;
        }
        BindNested.checkNoReservedNames(plan);
        plan.forEachDown(p -> {
            List<NestedObject> objects = objects(p);
            if (objects.isEmpty()) {
                return;
            }
            if (p instanceof Eval == false) {
                throw BindNested.error(objects.getFirst(), "NESTED_OBJECT is supported in EVAL");
            }
            Eval eval = (Eval) p;
            for (Alias field : eval.fields()) {
                if (field.child() instanceof NestedObject object) {
                    check(object, field, eval);
                } else {
                    field.child().forEachDown(NestedObject.class, object -> {
                        throw BindNested.error(object, "NESTED_OBJECT must be the whole value of an EVAL column");
                    });
                }
            }
        });
        return plan;
    }

    private static List<NestedObject> objects(LogicalPlan plan) {
        List<NestedObject> objects = new ArrayList<>();
        plan.forEachExpression(NestedObject.class, objects::add);
        return objects;
    }

    private static void check(NestedObject object, Alias field, Eval eval) {
        if (object.collection().resolved() == false) {
            return;
        }
        LogicalPlan input = eval.child();
        BindNested.checkReadsIndexRows(input, object, "NESTED_OBJECT");
        if (object.collection() instanceof FieldAttribute named
            && named.field() instanceof NestedScopeField namedScope
            && namedScope.conflict() != null) {
            throw BindNested.error(object, "NESTED_OBJECT cannot read [" + BindNested.publicName(named) + "]: " + namedScope.conflict());
        }
        if ((object.collection() instanceof FieldAttribute attribute
            && attribute.field() instanceof NestedScopeField scope
            && scope.kind() == NestedScopeField.Kind.COLLECTION) == false) {
            throw BindNested.error(object, "NESTED_OBJECT requires a nested collection, not [" + object.collection().sourceText() + "]");
        }
        FieldAttribute collection = (FieldAttribute) object.collection();
        NestedScopeField scope = (NestedScopeField) collection.field();
        if (scope.conflict() != null) {
            throw BindNested.error(object, "NESTED_OBJECT cannot read [" + BindNested.publicName(collection) + "]: " + scope.conflict());
        }
        if (BindNested.lookupSourced(input, collection.id())) {
            throw BindNested.error(
                object,
                "NESTED_OBJECT cannot read ["
                    + BindNested.publicName(collection)
                    + "] from a LOOKUP JOIN index; that index's documents are not the rows being read"
            );
        }
        for (Attribute attribute : BindNested.privateSchema(eval.children(), input.output())) {
            if (attribute instanceof FieldAttribute other && other.field() instanceof NestedScopeField otherScope) {
                if (otherScope.kind() == NestedScopeField.Kind.COLLECTION
                    && (field.name().equals(otherScope.path()) || field.name().startsWith(otherScope.path() + "."))) {
                    throw BindNested.error(
                        object,
                        "NESTED_OBJECT cannot be named [" + field.name() + "]: that name is a nested collection"
                    );
                }
                if (scope.path().startsWith(otherScope.path() + ".")) {
                    throw BindNested.error(object, "NESTED_OBJECT supports one nesting level");
                }
            }
        }
    }
}
