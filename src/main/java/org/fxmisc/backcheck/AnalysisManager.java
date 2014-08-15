package org.fxmisc.backcheck;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import org.reactfx.EventStream;
import org.reactfx.Guard;
import org.reactfx.Indicator;
import org.reactfx.LazilyBoundStream;
import org.reactfx.Subscription;
import org.reactfx.util.ListHelper;

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
    private final Set<K> dirtyFiles = new HashSet<>();
    private final Indicator busy = new Indicator();

    private long currentRevision = 0;

    private ListHelper<ResultConsumer<K, R>> resultConsumers = null;

    // resultConsumers reduced into one.
    // null means that it has to be re-computed,
    // Optional.empty means resultConsumers is empty
    private Optional<ResultConsumer<K, R>> resultConsumer = null;

    private AnalysisManager(AsyncAnalyzer<K, T, R> analyzer, Executor clientThreadExecutor) {
        this.asyncAnalyzer = analyzer;
        this.clientThreadExecutor = clientThreadExecutor;
    }

    public Indicator busyProperty() {
        return busy;
    }
    public boolean isBusy() {
        return busy.isOn();
    }

    @SafeVarargs
    public final void handleFileChanges(InputChange<K, T>... changes) {
        handleFileChanges(Arrays.asList(changes));
    }

    public void handleFileChanges(List<? extends InputChange<K, T>> changes) {
        long revision = ++currentRevision;
        Guard g = busy.on();
        asyncAnalyzer.update(changes).thenAcceptAsync(affectedFiles -> {
            dirtyFiles.addAll(affectedFiles);
            if(revision == currentRevision) {
                publishResults();
            }
            g.close();
        }, clientThreadExecutor);
    }

    public <U> EventStream<Update<K, U>> transformedResults(BiFunction<K, R, U> transformation) {
        return new ResultsTransformationStream<>(transformation);
    }

    public <U> EventStream<U> transformedResults(K fileId, Function<R, U> transformation) {
        return new ResultTransformationStream<>(fileId, transformation);
    }

    public <U> CompletionStage<Void> withTransformedResult(K id, BiFunction<K, R, U> transformation, BiConsumer<K, U> callback) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Guard g = busy.on();
        future.whenCompleteAsync((res, err) -> g.close(), clientThreadExecutor);
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

    private void publishResults() {
        long revision = currentRevision;
        getResultConsumer().ifPresent(consumer -> {
            BiConsumer<K, Guard> eventually = (k, g) -> clientThreadExecutor.execute(() -> {
                if(revision == currentRevision) {
                    dirtyFiles.remove(k);
                }
                g.close();
            });
            for(K f: dirtyFiles) {
                Guard g = busy.on();
                asyncAnalyzer.consumeResult(f, (k, r) -> {
                    try {
                        consumer.accept(k, r, revision);
                    } finally {
                        eventually.accept(k, g);
                    }
                }, k -> eventually.accept(k, g));
            }
        });
    }

    private void addResultConsumer(ResultConsumer<K, R> consumer) {
        resultConsumers = ListHelper.add(resultConsumers, consumer);
        resultConsumer = null;
    }

    private void removeResultConsumer(ResultConsumer<K, R> consumer) {
        resultConsumers = ListHelper.remove(resultConsumers, consumer);
        resultConsumer = null;
    }

    private Optional<ResultConsumer<K, R>> getResultConsumer() {
        if(resultConsumer == null) {
            if(ListHelper.isEmpty(resultConsumers)) {
                resultConsumer = Optional.empty();
            } else {
                @SuppressWarnings("unchecked")
                ResultConsumer<K, R>[] consumers = ListHelper.toArray(
                        resultConsumers,
                        i -> (ResultConsumer<K, R>[]) new ResultConsumer<?, ?>[i]);
                resultConsumer = Optional.of((k, r, rev) -> {
                    Throwable thrown = null;
                    for(ResultConsumer<K, R> consumer: consumers) {
                        try {
                            consumer.accept(k, r, rev);
                        } catch(Throwable t) {
                            thrown = t;
                        }
                    }
                    if(thrown != null) {
                        throw new RuntimeException(thrown);
                    }
                });
            }
        }
        return resultConsumer;
    }

    @FunctionalInterface
    private interface ResultConsumer<K, R> {
        void accept(K k, R r, long revision);
    }

    private class ResultsTransformationStream<U> extends LazilyBoundStream<Update<K, U>> implements ResultConsumer<K, R> {
        private final BiFunction<K, R, U> transformation;

        public ResultsTransformationStream(BiFunction<K, R, U> transformation) {
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
            addResultConsumer(this);
            return () -> removeResultConsumer(this);
        }
    }

    private class ResultTransformationStream<U> extends LazilyBoundStream<U> implements ResultConsumer<K, R> {
        private final K fileId;
        private final Function<R, U> transformation;

        public ResultTransformationStream(K fileId, Function<R, U> transformation) {
            this.fileId = fileId;
            this.transformation = transformation;
        }

        @Override
        public void accept(K f, R r, long revision) {
            if(Objects.equals(f, fileId)) {
                U u = transformation.apply(r);
                clientThreadExecutor.execute(() -> {
                    if(revision == currentRevision) {
                        emit(u);
                    }
                });
            }
        }

        @Override
        protected Subscription subscribeToInputs() {
            addResultConsumer(this);
            return () -> removeResultConsumer(this);
        }

        @Override
        protected void newSubscriber(Consumer<? super U> subscriber) {
            withTransformedResult(fileId,
                    (k, r) -> transformation.apply(r),
                    (k, u) -> emit(u));
        }
    }
}
