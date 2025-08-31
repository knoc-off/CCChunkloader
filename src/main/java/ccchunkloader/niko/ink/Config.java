package ccchunkloader.niko.ink;

public class Config {
    // General Settings
    public static double MAX_RADIUS = 2.5;
    public static double MAX_RANDOM_TICK_RADIUS = 1.4;

    // Fuel Cost Configuration
    public static double BASE_FUEL_COST_PER_CHUNK = 0.0333333;
    public static double DISTANCE_MULTIPLIER = 2.0;
    public static double RANDOM_TICK_FUEL_MULTIPLIER = 2.0;

    // Cleanup Settings - DISABLED BY DEFAULT
    // WARNING: Time-based cleanup destroys user turtle data inappropriately!
    // Only enable if you understand that dormant turtles will be permanently lost
    public static boolean ENABLE_TIME_BASED_CLEANUP = false; // DISABLED - causes data loss
    public static int CLEANUP_INTERVAL_TICKS = 600; // 30 seconds (only used if enabled above)
    public static long MAX_INACTIVE_TIME_MS = 5 * 60 * 1000; // 5 minutes (only used if enabled above)
}
