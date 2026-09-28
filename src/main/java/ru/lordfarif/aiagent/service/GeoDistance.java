package ru.lordfarif.aiagent.service;

/** Great-circle distance; this is not a walking-route distance. */
final class GeoDistance {
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoDistance() {}

    static boolean hasCoordinates(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    static double meters(double fromLat, double fromLon, double toLat, double toLon) {
        double latitudeDelta = Math.toRadians(toLat - fromLat);
        double longitudeDelta = Math.toRadians(toLon - fromLon);
        double a = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
                * Math.pow(Math.sin(longitudeDelta / 2), 2);
        // Floating-point rounding near antipodal points can put a just outside [0, 1].
        a = Math.max(0, Math.min(1, a));
        return (double) Math.round(EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
