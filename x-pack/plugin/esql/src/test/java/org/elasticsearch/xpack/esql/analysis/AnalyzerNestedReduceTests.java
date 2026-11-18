/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis;

import org.elasticsearch.Build;
import org.elasticsearch.test.TransportVersionUtils;
import org.elasticsearch.xpack.esql.TestAnalyzer;
import org.elasticsearch.xpack.esql.VersionMode;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.NestedScopeField;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedReduce;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedStats;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.withDefaultLimitWarning;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Binding and rejection of {@code NESTED} reductions during analysis, over the indices {@link AnalyzerNestedExpandTests}
 * describes: {@code ne} has {@code id}, nested {@code items} with {@code sku}, {@code qty}, {@code loc} and nested
 * {@code items.parts}, and nested {@code events} with {@code start}.
 */
public class AnalyzerNestedReduceTests extends AnalyzerTestCase {
    public AnalyzerNestedReduceTests(VersionMode versionMode) {
        super(versionMode);
    }

    @Before
    public void requireSnapshot() {
        assumeTrue("NESTED is snapshot-only", Build.current().isSnapshot());
    }

    @Override
    protected List<String> filteredWarnings() {
        return withDefaultLimitWarning(super.filteredWarnings());
    }

    public void testSumBindsToNestedReduce() {
        NestedReduce reduce = onlyReduce(nestedAnalyzer().query("FROM ne | EVAL t = NESTED(SUM(items.qty))"));
        assertThat(reduce.dataType(), equalTo(DataType.LONG));
        assertThat(reduce.nullable(), equalTo(Nullability.TRUE));
    }

    public void testCollectionFormBindsCountStar() {
        NestedReduce reduce = onlyReduce(nestedAnalyzer().query("FROM ne | EVAL n = NESTED(items, COUNT(*))"));
        assertThat(reduce.dataType(), equalTo(DataType.LONG));
        assertThat(reduce.nullable(), equalTo(Nullability.FALSE));
    }

    public void testCountOfAFieldCannotBeNull() {
        NestedReduce reduce = onlyReduce(nestedAnalyzer().query("FROM ne | EVAL c = NESTED(COUNT(items.qty))"));
        assertThat(reduce.nullable(), equalTo(Nullability.FALSE));
    }

    public void testAvgIsDouble() {
        assertThat(onlyReduce(nestedAnalyzer().query("FROM ne | EVAL a = NESTED(AVG(items.qty))")).dataType(), equalTo(DataType.DOUBLE));
    }

    public void testMinAndMaxKeepTheChildType() {
        assertThat(onlyReduce(nestedAnalyzer().query("FROM ne | EVAL m = NESTED(MIN(items.sku))")).dataType(), equalTo(DataType.KEYWORD));
        assertThat(onlyReduce(nestedAnalyzer().query("FROM ne | EVAL m = NESTED(MAX(items.qty))")).dataType(), equalTo(DataType.LONG));
    }

    public void testReductionOfAnExpressionOverChildFields() {
        NestedReduce reduce = onlyReduce(nestedAnalyzer().query("FROM ne | EVAL t = NESTED(SUM(items.qty * 2 + length(items.sku)))"));
        assertThat(reduce.dataType(), equalTo(DataType.LONG));
    }

