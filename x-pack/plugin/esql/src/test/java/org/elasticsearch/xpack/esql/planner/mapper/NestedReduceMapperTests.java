/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner.mapper;

import org.elasticsearch.Build;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.VersionMode;
import org.elasticsearch.xpack.esql.analysis.AnalyzerNestedExpandTests;
import org.elasticsearch.xpack.esql.analysis.AnalyzerTestCase;
import org.elasticsearch.xpack.esql.expression.function.scalar.nested.NestedReduce;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.FragmentExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.session.Versioned;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.withDefaultLimitWarning;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/** A {@code NESTED} reduction reads the child documents of the rows of the index, so it has to stay in the data-node fragment. */
public class NestedReduceMapperTests extends AnalyzerTestCase {
    public NestedReduceMapperTests(VersionMode versionMode) {
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

    public void testReductionOnTheRowsOfTheIndexStaysInTheFragment() {
        for (String query : List.of("FROM ne | EVAL t = NESTED(SUM(items.qty))", "FROM ne | WHERE NESTED(COUNT(items.qty)) > 1")) {
            List<FragmentExec> fragments = new ArrayList<>();
            map(query).forEachDown(FragmentExec.class, fragments::add);
            assertThat(query, fragments, hasSize(1));
            boolean[] reduces = { false };
            fragments.getFirst().fragment().forEachExpressionDown(NestedReduce.class, reduction -> reduces[0] = true);
            assertThat(query, reduces[0], is(true));
        }
    }

    public void testReductionAfterALimit() {
        assertRefused("FROM ne | LIMIT 3 | EVAL t = NESTED(SUM(items.qty))", "LIMIT 3");
    }

    public void testFilterOnAReductionAfterALimit() {
        assertRefused("FROM ne | LIMIT 3 | WHERE NESTED(COUNT(items.qty)) > 0", "LIMIT 3");
    }

    private PhysicalPlan map(String query) {
        LogicalPlan plan = AnalyzerNestedExpandTests.nestedAnalyzer(minimumVersionAtLeast(NestedReduce.VERSION)).query(query);
        return new Mapper().map(new Versioned<>(plan, TransportVersion.current()));
    }

    private void assertRefused(String query, String preceding) {
        VerificationException e = expectThrows(VerificationException.class, () -> map(query));
        assertThat(e.getMessage(), containsString("NESTED must run on the data nodes, so it cannot follow [" + preceding + "]"));
    }
}
