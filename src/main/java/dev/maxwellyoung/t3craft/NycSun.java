package dev.maxwellyoung.t3craft;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Sunrise and sunset in New York (the office's city), so the lamps come on at dusk. */
final class NycSun {
	private static final double LAT = 40.7128;
	private static final double LON = -74.0060;

	private NycSun() {
	}

	static boolean isNight(Instant now) {
		ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
		double hour = utc.getHour() + utc.getMinute() / 60.0;
		// NYC's day spans the UTC date line (sunset is ~22–24h UTC), so check yesterday's sunset too.
		double sunrise = event(utc.getDayOfYear(), true);
		double sunset = event(utc.getDayOfYear(), false);
		if (sunset < sunrise) return hour >= sunset && hour < sunrise; // sunset rolled past midnight UTC
		return hour < sunrise || hour >= sunset;
	}

	/** UTC hour of sunrise or sunset (the standard almanac approximation, zenith 90.833°). */
	static double event(int dayOfYear, boolean rise) {
		double lngHour = LON / 15;
		double t = dayOfYear + ((rise ? 6 : 18) - lngHour) / 24;
		double m = 0.9856 * t - 3.289;
		double l = mod(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634, 360);
		double ra = mod(Math.toDegrees(Math.atan(0.91764 * tan(l))), 360);
		ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
		ra /= 15;
		double sinDec = 0.39782 * sin(l);
		double cosDec = Math.cos(Math.asin(sinDec));
		double cosH = (cos(90.833) - sinDec * sin(LAT)) / (cosDec * cos(LAT));
		double h = rise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
		h /= 15;
		double local = h + ra - 0.06571 * t - 6.622;
		return mod(local - lngHour, 24);
	}

	private static double sin(double deg) {
		return Math.sin(Math.toRadians(deg));
	}

	private static double cos(double deg) {
		return Math.cos(Math.toRadians(deg));
	}

	private static double tan(double deg) {
		return Math.tan(Math.toRadians(deg));
	}

	private static double mod(double v, double m) {
		return ((v % m) + m) % m;
	}
}