    public void testReductionInWhere() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | WHERE NESTED(COUNT(items.qty)) > 1");
        List<Filter> filters = new ArrayList<>();
        plan.forEachDown(Filter.class, filters::add);
        assertThat(filters, hasSize(1));
        List<NestedReduce> reductions = new ArrayList<>();
        filters.getFirst().forEachExpression(NestedReduce.class, reductions::add);
        assertThat(reductions, hasSize(1));
    }

    public void testReductionsOverTwoCollectionsInOneQuery() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL a = NESTED(SUM(items.qty)), b = NESTED(events, COUNT(*))");
        assertThat(reduces(plan), hasSize(2));
    }

    public void testNothingIsLeftUnlowered() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL a = NESTED(SUM(items.qty)), b = NESTED(items, COUNT(*)) | WHERE a > 1");
        List<NestedStats> unlowered = new ArrayList<>();
        plan.forEachExpressionDown(NestedStats.class, unlowered::add);
        assertThat(unlowered, is(empty()));
    }

    public void testOutputHasNoNestedScope() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL t = NESTED(SUM(items.qty))");
        assertThat(names(plan.output()), hasItems("id", "t"));
        assertThat(names(plan.output()), everyItem(not(startsWith(NestedScopeField.PREFIX))));
        plan.forEachDown(
            EsRelation.class,
            relation -> assertThat(names(relation.output()), everyItem(not(startsWith(NestedScopeField.PREFIX))))
        );
    }

    public void testChildrenAreUnknownOutsideTheReduction() {
        nestedAnalyzer().error("FROM ne | EVAL t = NESTED(SUM(items.qty)) | KEEP items.qty", containsString("Unknown column [items.qty]"));
    }

    public void testCountStarNeedsTheCollection() {
        nestedAnalyzer().error(
            "FROM ne | EVAL n = NESTED(COUNT(*))",
            containsString("NESTED(COUNT(*)) needs the collection to count, such as NESTED(items, COUNT(*))")
        );
    }

    public void testNotAReduction() {
        nestedAnalyzer().error("FROM ne | EVAL x = NESTED(items.qty)", containsString("NESTED requires a reduction such as SUM or COUNT"));
    }

    public void testUnsupportedAggregate() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(MEDIAN(items.qty))",
            containsString("NESTED supports COUNT, SUM, MIN, MAX and AVG only, not [MEDIAN(items.qty)]")
        );
    }

    public void testTooManyArguments() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(items, COUNT(*), 1)",
            containsString("NESTED takes a reduction, or a collection and a reduction")
        );
    }

    public void testNestedInsideNested() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(SUM(NESTED(SUM(items.qty))))",
            containsString("NESTED does not allow inner scopes or full-text functions")
        );
    }

    public void testFieldsFromTwoNestedPaths() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(SUM(items.qty + events.start))",
            containsString("NESTED requires one nested path")
        );
    }

    public void testFieldOutsideTheNamedCollection() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(items, SUM(events.start))",
            containsString("Field [events.start] is outside the nested scope [items]")
        );
    }

    public void testParentFieldInsideTheBody() {
        nestedAnalyzer().error("FROM ne | EVAL x = NESTED(items, COUNT(id))", containsString("Field [id] is outside the nested scope"));
    }

    public void testCollectionIsNotNested() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(id, COUNT(*))",
            containsString("NESTED requires a nested collection first, not [id]")
        );
    }

    public void testChildTypeWithoutReductionSupport() {
        nestedAnalyzer().error(
            "FROM ne | EVAL x = NESTED(COUNT(items.loc))",
            containsString("NESTED does not support child type [GEO_POINT] for [items.loc]")
        );
    }

    public void testOneNestingLevel() {
        nestedAnalyzer().error("FROM ne | EVAL x = NESTED(COUNT(items.parts.pn))", containsString("NESTED supports one nesting level"));
    }

    public void testOnlyInEvalAndWhere() {
        nestedAnalyzer().error("FROM ne | SORT NESTED(SUM(items.qty))", containsString("NESTED is supported in EVAL and WHERE"));
        nestedAnalyzer().error("FROM ne | STATS t = NESTED(SUM(items.qty))", containsString("NESTED is supported in EVAL and WHERE"));
    }

    public void testAfterStats() {
        nestedAnalyzer().error(
            "FROM ne | STATS c = COUNT(*) | EVAL t = NESTED(SUM(items.qty))",
            containsString("NESTED reads each row's nested documents, so it cannot follow [STATS c = COUNT(*)]")
        );
    }

    public void testAfterInlineStats() {
        nestedAnalyzer().error(
            "FROM ne | INLINE STATS c = COUNT(*) BY id | EVAL t = NESTED(SUM(items.qty))",
            containsString("NESTED reads each row's nested documents, so it cannot follow [INLINE STATS c = COUNT(*) BY id]")
        );
    }

    public void testCollectionOfALookupIndex() {
        nestedAnalyzer().error(
            "FROM ne_orders | LOOKUP JOIN ne_lookup ON id | EVAL t = NESTED(SUM(items.qty))",
            containsString("NESTED cannot read [items.qty] from a LOOKUP JOIN index; that index's documents are not the rows being read")
        );
    }

    public void testCollectionOfTheDrivingIndexAfterALookupJoin() {
        NestedReduce reduce = onlyReduce(
            nestedAnalyzer().query("FROM ne | LOOKUP JOIN ne_lookup_note ON id | EVAL t = NESTED(SUM(items.qty))")
        );
        assertThat(reduce.dataType(), equalTo(DataType.LONG));
    }

    public void testColumnNamedLikeTheCollection() {
        nestedAnalyzer().error(
            "FROM ne | EVAL items = 1 | EVAL n = NESTED(items, COUNT(*))",
            containsString("NESTED cannot use [items]: a column of that name replaces it here")
        );
    }

    public void testIndicesDisagreeOnTheNestedPath() {
        nestedAnalyzer().error(
            "FROM ne_conflict | EVAL n = NESTED(COUNT(items.sub.v))",
            containsString("NESTED cannot read [items.sub.v]")
        );
    }

    public void testBeforeTheReductionAUserCannotNameThePrivateSchema() {
        nestedAnalyzer().error(
            "FROM ne | EVAL `$$nested_reductions.items.qty` = 1 | EVAL t = NESTED(SUM(items.qty))",
            containsString("NESTED uses reserved internal schema names")
        );
    }

    public void testOlderNode() {
        nestedAnalyzer().minimumTransportVersion(TransportVersionUtils.randomVersionNotSupporting(NestedReduce.VERSION))
            .error("FROM ne | EVAL t = NESTED(SUM(items.qty))", containsString("NESTED requires all nodes to support nested reductions"));
    }

    public void testUnmappedNameInTheBodyIsNotInTheNestedScopeByDefault() {
        nestedAnalyzer().error(
            "FROM ne | EVAL t = NESTED(SUM(items.undeclared))",
            containsString("Field [items.undeclared] is outside the nested scope")
        );
    }

    /** An unmapped name under a nested path is an unmapped parent field, which the nested scope of the body does not see. */
    public void testUnmappedNameInTheBodyIsNotInTheNestedScope() {
        nestedAnalyzer().statementError(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL t = NESTED(SUM(items.undeclared))",
            containsString("Field [items.undeclared] is outside the nested scope")
        );
    }

    public void testUnmappedNameOutsideTheBodyStillLoadsFromTheParent() {
        LogicalPlan plan = nestedAnalyzer().statement(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL u = items.undeclared, t = NESTED(SUM(items.qty)) | KEEP id, u, t"
        );
        assertThat(reduces(plan), hasSize(1));
        List<EsRelation> relations = new ArrayList<>();
        plan.forEachDown(EsRelation.class, relations::add);
        assertThat(relations, hasSize(1));
        assertThat(names(relations.getFirst().output()), hasItem("items.undeclared"));
    }

    public void testUnmappedNameBeforeAndAfterTheReductionResolvesTheSame() {
        for (String query : List.of(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL u = items.undeclared | EVAL t = NESTED(SUM(items.qty)) | KEEP id, u, t",
            "SET unmapped_fields=\"load\"; FROM ne | EVAL t = NESTED(SUM(items.qty)) | EVAL u = items.undeclared | KEEP id, u, t"
        )) {
            LogicalPlan plan = nestedAnalyzer().statement(query);
            List<EsRelation> relations = new ArrayList<>();
            plan.forEachDown(EsRelation.class, relations::add);
            assertThat(query, names(relations.getFirst().output()), hasItem("items.undeclared"));
            assertThat(query, names(plan.output()), hasItems("id", "u", "t"));
        }
    }

    private TestAnalyzer nestedAnalyzer() {
        return AnalyzerNestedExpandTests.nestedAnalyzer(minimumVersionAtLeast(NestedReduce.VERSION));
    }

    private static List<NestedReduce> reduces(LogicalPlan plan) {
        List<NestedReduce> found = new ArrayList<>();
        plan.forEachExpressionDown(NestedReduce.class, found::add);
        return found;
    }

    private static NestedReduce onlyReduce(LogicalPlan plan) {
        List<NestedReduce> found = reduces(plan);
        assertThat(found, hasSize(1));
        return found.getFirst();
    }

    private static List<String> names(List<Attribute> attributes) {
        return attributes.stream().map(Attribute::name).toList();
    }
}
