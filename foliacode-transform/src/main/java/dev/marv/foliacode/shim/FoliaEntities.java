package dev.marv.foliacode.shim;

/**
 * The entity entry points rewritten call sites jump to.
 *
 * <p>See {@link FoliaSchedulers} for why the parameters are {@code Object} and why
 * the original receiver is passed along.</p>
 */
public final class FoliaEntities {

    private static final String ENTITY = "org.bukkit.entity.Entity";
    private static final String LOCATION = "org.bukkit.Location";

    private FoliaEntities() {
    }

    /**
     * Replaces {@code Entity.teleport(Location)}.
     *
     * <p>The two calls do not mean the same thing. {@code teleport} returns whether
     * the move happened; {@code teleportAsync} returns immediately with a future and
     * the move happens later. Reporting {@code true} for work that has not finished
     * would be a lie in general — which is why the transformer refuses this rewrite
     * unless it has proved the result is discarded. Under that precondition no
     * caller can observe the difference.</p>
     *
     * @param entity   the entity to move
     * @param location the destination
     * @return true if the teleport was started or performed
     */
    public static boolean teleport(Object entity, Object location) {
        if (FoliaBridge.teleportAsync(entity, location)) {
            return true;
        }
        Object result = FoliaBridge.invokeOriginal(entity, ENTITY, "teleport",
                new String[]{LOCATION}, location);
        return Boolean.TRUE.equals(result);
    }
}
