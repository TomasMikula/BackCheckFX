package org.fxmisc.backcheck;

public abstract class InputChange<K, T> {

    public static enum ChangeType {
        ADDITION,
        REMOVAL,
        RENAME,
        MODIFICATION,
    }

    private final K inputId;

    private InputChange(K inputId) { // private constructor to prevent subclassing
        this.inputId = inputId;
    }

    public abstract ChangeType getType();

    public final boolean isAddition() { return this instanceof InputAddition; }
    public final boolean isRemoval() { return this instanceof InputRemoval; }
    public final boolean isRename() { return this instanceof InputRename; }
    public final boolean isModification() { return this instanceof InputModification; }

    public InputAddition<K, T> asAddition() { return (InputAddition<K, T>) this; }
    public InputRemoval<K, T> asRemoval() { return (InputRemoval<K, T>) this; }
    public InputRename<K, T> asRename() { return (InputRename<K, T>) this; }
    public InputModification<K, T> asModification() { return (InputModification<K, T>) this; }

    public K getInputId() {
        return inputId;
    }

    public static class InputAddition<K, T> extends InputChange<K, T> {
        private final T content;

        public InputAddition(K inputId, T content) {
            super(inputId);
            this.content = content;
        }

        @Override
        public ChangeType getType() { return ChangeType.ADDITION; }

        public T getContent() { return content; }
    }

    public static class InputRemoval<K, T> extends InputChange<K, T> {
        public InputRemoval(K inputId) {
            super(inputId);
        }

        @Override
        public ChangeType getType() { return ChangeType.REMOVAL; }
    }

    public static class InputRename<K, T> extends InputChange<K, T> {
        private final K newId;

        public InputRename(K oldId, K newId) {
            super(oldId);
            this.newId = newId;
        }

        @Override
        public ChangeType getType() { return ChangeType.RENAME; }

        public K getOldId() { return getInputId(); }
        public K getNewId() { return newId; }
    }

    public static class InputModification<K, T> extends InputChange<K, T> {
        private final T newContent;

        public InputModification(K inputId, T newContent) {
            super(inputId);
            this.newContent = newContent;
        }

        public final boolean isFileEdited() {
            return this instanceof FileEdited;
        }

        @SuppressWarnings("unchecked")
        public FileEdited<K> asFileEdited() {
            return (FileEdited<K>) this;
        }

        @Override
        public ChangeType getType() { return ChangeType.MODIFICATION; }

        public T getNewContent() {
            return newContent;
        }
    }

    public static class FileEdited<K> extends InputModification<K, String> {
        private final int position;
        private final String removedText;
        private final String insertedText;

        public FileEdited(K fileId, String newContent, int position, String removedText, String insertedText) {
            super(fileId, newContent);
            this.position = position;
            this.removedText = removedText;
            this.insertedText = insertedText;
        }

        public int getPosition() {
            return position;
        }

        public String getRemovedText() {
            return removedText;
        }

        public String getInsertedText() {
            return insertedText;
        }
    }
}
