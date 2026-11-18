/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.expression.function.scalar.nested;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.lucene.IndexedByShardId;
import org.elasticsearch.compute.lucene.IndexedByShardIdFromSingleton;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.test.TestDriverRunner;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.mapper.EvaluatorMapper;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Avg;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.planner.EsPhysicalOperationProviders;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;

/**
 * Evaluates {@link NestedReduce} over a real nested index. Parent {@code i} has {@code c = i % 4} children, child {@code j}
 * with {@code price = 10 * i + j} and {@code note = "n<j>"}, so every reduction has a closed form to compare with.
 */
public class NestedReduceOperatorTests extends NestedIndexOperatorTestCase {
    private static final ReferenceAttribute PRICE = new ReferenceAttribute(Source.EMPTY, "items.price", DataType.LONG);
    private static final ReferenceAttribute NOTE = new ReferenceAttribute(Source.EMPTY, "items.note", DataType.TEXT);

    @Override
    protected Operator.OperatorFactory simple(SimpleOptions options) {
        return reduce(new Sum(Source.EMPTY, PRICE));
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        List<Integer> parents = new ArrayList<>();
        for (Page page : input) {
            for (int p = 0; p < page.getPositionCount(); p++) {
                parents.add(parent(page, p));
            }
        }
        assertThat(values(results), equalTo(expected(parents, NestedReduceOperatorTests::sum)));
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return startsWith("EvalOperator[evaluator=");
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return startsWith("EvalOperator[evaluator=");
    }

    public void testSum() {
        assertThat(reduceAllParents(new Sum(Source.EMPTY, PRICE)), equalTo(expected(allParents(), NestedReduceOperatorTests::sum)));
    }

    public void testCount() {
        assertThat(reduceAllParents(new Count(Source.EMPTY, PRICE)), equalTo(expected(allParents(), parent -> (long) children(parent))));
    }

    public void testCountOfATextFieldReadFromTheParentSource() {
        assertThat(reduceAllParents(new Count(Source.EMPTY, NOTE)), equalTo(expected(allParents(), parent -> (long) children(parent))));
    }

    public void testMin() {
        assertThat(
            reduceAllParents(new Min(Source.EMPTY, PRICE)),
            equalTo(expected(allParents(), parent -> children(parent) == 0 ? null : 10L * parent))
        );
    }

    public void testMax() {
        assertThat(
            reduceAllParents(new Max(Source.EMPTY, PRICE)),
            equalTo(expected(allParents(), parent -> children(parent) == 0 ? null : 10L * parent + children(parent) - 1))
        );
    }

    public void testAvgIsADouble() {
        assertThat(reduceAllParents(new Avg(Source.EMPTY, PRICE)), equalTo(expected(allParents(), parent -> {
            Long sum = sum(parent);
            return sum == null ? null : sum / (double) children(parent);
        })));
    }

    /** A parent without children counts 0 and has no value for any other reduction. */
    public void testParentWithoutChildren() {
        List<Integer> empty = List.of(0, 4, 8);
        assertThat(reduce(new Count(Source.EMPTY, PRICE), empty), equalTo(expected(empty, parent -> 0L)));
        assertThat(reduce(new Sum(Source.EMPTY, PRICE), empty), equalTo(expected(empty, parent -> null)));
        assertThat(reduce(new Min(Source.EMPTY, PRICE), empty), equalTo(expected(empty, parent -> null)));
        assertThat(reduce(new Max(Source.EMPTY, PRICE), empty), equalTo(expected(empty, parent -> null)));
        assertThat(reduce(new Avg(Source.EMPTY, PRICE), empty), equalTo(expected(empty, parent -> null)));
    }

    /** More children than {@link NestedChildren#PAGE_SIZE}, so a parent's children are read over more than one page. */
    public void testChildrenSpanReadPages() {
        int children = 0;
        for (int i = 0; i < PARENTS; i++) {
            children += children(i);
        }
        assertThat(children, greaterThan(NestedChildren.PAGE_SIZE));
        assertThat(reduceAllParents(new Sum(Source.EMPTY, PRICE)), equalTo(expected(allParents(), NestedReduceOperatorTests::sum)));
    }

    public void testBreakerIsReleased() {
        for (Expression body : List.of(
            new Sum(Source.EMPTY, PRICE),
            new Count(Source.EMPTY, PRICE),
            new Min(Source.EMPTY, PRICE),
            new Max(Source.EMPTY, PRICE),
            new Avg(Source.EMPTY, PRICE)
        )) {
            var context = driverContext();
            List<Page> results = new TestDriverRunner().builder(context)
                .input(List.of(parents(context.blockFactory(), 0, PARENTS)))
                .run(reduce(body).get(context));
            for (Page page : results) {
                page.releaseBlocks();
            }
            assertThat(body.toString(), context.breaker().getUsed(), equalTo(0L));
        }
    }

    private Operator.OperatorFactory reduce(Expression body) {
        NestedReduce reduce = new NestedReduce(Source.EMPTY, "items", body, List.of(PRICE, NOTE), List.of("price", "note"));
        return new EvalOperator.EvalOperatorFactory(reduce.toEvaluator(toEvaluator()));
    }

    private EvaluatorMapper.ToEvaluator toEvaluator() {
        return new EvaluatorMapper.ToEvaluator() {
            @Override
            public ExpressionEvaluator.Factory apply(Expression expression) {
                throw new UnsupportedOperationException("NestedReduce builds the evaluators of its body from the child fields");
            }

            @Override
            public FoldContext foldCtx() {
                return FoldContext.small();
            }

            @Override
            public IndexedByShardId<? extends EsPhysicalOperationProviders.ShardContext> shardContexts() {
                return new IndexedByShardIdFromSingleton<>(shard);
            }
        };
    }

    private List<Object> reduceAllParents(Expression body) {
        return reduce(body, allParents());
    }

    private List<Object> reduce(Expression body, List<Integer> parents) {
        var context = driverContext();
        List<Page> input = new ArrayList<>();
        for (int parent : parents) {
            input.add(parents(context.blockFactory(), parent, 1));
        }
        List<Page> results = new TestDriverRunner().builder(context).input(input).run(reduce(body).get(context));
        List<Object> values = values(results);
        for (Page page : results) {
            page.releaseBlocks();
        }
        return values;
    }

    private static List<Object> values(List<Page> results) {
        List<Object> values = new ArrayList<>();
        for (Page page : results) {
            Block block = page.getBlock(page.getBlockCount() - 1);
            for (int p = 0; p < page.getPositionCount(); p++) {
                values.add(BlockUtils.toJavaObject(block, p));
            }
        }
        return values;
    }

    private static List<Object> expected(List<Integer> parents, IntFunction<Object> value) {
        List<Object> expected = new ArrayList<>();
        for (int parent : parents) {
            expected.add(value.apply(parent));
        }
        return expected;
    }

    private static List<Integer> allParents() {
        List<Integer> parents = new ArrayList<>();
        for (int i = 0; i < PARENTS; i++) {
            parents.add(i);
        }
        return parents;
    }

    private static int children(int parent) {
        return parent % 4;
    }

    private static Long sum(int parent) {
        int children = children(parent);
        return children == 0 ? null : 10L * parent * children + (long) children * (children - 1) / 2;
    }
}
