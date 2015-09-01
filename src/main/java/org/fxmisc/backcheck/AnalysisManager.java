package org.fxmisc.backcheck;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
import org.reactfx.EventStreamBase;
import org.reactfx.Guard;
import org.reactfx.Subscription;
import org.reactfx.SuspendableNo;
import org.reactfx.util.ListHelper;
import org.reactfx.util.Try;

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
    private final SuspendableNo busy = new SuspendableNo();

    private long currentRevision = 0;

    private ListHelper<ResultHandler<K, R>> resultHandlers = null;

    // resultHandlers reduced into one.
    // null means that it has to be re-computed.
    private ResultHandler<K, R> resultHandler = null;

    private AnalysisManager(AsyncAnalyzer<K, T, R> analyzer, Executor clientThreadExecutor) {
        this.asyncAnalyzer = analyzer;
        this.clientThreadExecutor = clientThreadExecutor;
    }

    public SuspendableNo busyProperty() {
        return busy;
    }
    public boolean isBusy() {
        return busy.getValue();
    }

    @SafeVarargs
    public final void handleFileChanges(InputChange<K, T>... changes) {
        handleFileChanges(Arrays.asList(changes));
    }

    public void handleFileChanges(List<? extends InputChange<K, T>> changes) {
        long revision = ++currentRevision;
        Guard g = busy.suspend();
        asyncAnalyzer.update(changes)
                .whenCompleteAsync((affectedFiles, err) -> {
                    if(err != null) {
                        err.printStackTrace();
                    } else {
                        dirtyFiles.addAll(affectedFiles);
                        if(revision == currentRevision) {
                            publishResults();
                        }
                    }
                    g.close();
                }, clientThreadExecutor);
    }

    public <U> EventStream<Try<Update<K, U>>> transformedResults(BiFunction<K, R, U> transformation) {
        return new ResultsTransformationStream<>(transformation);
    }

    public <U> EventStream<Try<U>> transformedResults(K fileId, Function<R, U> transformation) {
        return new ResultTransformationStream<>(fileId, transformation);
    }

    public <U> CompletionStage<Boolean> withTransformedResult(K id, Function<R, U> transformation, Consumer<U> callback) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Guard g = busy.suspend();
        future.whenCompleteAsync((res, err) -> g.close(), clientThreadExecutor);
        withTransformedResult(id, transformation, callback, future);
        return future;
    }

    private <U> void withTransformedResult(K id, Function<R, U> transformation, Consumer<U> callback, CompletableFuture<Boolean> toComplete) {
        long revision = currentRevision;
        asyncAnalyzer.whenReadyApply(id, transformation)
                .whenCompleteAsync((ou, err) -> {
                    if(err != null) {
                        toComplete.completeExceptionally(err);
                    } else if(revision == currentRevision) {
                        if(ou.isPresent()) {
                            try {
                                callback.accept(ou.get());
                                toComplete.complete(true);
                            } catch(Throwable ex) {
                                toComplete.completeExceptionally(ex);
                            }
                        } else {
                            toComplete.complete(false);
                        }
                    } else { // try again
                        withTransformedResult(id, transformation, callback, toComplete);
                    }
                }, clientThreadExecutor);
    }

    private void publishResults() {
        long revision = currentRevision;
        ResultHandler<K, R> consumer = getResultHandler();
        for(K f: dirtyFiles) {
            Guard g = busy.suspend();
            asyncAnalyzer.whenReadyAccept(f, r -> consumer.onSuccess(f, r, revision))
                    .whenCompleteAsync((res, err) -> {
                        if(revision == currentRevision) {
                            dirtyFiles.remove(f);
                        }
                        g.close();
                        if(err != null) {
                            consumer.onError(f, err);
                        } else if(!res) {
                            throw new AssertionError("Oops, shouldn't happen.");
                        }
                    }, clientThreadExecutor);
        }
    }

    private void addResultHandler(ResultHandler<K, R> consumer) {
        resultHandlers = ListHelper.add(resultHandlers, consumer);
        resultHandler = null;
    }

    private void removeResultHandler(ResultHandler<K, R> consumer) {
        resultHandlers = ListHelper.remove(resultHandlers, consumer);
        resultHandler = null;
    }

    private ResultHandler<K, R> getResultHandler() {
        if(resultHandler == null) {
            @SuppressWarnings("unchecked")
            ResultHandler<K, R>[] handlers = ListHelper.toArray(
                    resultHandlers,
                    i -> (ResultHandler<K, R>[]) new ResultHandler<?, ?>[i]);
            resultHandler = ResultHandler.create(
                    (k, r, rev) -> {
                        for(ResultHandler<K, R> handler: handlers) {
                            try {
                                handler.onSuccess(k, r, rev);
                            } catch(Throwable err) {
                                try {
                                    handler.onError(k, err);
                                } catch(Throwable t) {
                                    // ignore
                                }
                            }
                        }
                    },
                    (k, err) -> {
                        for(ResultHandler<K, R> handler: handlers) {
                            try {
                                handler.onError(k, err);
                            } catch(Throwable t) {
                                // ignore
                            }
                        }
                    });
        }
        return resultHandler;
    }

    @FunctionalInterface
    private interface ResultConsumer<K, R> {
        void accept(K k, R r, long revision);
    }

    private interface ResultHandler<K, R> {
        void onSuccess(K k, R r, long revision);
        void onError(K k, Throwable ex);

        static <K, R> ResultHandler<K, R> create(
                ResultConsumer<K, R> onSuccess,
                BiConsumer<K, Throwable> onError) {
            return new ResultHandler<K, R>() {

                @Override
                public void onSuccess(K k, R r, long revision) {
                    onSuccess.accept(k, r, revision);
                }

                @Override
                public void onError(K k, Throwable ex) {
                    onError.accept(k, ex);
                }
            };
        }
    }

    private class ResultsTransformationStream<U> extends EventStreamBase<Try<Update<K, U>>> implements ResultHandler<K, R> {
        private final BiFunction<K, R, U> transformation;

        public ResultsTransformationStream(BiFunction<K, R, U> transformation) {
            this.transformation = transformation;
        }

        @Override
        public void onSuccess(K f, R r, long revision) {
            U u = transformation.apply(f, r);
            clientThreadExecutor.execute(() -> {
                if(revision == currentRevision) {
                    emit(Try.success(new Update<>(f, u)));
                }
            });
        }

        @Override
        public void onError(K f, Throwable err) {
            clientThreadExecutor.execute(() -> emit(Try.failure(err)));
        }

        @Override
        protected Subscription observeInputs() {
            addResultHandler(this);
            return () -> removeResultHandler(this);
        }
    }

    private class ResultTransformationStream<U> extends EventStreamBase<Try<U>> implements ResultHandler<K, R> {
        private final K fileId;
        private final Function<R, U> transformation;

        public ResultTransformationStream(K fileId, Function<R, U> transformation) {
            this.fileId = fileId;
            this.transformation = transformation;
        }

        @Override
        public void onSuccess(K f, R r, long revision) {
            if(Objects.equals(f, fileId)) {
                U u = transformation.apply(r);
                clientThreadExecutor.execute(() -> {
                    if(revision == currentRevision) {
                        emit(Try.success(u));
                    }
                });
            }
        }

        @Override
        public void onError(K f, Throwable err) {
            if(Objects.equals(f, fileId)) {
                clientThreadExecutor.execute(() -> emit(Try.failure(err)));
            }
        }

        @Override
        protected Subscription observeInputs() {
            addResultHandler(this);
            return () -> removeResultHandler(this);
        }

        @Override
        protected void newObserver(Consumer<? super Try<U>> subscriber) {
            withTransformedResult(fileId, transformation, r -> emit(Try.success(r)))
                    .whenCompleteAsync((found, err) -> {
                        if(err != null) {
                            emit(Try.failure(err));
                        } else if(!found) {
                            // fileId not found in the analyzer, do nothing
                        }
                    }, clientThreadExecutor);
        }
    }
}
