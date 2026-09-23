package dev.maxwellyoung.t3craft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared logger, so code used on a dedicated server never loads client-only classes. */
public final class T3Log {
	public static final Logger LOGGER = LoggerFactory.getLogger("t3craft");

	private T3Log() {
	}
}
