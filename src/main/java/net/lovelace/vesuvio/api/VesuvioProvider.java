package net.lovelace.vesuvio.api;

/**
 * Static provider accessor for {@link VesuvioAPI}.
 *
 * Author: Lovelace
 */
public final class VesuvioProvider {

    private static VesuvioAPI instance;

    private VesuvioProvider() {}

    public static VesuvioAPI get() {
        if (instance == null) {
            throw new IllegalStateException("VesuvioAPI is not yet registered. Ensure Vesuvio is loaded.");
        }
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static void register(VesuvioAPI api) {
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }
}
