package com.arlight.bingo.game;

import java.util.Objects;

/** Representa una casilla y su progreso. */
public class BingoGoal {
    private final String id;
    private final GoalType type;
    private final String target;
    private final int amountRequired;
    private final String displayName;
    private final String iconKey;

    private int progress = 0;
    private boolean completed = false;

    public BingoGoal(String id, GoalType type, String target, int amountRequired, String displayName) {
        this(id, type, target, amountRequired, displayName, null);
    }

    public BingoGoal(String id, GoalType type, String target, int amountRequired,
                     String displayName, String iconKey) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.amountRequired = Math.max(1, amountRequired);
        this.displayName = displayName;
        this.iconKey = iconKey == null ? "" : iconKey.trim();
    }

    public String getId() { return id; }
    public GoalType getType() { return type; }
    public String getTarget() { return target; }
    public int getAmountRequired() { return amountRequired; }
    public String getDisplayName() { return displayName; }
    public String getIconKey() { return iconKey; }
    public int getProgress() { return progress; }
    public boolean isCompleted() { return completed; }

    public boolean addProgress(int amount) {
        if (completed) return false;
        progress += amount;
        if (progress >= amountRequired) {
            progress = amountRequired;
            completed = true;
            return true;
        }
        return false;
    }

    public boolean updateProgressIfHigher(int newAmount) {
        if (completed) return false;
        if (newAmount > progress) progress = Math.min(newAmount, amountRequired);
        if (progress >= amountRequired) {
            completed = true;
            return true;
        }
        return false;
    }

    public void forceComplete() {
        progress = amountRequired;
        completed = true;
    }

    public BingoGoal copyFresh() {
        return new BingoGoal(id, type, target, amountRequired, displayName, iconKey);
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof BingoGoal other && id.equals(other.id));
    }
    @Override public int hashCode() { return Objects.hash(id); }
}
