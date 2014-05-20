package org.fxmisc.backcheck;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import javafx.application.Platform;

import org.reactfx.EventStream;
import org.reactfx.LazilyBoundStream;
import org.reactfx.ListHelper;
import org.reactfx.Subscription;

public final class AnalysisManager<K, T, R> {

    public static final class Update<K, T> {
        private final K f;
        private final T t;

        private Update(K f, T t) {
            this.f = f;
            this.t = t;
        }

        public K getFileId() { return f; }
        public T getResult() { return t; }
    }

    public static <K, T, R> AnalysisManager<K, T, R> manage(AsyncAnalyzer<K, T, R> analyzer, Executor clientThreadExecutor) {
        return new AnalysisManager<>(analyzer, clientThreadExecutor);
    }

    public static <K, T, R> AnalysisManager<K, T, R> manageFromFxThread(AsyncAnalyzer<K, T, R> analyzer) {
        return new AnalysisManager<>(analyzer, Platform::runLater);
    }

    private final AsyncAnalyzer<K, T, R> asyncAnalyzer;
    private final Executor clientThreadExecutor;

    private Set<K> dirtyFiles = new HashSet<>();
    private long currentRevision = 0;

    private ListHelper<ResultConsumer<K, R>> resultConsumers = null;

    private AnalysisManager(AsyncAnalyzer<K, T, R> analyzer, Executor clientThreadExecutor) {
        this.asyncAnalyzer = analyzer;
        this.clientThreadExecutor = clientThreadExecutor;
    }

    @SafeVarargs
    public final void handleFileChanges(InputChange<K, T>... changes) {
        handleFileChanges(Arrays.asList(changes));
    }

    public void handleFileChanges(List<? extends InputChange<K, T>> changes) {
        long revision = ++currentRevision;
        asyncAnalyzer.update(changes).thenAcceptAsync(affectedFiles -> {
            dirtyFiles.addAll(affectedFiles);
            consumeResultsIfStillCurrent(revision);
        }, clientThreadExecutor);
    }

    public <U> EventStream<Update<K, U>> transformedResults(BiFunction<K, R, U> transformation) {
        return new ResultTransformationStream<U>(transformation);
    }

    public <U> CompletionStage<Void> withTransformedResult(K id, BiFunction<K, R, U> transformation, BiConsumer<K, U> callback) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        withTransformedResult(id, transformation, callback, future);
        return future;
    }

    private <U> void withTransformedResult(K id, BiFunction<K, R, U> transformation, BiConsumer<K, U> callback, CompletableFuture<Void> toComplete) {
        long revision = currentRevision;
        asyncAnalyzer.consumeResult(id, (k, r) -> {
            U u;
            try {
                u = transformation.apply(k, r);
            } catch(Throwable t) {
                toComplete.completeExceptionally(t);
                return;
            }
            clientThreadExecutor.execute(() -> {
                if(revision == currentRevision) {
                    try {
                        callback.accept(k, u);
                        toComplete.complete(null);
                    } catch(Throwable t) {
                        toComplete.completeExceptionally(t);
                    }
                } else { // try again
                    withTransformedResult(id, transformation, callback, toComplete);
                }
            });
        }, k -> toComplete.completeExceptionally(new NoSuchElementException("No analysis result for " + id)));
    }

    private void consumeResultsIfStillCurrent(long revision) {
        if(revision == currentRevision) {
            CountDownLatch cdl = new CountDownLatch(ListHelper.size(resultConsumers));
            ListHelper.forEach(resultConsumers, consumer -> {
                BiConsumer<K, R> biConsumer = (k, r) -> {
                    try {
                        consumer.accept(k, r, revision);
                    } finally {
                        cdl.countDown();
                        if(cdl.getCount() == 0) { // may occur more than once, but that's OK
                            clientThreadExecutor.execute(() -> {
                                if(revision == currentRevision) {
                                    dirtyFiles = new HashSet<>();
                                }
                            });
                        }
                    }
                };
                asyncAnalyzer.consumeResults(dirtyFiles, biConsumer);
            });
        }
    }

    @FunctionalInterface
    private interface ResultConsumer<K, R> {
        void accept(K k, R r, long revision);
    }

    private class ResultTransformationStream<U> extends LazilyBoundStream<Update<K, U>> implements ResultConsumer<K, R> {
        private final BiFunction<K, R, U> transformation;

        public ResultTransformationStream(BiFunction<K, R, U> transformation) {
            this.transformation = transformation;
        }

        @Override
        public void accept(K f, R r, long revision) {
            U u = transformation.apply(f, r);
            clientThreadExecutor.execute(() -> {
                if(revision == currentRevision) {
                    emit(new Update<>(f, u));
                }
            });
        }

        @Override
        protected Subscription subscribeToInputs() {
            resultConsumers = ListHelper.add(resultConsumers, this);
            return () -> resultConsumers = ListHelper.remove(resultConsumers, this);
        }
    }
}
