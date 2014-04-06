package org.fxmisc.backcheck;

public interface StatelessAnalyzer<T, R> {
    R analyze(T input);
}
