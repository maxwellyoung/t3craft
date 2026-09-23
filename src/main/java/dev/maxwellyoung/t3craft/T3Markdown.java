package dev.maxwellyoung.t3craft;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown for agent replies in Minecraft's font: headings, lists with hanging
 * indents, inline code, code blocks, quotes, links, rules, and pipe tables. Anything else
 * falls through as plain text, so nothing is ever hidden.
 */
final class T3Markdown {
	/** One wrapped row. {@code marker} is drawn in the gutter (bullets); {@code kind} picks the backdrop. */
	record Line(FormattedCharSequence text, int indent, String marker, Kind kind) {
		static final Line GAP = new Line(FormattedCharSequence.EMPTY, 0, null, Kind.GAP);
	}

	enum Kind { TEXT, GAP, CODE, QUOTE, RULE, LABEL }

	static final int TEXT = 0xFFE5E7EB;
	static final int MUTED = 0xFF9CA3AF;
	static final int HEADING = 0xFFFFFFFF;
	static final int CODE = 0xFFF0C674;
	static final int LINK = 0xFF7DD3FC;

	private static final Pattern LIST = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
	private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern RULE_LINE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
	private static final Pattern INLINE = Pattern.compile(
		"`([^`]+)`"                                   // code
			+ "|\\*\\*(.+?)\\*\\*|__(.+?)__"          // bold
			+ "|\\[([^\\]]+)]\\(([^)\\s]+)\\)"         // link
			+ "|~~(.+?)~~"                             // strike
			+ "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<!\\s)\\*(?![\\w*])" // italic *x*
			+ "|(?<![\\w_])_(?![\\s_])(.+?)(?<!\\s)_(?![\\w_])");   // italic _x_

	private T3Markdown() {
	}

	/** Appends the wrapped rows for one message body. */
	static void render(Font font, String markdown, int width, int baseColor, List<Line> out) {
		if (markdown == null) return;
		boolean inCode = false;
		for (String raw : markdown.replace("\t", "    ").split("\n", -1)) {
			String trimmed = raw.strip();
			if (trimmed.startsWith("```")) {
				inCode = !inCode;
				continue;
			}
			if (inCode) {
				// Code keeps its spacing; long lines wrap rather than run off the panel.
				Component code = Component.literal(raw.isEmpty() ? " " : raw).withColor(CODE);
				wrap(font, code, width - 16, 8, null, Kind.CODE, out);
				continue;
			}
			if (trimmed.isEmpty()) {
				gap(out);
				continue;
			}
			Matcher heading = HEADING_LINE.matcher(trimmed);
			if (heading.matches()) {
				gap(out);
				wrap(font, inline(heading.group(2), HEADING).withStyle(Style.EMPTY.withBold(true)), width, 0, null, Kind.TEXT, out);
				continue;
			}
			if (RULE_LINE.matcher(trimmed).matches()) {
				out.add(new Line(FormattedCharSequence.EMPTY, 0, null, Kind.RULE));
				continue;
			}
			if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
				if (TABLE_DIVIDER.matcher(trimmed).matches()) continue;
				String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
				MutableComponent row = Component.empty();
				for (int i = 0; i < cells.length; i++) {
					if (i > 0) row.append(Component.literal("  │  ").withColor(0xFF4B5563));
					row.append(inline(cells[i].strip(), baseColor));
				}
				wrap(font, row, width, 0, null, Kind.TEXT, out);
				continue;
			}
			if (trimmed.startsWith(">")) {
				wrap(font, inline(trimmed.replaceFirst("^>\\s?", ""), MUTED), width - 10, 10, null, Kind.QUOTE, out);
				continue;
			}
			Matcher list = LIST.matcher(raw);
			if (list.matches()) {
				int depth = Math.min(4, list.group(1).length() / 2);
				String token = list.group(2);
				String marker = Character.isDigit(token.charAt(0)) ? token.replace(")", ".") : depth % 2 == 0 ? "•" : "◦";
				int indent = 10 + depth * 10 + (marker.length() > 2 ? 6 : 0);
				wrap(font, inline(list.group(3), baseColor), width - indent, indent, marker, Kind.TEXT, out);
				continue;
			}
			wrap(font, inline(trimmed, baseColor), width, 0, null, Kind.TEXT, out);
		}
		while (!out.isEmpty() && out.getLast().kind() == Kind.GAP) out.removeLast();
	}

	/** Inline spans → one styled component. Unmatched markers stay as literal text. */
	static MutableComponent inline(String text, int color) {
		MutableComponent result = Component.empty();
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) result.append(Component.literal(text.substring(last, m.start())).withColor(color));
			if (m.group(1) != null) {
				result.append(Component.literal(m.group(1)).withColor(CODE));
			} else if (m.group(2) != null || m.group(3) != null) {
				result.append(inline(m.group(2) != null ? m.group(2) : m.group(3), color).withStyle(Style.EMPTY.withBold(true)));
			} else if (m.group(4) != null) {
				result.append(Component.literal(m.group(4)).withStyle(Style.EMPTY.withColor(LINK).withUnderlined(true)));
			} else if (m.group(6) != null) {
				result.append(inline(m.group(6), MUTED).withStyle(Style.EMPTY.withStrikethrough(true)));
			} else {
				String italic = m.group(7) != null ? m.group(7) : m.group(8);
				result.append(inline(italic, color).withStyle(Style.EMPTY.withItalic(true)));
			}
			last = m.end();
		}
		if (last < text.length()) result.append(Component.literal(text.substring(last)).withColor(color));
		return result;
	}

	static void wrap(Font font, Component text, int width, int indent, String marker, Kind kind, List<Line> out) {
		List<FormattedCharSequence> parts = font.split(text, Math.max(40, width));
		for (int i = 0; i < parts.size(); i++) {
			out.add(new Line(parts.get(i), indent, i == 0 ? marker : null, kind));
		}
	}

	private static void gap(List<Line> out) {
		if (!out.isEmpty() && out.getLast().kind() != Kind.GAP) out.add(Line.GAP);
	}
}
