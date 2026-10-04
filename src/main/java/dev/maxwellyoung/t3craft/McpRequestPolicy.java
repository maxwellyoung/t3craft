package dev.maxwellyoung.t3craft;

import com.sun.net.httpserver.Headers;
import java.net.URI;

/** Native local clients only: browsers and rebinding hostnames cannot reach game tools. */
final class McpRequestPolicy {
	static boolean allows(Headers headers, int port) {
		if (headers.containsKey("Origin")) return false;
		var hosts = headers.get("Host");
		if (hosts == null || hosts.size() != 1) return false;
		try {
			String authority = hosts.getFirst();
			URI uri = URI.create("http://" + authority);
			String host = uri.getHost();
			return authority.equals(uri.getRawAuthority()) && uri.getRawUserInfo() == null
				&& uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null
				&& uri.getPort() == port && host != null
				&& (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
		} catch (IllegalArgumentException e) { return false; }
	}
}
