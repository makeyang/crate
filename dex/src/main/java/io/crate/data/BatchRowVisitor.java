/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.data;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collector;

/**
 * Visitor that iterates to the end of a BatchIterator consuming all rows.
 * The returned future will complete once the end of the batchIterator has been reached.
 *
 * This is no proper {@link io.crate.data.BatchConsumer} and it does *NOT* close the BatchIterator.
 */
public class BatchRowVisitor {

    public static <A, R> CompletableFuture<R> visitRows(BatchIterator it, Collector<Row, A, R> collector) {
        return visitRows(it, collector.supplier().get(), collector, new CompletableFuture<R>());
    }

    public static <A, R> CompletableFuture<R> visitRows(BatchIterator it,
                                                        A state,
                                                        Collector<Row, A, R> collector,
                                                        CompletableFuture<R> resultFuture) {
        BiConsumer<A, Row> accumulator = collector.accumulator();
        Row row = RowBridging.toRow(it.rowData());
        try {
            while (it.moveNext()) {
                accumulator.accept(state, row);
            }
        } catch (Throwable t) {
            resultFuture.completeExceptionally(t);
            return resultFuture;
        }

        if (it.allLoaded()) {
            resultFuture.complete(collector.finisher().apply(state));
        } else {
            it.loadNextBatch().whenComplete((r, t) -> {
                if (t == null) {
                    visitRows(it, state, collector, resultFuture);
                } else {
                    resultFuture.completeExceptionally(t);
                }
            });
        }
        return resultFuture;
    }
}
