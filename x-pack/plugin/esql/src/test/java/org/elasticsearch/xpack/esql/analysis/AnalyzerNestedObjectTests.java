/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis;

import org.elasticsearch.Build;
import org.elasticsearch.xpack.esql.TestAnalyzer;
import org.elasticsearch.xpack.esql.VersionMode;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.NestedScopeField;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedObject;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedPredicate;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedReduce;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.NestedExpand;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.withDefaultLimitWarning;
import static org.hamcrest.Matchers.contains;
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
 * {@code NESTED_OBJECT} names a nested collection so that later nested functions and {@code NESTED_EXPAND} can read it
 * through the name. Analysis resolves those names and then removes the reference, so it is never a column. The indices are
 * the ones {@link AnalyzerNestedExpandTests} describes: {@code ne} has {@code id}, nested {@code items} with {@code sku},
 * {@code qty}, {@code loc} and nested {@code items.parts}, and nested {@code events} with {@code start}.
 */
public class AnalyzerNestedObjectTests extends AnalyzerTestCase {
    private static final String REFERENCE_ERROR = "] is a reference to the nested collection [";

    public AnalyzerNestedObjectTests(VersionMode versionMode) {
        super(versionMode);
    }

    @Before
    public void requireSnapshot() {
        assumeTrue("NESTED_OBJECT is snapshot-only", Build.current().isSnapshot());
    }

    @Override
    protected List<String> filteredWarnings() {
        return withDefaultLimitWarning(super.filteredWarnings());
    }

