package ccchunkloader.niko.ink;

/**
 * Configuration constants for CCChunkloader mod.
 * Contains limits and cost calculations for chunk loading.
 */
public final class Config {
    
    // General Settings
    /** Maximum radius in chunks that can be loaded by a turtle */
    public static final double MAX_RADIUS = 2.5;
    
    /** Maximum radius for which random ticking can be enabled */
    public static final double MAX_RANDOM_TICK_RADIUS = 1.4;

    // Fuel Cost Configuration
    /** Base fuel cost per chunk per tick */
    public static final double BASE_FUEL_COST_PER_CHUNK = 0.0333333;
    
    /** Multiplier applied based on distance from turtle */
    public static final double DISTANCE_MULTIPLIER = 2.0;
    
    /** Additional fuel cost multiplier when random ticking is enabled */
    public static final double RANDOM_TICK_FUEL_MULTIPLIER = 2.0;
    
    // Private constructor to prevent instantiation
    private Config() {
        throw new UnsupportedOperationException("Config is a utility class");
    }
}
