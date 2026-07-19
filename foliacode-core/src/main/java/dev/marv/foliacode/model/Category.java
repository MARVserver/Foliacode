package dev.marv.foliacode.model;

/**
 * Classification of a detected API.
 *
 * <p>Grouping the report by category lets the reader tell at a glance whether
 * the fix is localised ("just the scheduler") or structural ("this needs a
 * rewrite").</p>
 */
public enum Category {

    SCHEDULER("Scheduler"),
    BLOCK("Block"),
    ENTITY("Entity"),
    WORLD("World"),
    CHUNK("Chunk"),
    INVENTORY("Inventory"),
    WORLDGEN("World generation"),
    SERVER("Server"),
    REFLECTION("Reflection");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
