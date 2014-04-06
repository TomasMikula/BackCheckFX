package org.fxmisc.backcheck;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.fxmisc.backcheck.InputChange.InputRename;

public interface Analyzer<K, T, R> {
    Set<K> update(List<? extends InputChange<K, T>> changes);
    default Set<K> update(@SuppressWarnings("unchecked") InputChange<K, T>... changes) {
        return update(Arrays.asList(changes));
    }

    Optional<R> getResult(K fileKey);
    Map<K, R> getResults();
    Map<K, R> getResults(Set<K> fileKeys);


    static <K, T, R> Analyzer<K, T, R> wrap(StatelessAnalyzer<T, R> analyzer) {
        return new Analyzer<K, T, R>() {
            private final Map<K, R> results = new HashMap<>();

            @Override
            public Set<K> update(List<? extends InputChange<K, T>> changes) {
                Set<K> affectedFiles = new HashSet<>(changes.size());
                for(InputChange<K, T> ch: changes) {
                    handleChange(ch).ifPresent(affectedFiles::add);
                }
                return affectedFiles;
            }

            @Override
            public Optional<R> getResult(K fileKey) {
                return Optional.ofNullable(results.get(fileKey));
            }

            @Override
            public Map<K, R> getResults() {
                return Collections.unmodifiableMap(results);
            }

            @Override
            public Map<K, R> getResults(Set<K> fileKeys) {
                Map<K, R> res = new HashMap<>(fileKeys.size());
                for(K key: fileKeys) {
                    R r = results.get(key);
                    if(r != null) {
                        res.put(key, r);
                    }
                }
                return res;
            }

            private Optional<K> handleChange(InputChange<K, T> ch) {
                R res;
                switch(ch.getType()) {
                    case ADDITION:
                        res = analyzer.analyze(ch.asAddition().getContent());
                        results.put(ch.getInputId(), res);
                        return Optional.of(ch.getInputId());
                    case REMOVAL:
                        results.remove(ch.getInputId());
                        return Optional.empty();
                    case RENAME:
                        InputRename<K, T> change = ch.asRename();
                        K oldKey = change.getOldId();
                        K newKey = change.getNewId();
                        if(!newKey.equals(oldKey)) {
                            res = results.remove(oldKey);
                            results.put(newKey, res);
                        }
                        return Optional.empty();
                    case MODIFICATION:
                        res = analyzer.analyze(ch.asModification().getNewContent());
                        results.put(ch.getInputId(), res);
                        return Optional.of(ch.getInputId());
                    default:
                        throw new AssertionError("unreachable code");
                }
            }
        };
    }
}