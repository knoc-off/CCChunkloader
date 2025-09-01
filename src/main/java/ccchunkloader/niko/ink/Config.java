package ccchunkloader.niko.ink;

/**
 * Configuration constants for CC Chunk Loader mod.
 * These values control chunk loading limits and fuel consumption rates.
 */
public final class Config {
    // Prevent instantiation
    private Config() {}
    
    // General Settings
    public static double MAX_RADIUS = 2.5;
    public static double MAX_RANDOM_TICK_RADIUS = 1.4;

    // Fuel Cost Configuration
    public static double BASE_FUEL_COST_PER_CHUNK = 0.0333333;
    public static double DISTANCE_MULTIPLIER = 2.0;
    public static double RANDOM_TICK_FUEL_MULTIPLIER = 2.0;
}
