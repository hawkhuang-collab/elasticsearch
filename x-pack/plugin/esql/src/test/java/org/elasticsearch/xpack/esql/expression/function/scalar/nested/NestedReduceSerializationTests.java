/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.expression.function.scalar.nested;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.test.TransportVersionUtils;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.AbstractExpressionSerializationTests;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;

/** Only a lowered {@link NestedReduce} is serialized: the reduction, the child fields it reads and where they live. */
public class NestedReduceSerializationTests extends AbstractExpressionSerializationTests<NestedReduce> {
    @Override
    protected NestedReduce createTestInstance() {
        int size = between(1, 3);
        List<Attribute> inputs = randomInputs(size);
        return new NestedReduce(randomSource(), randomAlphaOfLength(5), randomBody(inputs.getFirst()), inputs, randomPaths(size));
    }

    @Override
    protected NestedReduce mutateInstance(NestedReduce instance) throws IOException {
        return randomValueOtherThan(instance, this::createTestInstance);
    }

    public void testWriteToRefusesNodesWithoutNestedReductions() throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            out.setTransportVersion(TransportVersionUtils.randomVersionNotSupporting(NestedReduce.VERSION));
            IOException e = expectThrows(IOException.class, () -> createTestInstance().writeTo(out));
            assertThat(e.getMessage(), containsString("NESTED requires nested reduction transport support"));
        }
    }

    private static Expression randomBody(Attribute input) {
        Source source = Source.EMPTY;
        return switch (between(0, 2)) {
            case 0 -> new Sum(source, input);
            case 1 -> new Count(source, input);
            case 2 -> new Max(source, input);
            default -> throw new UnsupportedOperationException();
        };
    }

    private static List<Attribute> randomInputs(int size) {
        List<Attribute> inputs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            inputs.add(
                new ReferenceAttribute(Source.EMPTY, "items." + randomAlphaOfLength(4), randomFrom(DataType.LONG, DataType.INTEGER))
            );
        }
        return inputs;
    }

    private static List<String> randomPaths(int size) {
        List<String> paths = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            paths.add(randomAlphaOfLength(4));
        }
        return paths;
    }
}