    public void testReductionReadsThroughTheReference() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.qty))");
        NestedReduce reduce = onlyReduce(plan);
        assertThat(reduce.path(), equalTo("items"));
        assertThat(reduce.relativePaths(), contains("qty"));
        assertThat(reduce.dataType(), equalTo(DataType.LONG));
    }

    public void testCollectionFormReadsThroughTheReference() {
        NestedReduce reduce = onlyReduce(nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(events) | EVAL n = NESTED(o, COUNT(*))"));
        assertThat(reduce.path(), equalTo("events"));
    }

    public void testPredicateReadsThroughTheReference() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | WHERE NESTED_ANY(o.sku == \"a\")");
        List<NestedPredicate> predicates = new ArrayList<>();
        plan.forEachExpressionDown(NestedPredicate.class, predicates::add);
        assertThat(predicates, hasSize(1));
        assertThat(predicates.getFirst().path(), equalTo("items"));
    }

    public void testCollectionFormPredicateReadsThroughTheReference() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | WHERE NESTED_ANY(o, true)");
        List<NestedPredicate> predicates = new ArrayList<>();
        plan.forEachExpressionDown(NestedPredicate.class, predicates::add);
        assertThat(predicates, hasSize(1));
        assertThat(predicates.getFirst().path(), equalTo("items"));
    }

    public void testExpansionNamesItsColumnsAfterTheReference() {
        LogicalPlan plan = nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | NESTED_EXPAND o");
        NestedExpand expand = onlyExpand(plan);
        assertThat(expand.path(), equalTo("items"));
        assertThat(names(expand.expanded()), contains("o.loc", "o.qty", "o.sku"));
        assertThat(expand.paths(), contains("loc", "qty", "sku"));
        assertThat(names(plan.output()), hasItems("id", "o.qty", "o.sku"));
    }

    public void testReferenceFollowsARename() {
        NestedReduce reduce = onlyReduce(
            nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | RENAME o AS p | EVAL t = NESTED(SUM(p.qty))")
        );
        assertThat(reduce.path(), equalTo("items"));
    }

    public void testRenamedReferenceIsExpandedUnderTheNewName() {
        NestedExpand expand = onlyExpand(
            nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | RENAME o AS p | NESTED_EXPAND p")
        );
        assertThat(expand.path(), equalTo("items"));
        assertThat(names(expand.expanded()), contains("p.loc", "p.qty", "p.sku"));
    }

    public void testReferenceSurvivesKeepAndDrop() {
        assertThat(
            onlyReduce(nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP id, o | EVAL t = NESTED(SUM(o.qty))")).path(),
            equalTo("items")
        );
        assertThat(
            onlyReduce(nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | DROP id | EVAL t = NESTED(SUM(o.qty))")).path(),
            equalTo("items")
        );
    }

    public void testReferenceSurvivesAWildcardKeep() {
        assertThat(
            onlyReduce(nestedAnalyzer().query("FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP * | EVAL t = NESTED(SUM(o.qty))")).path(),
            equalTo("items")
        );
    }

    public void testTwoReferencesToTwoCollections() {
        LogicalPlan plan = nestedAnalyzer().query(
            "FROM ne | EVAL a = NESTED_OBJECT(items), b = NESTED_OBJECT(events) | EVAL n = NESTED(SUM(a.qty)), m = NESTED(COUNT(b.start))"
        );
        assertThat(reduces(plan).stream().map(NestedReduce::path).toList(), contains("items", "events"));
    }

    public void testTwoReferencesToTheSameCollection() {
        LogicalPlan plan = nestedAnalyzer().query(
            "FROM ne | EVAL a = NESTED_OBJECT(items), b = NESTED_OBJECT(items) | EVAL n = NESTED(SUM(a.qty)), m = NESTED(COUNT(b.sku))"
        );
        assertThat(reduces(plan).stream().map(NestedReduce::path).toList(), contains("items", "items"));
    }

    public void testALaterReferenceOfTheSameNameReplacesTheEarlierOne() {
        NestedReduce reduce = onlyReduce(
            nestedAnalyzer().query(
                "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL o = NESTED_OBJECT(events) | EVAL t = NESTED(COUNT(o.start))"
            )
        );
        assertThat(reduce.path(), equalTo("events"));
    }

    public void testOrdinaryColumnReplacingAReferenceIsNotAReference() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL o = 1 | EVAL t = NESTED(SUM(o.qty))",
            containsString("Field [o.qty] is outside the nested scope")
        );
    }

    public void testReadingThroughTheReferenceEqualsReadingTheCollection() {
        LogicalPlan viaReference = nestedAnalyzer().query(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.qty)), n = NESTED(o, COUNT(*))"
                + " | WHERE NESTED_ANY(o.sku == \"a\")"
        );
        LogicalPlan direct = nestedAnalyzer().query(
            "FROM ne | EVAL t = NESTED(SUM(items.qty)), n = NESTED(items, COUNT(*)) | WHERE NESTED_ANY(items.sku == \"a\")"
        );
        assertThat(names(viaReference.output()), equalTo(names(direct.output())));
        assertThat(
            reduces(viaReference).stream().map(r -> r.path() + r.relativePaths() + r.dataType()).toList(),
            equalTo(reduces(direct).stream().map(r -> r.path() + r.relativePaths() + r.dataType()).toList())
        );
    }

    public void testReferenceIsNotAColumn() {
        for (String query : List.of(
            "FROM ne | EVAL o = NESTED_OBJECT(items)",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP id, o",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP *",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | RENAME o AS p",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.qty))",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | NESTED_EXPAND o"
        )) {
            LogicalPlan plan = nestedAnalyzer().query(query);
            assertThat(query, names(plan.output()), hasItem("id"));
            assertThat(query, names(plan.output()), everyItem(not(equalTo("o"))));
            assertThat(query, names(plan.output()), everyItem(not(equalTo("p"))));
            assertThat(query, names(plan.output()), everyItem(not(startsWith(NestedScopeField.PREFIX))));
            assertThat(query, plan.output().stream().filter(a -> a.dataType() == DataType.OBJECT).toList(), is(empty()));
            List<NestedObject> unlowered = new ArrayList<>();
            plan.forEachExpressionDown(NestedObject.class, unlowered::add);
            assertThat(query, unlowered, is(empty()));
            plan.forEachDown(
                EsRelation.class,
                relation -> assertThat(query, names(relation.output()), everyItem(not(startsWith(NestedScopeField.PREFIX))))
            );
        }
    }

    public void testArgumentIsNotACollection() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(items.qty)",
            containsString("NESTED_OBJECT requires a nested collection, not [items.qty]")
        );
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(id)",
            containsString("NESTED_OBJECT requires a nested collection, not [id]")
        );
    }

    public void testArgumentIsAnUnknownName() {
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(missing)", containsString("Unknown column [missing]"));
    }

    public void testUnknownColumnAfterAReferenceIsReported() {
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP missing", containsString("Unknown column [missing]"));
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL x = missing", containsString("Unknown column [missing]"));
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items) | WHERE missing > 1", containsString("Unknown column [missing]"));
    }

    public void testArgumentIsAnExpression() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(items.qty + 1)",
            containsString("NESTED_OBJECT requires a nested collection, not [items.qty + 1]")
        );
    }

    public void testOnlyInEval() {
        nestedAnalyzer().error("FROM ne | WHERE NESTED_OBJECT(items) IS NOT NULL", containsString("NESTED_OBJECT is supported in EVAL"));
        nestedAnalyzer().error(
            "FROM ne | STATS c = COUNT(*) BY o = NESTED_OBJECT(items)",
            containsString("NESTED_OBJECT is supported in EVAL")
        );
        nestedAnalyzer().error("FROM ne | SORT NESTED_OBJECT(items)", containsString("NESTED_OBJECT is supported in EVAL"));
    }

    public void testOnlyAsTheWholeAssignment() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = COALESCE(NESTED_OBJECT(items), NESTED_OBJECT(events))",
            containsString("NESTED_OBJECT must be the whole value of an EVAL column")
        );
    }

    public void testReferenceIsNotAValue() {
        for (String query : List.of(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | WHERE o IS NOT NULL",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | STATS c = COUNT(*) BY o",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | SORT o",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL x = o",
            "FROM ne | EVAL o = NESTED_OBJECT(items) | RENAME o AS p | WHERE p IS NULL"
        )) {
            nestedAnalyzer().error(query, containsString(REFERENCE_ERROR + "items]"));
        }
    }

    public void testNameOfAReferenceCannotBeANestedCollection() {
        nestedAnalyzer().error(
            "FROM ne | EVAL items = NESTED_OBJECT(items)",
            containsString("NESTED_OBJECT cannot be named [items]: that name is a nested collection")
        );
        nestedAnalyzer().error(
            "FROM ne | EVAL items = NESTED_OBJECT(events)",
            containsString("NESTED_OBJECT cannot be named [items]: that name is a nested collection")
        );
        nestedAnalyzer().error(
            "FROM ne | EVAL items.qty = NESTED_OBJECT(events)",
            containsString("NESTED_OBJECT cannot be named [items.qty]: that name is a nested collection")
        );
    }

    public void testAfterStats() {
        nestedAnalyzer().error(
            "FROM ne | STATS c = COUNT(*) | EVAL o = NESTED_OBJECT(items)",
            containsString("NESTED_OBJECT reads each row's nested documents, so it cannot follow [STATS c = COUNT(*)]")
        );
    }

    public void testCollectionOfALookupIndex() {
        nestedAnalyzer().error(
            "FROM ne_orders | LOOKUP JOIN ne_lookup ON id | EVAL o = NESTED_OBJECT(items)",
            containsString("NESTED_OBJECT cannot read [items] from a LOOKUP JOIN index; that index's documents are not the rows being read")
        );
    }

    public void testIndicesDisagreeOnTheNestedPath() {
        nestedAnalyzer().error(
            "FROM ne_conflict | EVAL o = NESTED_OBJECT(items.sub) | EVAL n = NESTED(o, COUNT(*))",
            containsString("NESTED_OBJECT cannot read [items.sub]")
        );
    }

    public void testReferenceIsNotAColumnOfAFork() {
        LogicalPlan plan = nestedAnalyzer().query(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | FORK (WHERE id == \"a\") (WHERE id == \"b\") | KEEP id, _fork"
        );
        assertThat(names(plan.output()), everyItem(not(equalTo("o"))));
        assertThat(plan.output().stream().filter(a -> a.dataType() == DataType.OBJECT).toList(), is(empty()));
    }

    public void testReferenceCannotBeReadAfterAFork() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | FORK (WHERE id == \"a\") (WHERE id == \"b\") | EVAL t = NESTED(SUM(o.qty))",
            containsString("o.qty")
        );
    }

    public void testNestedOneLevelOnly() {
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items.parts)", containsString("NESTED_OBJECT supports one nesting level"));
    }

    public void testBeforeTheReferenceAUserCannotNameThePrivateSchema() {
        nestedAnalyzer().error(
            "FROM ne | EVAL `$$nested_reductions.items.qty` = 1 | EVAL o = NESTED_OBJECT(items)",
            containsString("NESTED uses reserved internal schema names")
        );
    }

    public void testUndeclaredNameThroughTheReferenceIsNotInTheNestedScope() {
        nestedAnalyzer().error(
            "FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.undeclared))",
            containsString("outside the nested scope")
        );
    }

    /** An unmapped name under a nested path is an unmapped parent field, which the nested scope of the body does not see. */
    public void testUndeclaredNameThroughTheReferenceIsNotInTheNestedScopeWhenLoading() {
        nestedAnalyzer().statementError(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.undeclared))",
            containsString("outside the nested scope")
        );
    }

    public void testMappedChildThroughTheReferenceIsUnknownOutsideTheNestedFunctions() {
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items) | KEEP o.qty", containsString("Unknown column [o.qty]"));
        nestedAnalyzer().error("FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL u = o.qty", containsString("Unknown column [o.qty]"));
    }

    public void testUndeclaredNameStaysAParentFieldBesideAReference() {
        for (String query : List.of(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL u = items.undeclared | EVAL o = NESTED_OBJECT(items)"
                + " | EVAL t = NESTED(SUM(o.qty)) | KEEP id, u, t",
            "SET unmapped_fields=\"load\"; FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL t = NESTED(SUM(o.qty))"
                + " | EVAL u = items.undeclared | KEEP id, u, t"
        )) {
            LogicalPlan plan = nestedAnalyzer().statement(query);
            List<EsRelation> relations = new ArrayList<>();
            plan.forEachDown(EsRelation.class, relations::add);
            assertThat(query, names(relations.getFirst().output()), hasItem("items.undeclared"));
            assertThat(query, names(plan.output()), contains("id", "u", "t"));
        }
    }

    public void testUnmappedNameOutsideTheNestedFunctionsKeepsItsOrdinaryMeaningWhenLoading() {
        LogicalPlan withReference = nestedAnalyzer().statement(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL o = NESTED_OBJECT(items) | EVAL u = o.undeclared | KEEP id, u"
        );
        LogicalPlan withOrdinaryColumn = nestedAnalyzer().statement(
            "SET unmapped_fields=\"load\"; FROM ne | EVAL o = 1 | EVAL u = o.undeclared | KEEP id, u"
        );
        assertThat(names(withReference.output()), equalTo(names(withOrdinaryColumn.output())));
        List<EsRelation> relations = new ArrayList<>();
        withReference.forEachDown(EsRelation.class, relations::add);
        assertThat(names(relations.getFirst().output()), hasItem("o.undeclared"));
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

    private static NestedExpand onlyExpand(LogicalPlan plan) {
        List<NestedExpand> found = new ArrayList<>();
        plan.forEachDown(NestedExpand.class, found::add);
        assertThat(found, hasSize(1));
        return found.getFirst();
    }

    private static List<String> names(List<Attribute> attributes) {
        return attributes.stream().map(Attribute::name).toList();
    }
}
