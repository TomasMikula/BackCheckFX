package org.fxmisc.backcheck;

import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public interface AsyncAnalyzer<K, T, R> {
    CompletionStage<Set<K>> update(List<? extends InputChange<K, T>> changes);
    default CompletionStage<Set<K>> update(@SuppressWarnings("unchecked") InputChange<K, T>... changes) {
        return update(Arrays.asList(changes));
    }

    void consumeResult(K fileKey, BiConsumer<K, R> consumer);
    void consumeResults(BiConsumer<K, R> consumer);
    void consumeResults(Set<K> fileKeys, BiConsumer<K, R> consumer);


    /**
     * Wraps a synchronous analyzer in an asynchronous interface.
     * All operations are still synchronous, performed on the caller thread.
     * The return values of all operations are completed by the time they are
     * returned.
     * @param analyzer synchronous analyzer
     * @return wrapper for {@code analyzer} with asynchronous interface.
     */
    public static <K, T, R> AsyncAnalyzer<K, T, R> wrap(Analyzer<K, T, R> analyzer) {
        return new AsyncAnalyzer<K, T, R>() {

            @Override
            public CompletionStage<Set<K>> update(List<? extends InputChange<K, T>> changes) {
                Set<K> res = analyzer.update(changes);
                return CompletableFuture.completedFuture(res);
            }

            @Override
            public void consumeResult(K fileKey, BiConsumer<K, R> consumer) {
                analyzer.getResult(fileKey).ifPresent(
                        r -> consumer.accept(fileKey, r));
            }

            @Override
            public void consumeResults(BiConsumer<K, R> consumer) {
                for(Entry<K, R> e: analyzer.getResults().entrySet()) {
                    consumer.accept(e.getKey(), e.getValue());
                }
            }

            @Override
            public void consumeResults(Set<K> fileKeys, BiConsumer<K, R> consumer) {
                for(Entry<K, R> e: analyzer.getResults(fileKeys).entrySet()) {
                    consumer.accept(e.getKey(), e.getValue());
                }
            }
        };
    }

    /**
     * Creates an analyzer that asynchronously delegates the operations
     * to the given synchronous analyzer. As opposed to {@link #wrap(Analyzer)},
     * the returned analyzer is truly asynchronous.
     * @param analyzer synchronous analyzer.
     * @param singleThreadExecutor executor used to invoke operations
     * on the wrapped synchronous analyzer. Since {@link Analyzer} is
     * in general not thread-safe, the executor must execute all actions
     * on the same thread.
     * @return asynchronous proxy to the given synchronous analyzer.
     */
    public static <K, T, R> AsyncAnalyzer<K, T, R> from(Analyzer<K, T, R> analyzer, Executor singleThreadExecutor) {
        return new AsyncAnalyzer<K, T, R>() {

            @Override
            public CompletionStage<Set<K>> update(List<? extends InputChange<K, T>> changes) {
                Supplier<Set<K>> supplier = () -> analyzer.update(changes);
                return CompletableFuture.supplyAsync(supplier, singleThreadExecutor);
            }

            @Override
            public void consumeResult(K fileKey, BiConsumer<K, R> consumer) {
                singleThreadExecutor.execute(() -> {
                    analyzer.getResult(fileKey).ifPresent(
                            r -> consumer.accept(fileKey, r));
                });
            }

            @Override
            public void consumeResults(BiConsumer<K, R> consumer) {
                singleThreadExecutor.execute(() -> {
                    for(Entry<K, R> e: analyzer.getResults().entrySet()) {
                        consumer.accept(e.getKey(), e.getValue());
                    }
                });
            }

            @Override
            public void consumeResults(Set<K> fileKeys, BiConsumer<K, R> consumer) {
                singleThreadExecutor.execute(() -> {
                    for(Entry<K, R> e: analyzer.getResults(fileKeys).entrySet()) {
                        consumer.accept(e.getKey(), e.getValue());
                    }
                });
            }
        };
    }
}