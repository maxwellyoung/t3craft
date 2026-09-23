package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game T3 panel: thread list on the left, the focused conversation on the right,
 * approvals above the composer. Enter sends and drops you back into the world;
 * Shift+Enter sends and keeps the panel open.
 */
final class T3Screen extends Screen {
	private static final int MARGIN = 10;
	private static final int SIDEBAR = 150;
	private static final int ROW = 22;
	private static final int HEADER = 26;
	private static final int COMPOSER = 20;
	private static final int APPROVAL = 34;
	private static final int NEW_WIDTH = 40;

	private final T3CraftClient mod;
	private final long openedAt = System.currentTimeMillis();
	private EditBox composer;
	private Button approve;
	private Button deny;
	private Button stop;
	private boolean newThread;
	private int scroll;
	/** Model chosen in the picker for the next send; null keeps the thread's current model. */
	private JsonObject pickedModel;
	private boolean pickerOpen;
	private int pickerScroll;

	// Answering the agent's questions: which request, which question, and the answers so far.
	private String answeringRequest;
	private int questionIndex;
	private JsonObject answers = new JsonObject();
	private final java.util.LinkedHashSet<String> multiPicked = new java.util.LinkedHashSet<>();
	private final List<int[]> optionRows = new ArrayList<>();

	private record PickerEntry(T3Api.Provider provider, T3Api.Model model, boolean needsNewThread) {}

	private T3State.Focus cachedFocus;
	private int cachedWidth;
	private List<T3Markdown.Line> cachedLines = List.of();

	T3Screen(T3CraftClient mod) {
		super(Component.literal("T3"));
		this.mod = mod;
	}

	@Override
	protected void init() {
		mod.state().setPanelOpen(true);
		int mainX = MARGIN + SIDEBAR + 8;
		int mainWidth = width - mainX - MARGIN;
		int composerY = height - MARGIN - 12 - COMPOSER;

		composer = new EditBox(font, mainX, composerY, mainWidth, COMPOSER, composer, Component.literal("Prompt"));
		composer.setMaxLength(8000);
		if (composer.getValue().isEmpty()) composer.setValue(mod.draft(draftKey()));
		updateHint();
		addRenderableWidget(composer);
		setInitialFocus(composer);

		// Buttons sit on the card's title row so the command gets the full second row.
		int approvalY = composerY - APPROVAL - 2;
		approve = addRenderableWidget(Button.builder(Component.literal("Yes (Y)"), b -> answer("accept"))
			.bounds(mainX + mainWidth - 128, approvalY + 2, 60, 14).build());
		deny = addRenderableWidget(Button.builder(Component.literal("No (N)"), b -> answer("decline"))
			.bounds(mainX + mainWidth - 66, approvalY + 2, 60, 14).build());
		stop = addRenderableWidget(Button.builder(Component.literal("Stop"), b -> mod.interrupt())
			.bounds(width - MARGIN - 44, MARGIN + 3, 44, 18).build());
		syncWidgets(mod.state().snapshot());
	}

	@Override
	public void removed() {
		mod.saveDraft(draftKey(), composer.getValue());
		mod.state().setPanelOpen(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		// Transparent backdrop instead of the menu blur: the world keeps moving behind the panel.
		return true;
	}

	@Override
	public void tick() {
		syncWidgets(mod.state().snapshot());
		updateHint();
	}

	private void syncWidgets(T3State.Snapshot snapshot) {
		boolean hasApproval = snapshot.focus() != null && !snapshot.focus().approvals().isEmpty();
		approve.visible = deny.visible = hasApproval;
		T3State.ThreadRow row = snapshot.focusedRow();
		stop.visible = row != null && row.status() == T3State.Status.WORKING;
	}

	private String draftKey() {
		return newThread ? "new" : mod.state().focusedThreadId();
	}

	private void updateHint() {
		T3State.ThreadRow row = mod.state().snapshot().focusedRow();
		String hint = newThread
			? "New thread in " + (row == null ? "this project" : row.projectTitle()) + "…"
			: "Ask " + (row == null ? "the agent" : "\"" + row.title() + "\"") + "…";
		T3State.Question question = currentQuestion();
		if (question != null && !newThread) {
			hint = question.options().isEmpty() ? "Type your answer…"
				: "Press 1–" + Math.min(9, question.options().size()) + (question.allowCustomAnswer() ? ", or type your own answer" : "")
				+ (question.multiSelect() ? ", then Enter" : "") + "…";
		}
		composer.setHint(Component.literal(hint).withColor(0xFF6B7280));
	}

	private void answer(String decision) {
		T3State.Focus focus = mod.state().snapshot().focus();
		if (focus != null && !focus.approvals().isEmpty()) mod.respond(focus.approvals().getFirst(), decision);
	}

	private T3State.UserInput currentInput() {
		T3State.Focus focus = mod.state().snapshot().focus();
		if (focus == null || focus.userInputs().isEmpty()) return null;
		T3State.UserInput input = focus.userInputs().getFirst();
		if (!input.requestId().equals(answeringRequest)) {
			answeringRequest = input.requestId();
			questionIndex = 0;
			answers = new JsonObject();
			multiPicked.clear();
		}
		return input;
	}

	private T3State.Question currentQuestion() {
		T3State.UserInput input = currentInput();
		return input == null || questionIndex >= input.questions().size() ? null : input.questions().get(questionIndex);
	}

	/** Records one answer; after the last question, sends the whole set. */
	private void answerQuestion(com.google.gson.JsonElement value) {
		T3State.UserInput input = currentInput();
		T3State.Question question = currentQuestion();
		if (input == null || question == null) return;
		answers.add(question.id(), value);
		multiPicked.clear();
		questionIndex++;
		if (questionIndex >= input.questions().size()) {
			mod.answer(input, answers);
			answeringRequest = null;
		}
		updateHint();
	}

	private void pickOption(int index) {
		T3State.Question question = currentQuestion();
		if (question == null || index < 0 || index >= question.options().size()) return;
		String value = question.options().get(index).value();
		if (!question.multiSelect()) {
			answerQuestion(new com.google.gson.JsonPrimitive(value));
			return;
		}
		if (!multiPicked.remove(value)) multiPicked.add(value);
	}

	private void confirmMulti() {
		com.google.gson.JsonArray values = new com.google.gson.JsonArray();
		multiPicked.forEach(values::add);
		answerQuestion(values);
	}

	/** Used by the dev self-test: presses a number key on the question card. */
	void pressOption(int number) {
		keyPressed(new KeyEvent(InputConstants.KEY_1 + number - 1, 0, 0));
	}

	/** Used by the dev self-test. */
	void setComposerForTest(String text) {
		composer.setValue(text);
	}

	/** Used by the dev self-test. */
	String composerValueForTest() {
		return composer.getValue();
	}

	/** Used by the dev self-test: same as clicking + New. */
	void startNewThread() {
		newThread = true;
		updateHint();
	}

	/** Used by the dev self-test. */
	void openPicker() {
		pickerOpen = true;
	}

	/** Used by the dev self-test to drive the real composer path. */
	void typeAndSubmit(String text, boolean stayOpen) {
		composer.setValue(text);
		submit(stayOpen);
	}

	private void submit(boolean stayOpen) {
		String text = composer.getValue().trim();
		if (text.isEmpty()) return;
		mod.send(text, newThread, pickedModel);
		mod.saveDraft(draftKey(), "");
		composer.setValue("");
		newThread = false;
		pickedModel = null;
		scroll = 0;
		updateHint();
		if (!stayOpen) onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (pickerOpen && event.isEscape()) {
			pickerOpen = false;
			return true;
		}
		T3State.Question question = newThread ? null : currentQuestion();
		boolean composerEmpty = composer.getValue().isEmpty();
		if (question != null && composerEmpty && event.key() >= InputConstants.KEY_1 && event.key() <= InputConstants.KEY_9) {
			pickOption(event.key() - InputConstants.KEY_1);
			return true;
		}
		if (question != null && event.isConfirmation()) {
			String text = composer.getValue().trim();
			if (!text.isEmpty() && question.allowCustomAnswer()) {
				composer.setValue("");
				answerQuestion(new com.google.gson.JsonPrimitive(text));
			} else if (text.isEmpty() && question.multiSelect() && !multiPicked.isEmpty()) {
				confirmMulti();
			}
			return true;
		}
		if (event.isConfirmation() && composer.isFocused()) {
			submit(event.hasShiftDown());
			return true;
		}
		if (composerEmpty && (event.key() == InputConstants.KEY_Y || event.key() == InputConstants.KEY_N)
			&& approve.visible) {
			answer(event.key() == InputConstants.KEY_Y ? "accept" : "decline");
			return true;
		}
		if (composerEmpty && event.key() == InputConstants.KEY_GRAVE && System.currentTimeMillis() - openedAt > 250) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// Swallow the ` that opened the panel, digits that picked an option, and Y/N that answered an approval.
		if (composer.getValue().isEmpty() && !newThread && currentQuestion() != null && event.codepoint() >= '1' && event.codepoint() <= '9') {
			return true;
		}
		if (composer.getValue().isEmpty() && (event.codepoint() == '`'
			|| (approve.visible && (event.codepoint() == 'y' || event.codepoint() == 'n' || event.codepoint() == 'Y' || event.codepoint() == 'N')))) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (pickerOpen) {
			int[] box = pickerBox();
			if (event.x() >= box[0] && event.x() < box[2] && event.y() >= box[1] && event.y() < box[3]) {
				int index = (int) ((event.y() - box[1] - 2) / PICKER_ROW) + pickerScroll;
				List<Object> rows = pickerRows();
				if (index >= 0 && index < rows.size() && rows.get(index) instanceof PickerEntry entry) {
					pickedModel = T3Api.modelSelection(entry.provider().instanceId(), entry.model().slug());
					if (entry.needsNewThread()) newThread = true;
					updateHint();
					pickerOpen = false;
					setFocused(composer);
				}
				return true;
			}
			pickerOpen = false;
			return true;
		}
		if (chipContains(event.x(), event.y())) {
			pickerOpen = true;
			pickerScroll = 0;
			return true;
		}
		for (int[] optionRow : optionRows) {
			if (event.x() >= optionRow[0] && event.x() < optionRow[2] && event.y() >= optionRow[1] && event.y() < optionRow[3]) {
				pickOption(optionRow[4]);
				return true;
			}
		}
		List<T3State.ThreadRow> rows = mod.state().snapshot().threads();
		int listTop = MARGIN + HEADER;
		if (event.x() >= MARGIN && event.x() < MARGIN + SIDEBAR && event.y() >= listTop) {
			int index = (int) ((event.y() - listTop) / ROW);
			if (index < rows.size()) {
				mod.saveDraft(draftKey(), composer.getValue());
				mod.focus(rows.get(index).id());
				newThread = false;
				composer.setValue(mod.draft(draftKey()));
				pickedModel = null;
				pickerOpen = false;
				scroll = 0;
				updateHint();
				return true;
			}
		}
		if (event.x() >= MARGIN + SIDEBAR - NEW_WIDTH && event.x() < MARGIN + SIDEBAR && event.y() >= MARGIN && event.y() < MARGIN + HEADER) {
			newThread = !newThread;
			updateHint();
			setFocused(composer);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (pickerOpen) {
			int visible = (pickerBox()[3] - pickerBox()[1] - 4) / PICKER_ROW;
			pickerScroll = Math.max(0, Math.min(pickerRows().size() - visible, pickerScroll - (int) Math.signum(scrollY) * 2));
			return true;
		}
		scroll = Math.max(0, scroll + (int) Math.signum(scrollY) * 3);
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		T3State.Snapshot snapshot = mod.state().snapshot();
		mouseXCache = mouseX;
		mouseYCache = mouseY;
		extractSidebar(graphics, snapshot, mouseX, mouseY);
		extractConversation(graphics, snapshot);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		extractModelChip(graphics, snapshot, mouseX, mouseY);
		if (pickerOpen) {
			graphics.nextStratum();
			extractPicker(graphics, mouseX, mouseY);
		}
	}

	private void extractSidebar(GuiGraphicsExtractor graphics, T3State.Snapshot snapshot, int mouseX, int mouseY) {
		int x0 = MARGIN;
		int x1 = MARGIN + SIDEBAR;
		graphics.fill(x0, MARGIN, x1, height - MARGIN, 0xC0101014);
		graphics.text(font, "T3 Code", x0 + 8, MARGIN + 8, 0xFFFFFFFF, false);
		int connection = snapshot.connected() ? 0xFF4ADE80 : snapshot.error() == null ? 0xFF9CA3AF : 0xFFF87171;
		graphics.fill(x0 + 12 + font.width("T3 Code"), MARGIN + 10, x0 + 16 + font.width("T3 Code"), MARGIN + 14, connection);
		// Lives in the header so it stays reachable however many threads there are.
		boolean newHovered = mouseX >= x1 - NEW_WIDTH && mouseX < x1 && mouseY >= MARGIN && mouseY < MARGIN + HEADER;
		if (newThread) graphics.fill(x1 - NEW_WIDTH, MARGIN + 3, x1 - 3, MARGIN + HEADER - 5, 0x40FFFFFF);
		else if (newHovered) graphics.fill(x1 - NEW_WIDTH, MARGIN + 3, x1 - 3, MARGIN + HEADER - 5, 0x20FFFFFF);
		graphics.text(font, "+ New", x1 - NEW_WIDTH + 5, MARGIN + 8, 0xFFE5E7EB, false);

		int y = MARGIN + HEADER;
		String focused = mod.state().focusedThreadId();
		for (T3State.ThreadRow row : snapshot.threads()) {
			if (y + ROW > height - MARGIN) break;
			boolean hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + ROW;
			if (row.id().equals(focused) && !newThread) graphics.fill(x0 + 2, y, x1 - 2, y + ROW, 0x40FFFFFF);
			else if (hovered) graphics.fill(x0 + 2, y, x1 - 2, y + ROW, 0x20FFFFFF);
			graphics.fill(x0 + 8, y + 5, x0 + 12, y + 9, T3Hud.color(row.status()));
			graphics.text(font, T3Hud.ellipsize(font, row.title(), SIDEBAR - 24), x0 + 16, y + 3, 0xFFE5E7EB, false);
			String meta = (row.environment() == null ? "" : row.environment() + " · ") + row.projectTitle() + " · " + T3Hud.label(row.status());
			graphics.text(font, T3Hud.ellipsize(font, meta, SIDEBAR - 24), x0 + 16, y + 12, 0xFF6B7280, false);
			y += ROW;
		}
	}

	private void extractConversation(GuiGraphicsExtractor graphics, T3State.Snapshot snapshot) {
		int x0 = MARGIN + SIDEBAR + 8;
		int x1 = width - MARGIN;
		graphics.fill(x0, MARGIN, x1, height - MARGIN, 0xB0101014);

		T3State.ThreadRow row = snapshot.focusedRow();
		T3State.Focus focus = snapshot.focus();
		if (!snapshot.connected() && row == null) {
			String message = snapshot.error() == null ? "Connecting to T3…" : snapshot.error();
			graphics.textWithWordWrap(font, Component.literal(message), x0 + 10, MARGIN + 10, x1 - x0 - 20, 0xFFF87171);
			return;
		}
		if (row == null && !newThread) {
			String hint = snapshot.threads().isEmpty() ? "No threads yet. Click + New to start one." : "Pick a thread on the left, or click + New.";
			graphics.text(font, hint, x0 + 10, MARGIN + 10, 0xFF9CA3AF, false);
			return;
		}
		if (row == null) row = snapshot.threads().isEmpty() ? null : snapshot.threads().getFirst();
		if (row == null) return;

		String title = newThread ? "New thread" : row.title();
		graphics.text(font, T3Hud.ellipsize(font, title, chipX() - x0 - 20), x0 + 10, MARGIN + 6, 0xFFFFFFFF, false);
		String status = T3Hud.label(row.status());
		if (row.status() == T3State.Status.WORKING) status += " " + T3Hud.elapsed(row.workingSince()) + (row.step() == null ? "" : " · " + row.step());
		String meta = (row.environment() == null ? "" : row.environment() + " · ") + row.projectTitle() + " · ";
		graphics.text(font, meta, x0 + 10, MARGIN + 16, 0xFF6B7280, false);
		graphics.text(font, T3Hud.ellipsize(font, status, chipX() - x0 - 20 - font.width(meta)), x0 + 10 + font.width(meta), MARGIN + 16, T3Hud.color(row.status()), false);
		graphics.horizontalLine(x0, x1 - 1, MARGIN + HEADER, 0x30FFFFFF);

		boolean hasApproval = focus != null && !focus.approvals().isEmpty();
		int composerY = height - MARGIN - 12 - COMPOSER;
		T3State.Question question = newThread ? null : currentQuestion();
		int questionHeight = question == null ? 0 : questionCardHeight(question, x1 - x0 - 24);
		int bottom = composerY - 6 - (hasApproval ? APPROVAL : 0) - questionHeight;
		int top = MARGIN + HEADER + 4;

		if (!pickerOpen && !newThread && focus != null && focus.threadId().equals(row.id())) {
			List<T3Markdown.Line> lines = lines(focus, x1 - x0 - 28);
			int visible = Math.max(1, (bottom - top) / font.lineHeight);
			scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
			int end = lines.size() - scroll;
			int start = Math.max(0, end - visible);
			graphics.enableScissor(x0, top, x1, bottom);
			int y = bottom - (end - start) * font.lineHeight;
			for (int i = start; i < end; i++) {
				T3Markdown.Line line = lines.get(i);
				int textX = x0 + 10 + line.indent();
				switch (line.kind()) {
					case RULE -> graphics.horizontalLine(x0 + 10, x1 - 12, y + 4, 0x40FFFFFF);
					case CODE -> graphics.fill(x0 + 10, y - 1, x1 - 12, y + font.lineHeight, 0x70000000);
					case QUOTE -> graphics.fill(x0 + 10, y - 1, x0 + 12, y + font.lineHeight, 0x60FFFFFF);
					default -> {
					}
				}
				if (line.marker() != null) {
					graphics.text(font, line.marker(), textX - 4 - font.width(line.marker()), y, T3Markdown.MUTED, false);
				}
				graphics.text(font, line.text(), textX, y, T3Markdown.TEXT, false);
				y += font.lineHeight;
			}
			graphics.disableScissor();
			if (lines.isEmpty()) graphics.text(font, "No messages yet.", x0 + 10, top + 4, 0xFF6B7280, false);
		}

		optionRows.clear();
		if (question != null) {
			extractQuestion(graphics, question, x0, x1, composerY - (hasApproval ? APPROVAL : 0) - questionHeight - 2, mouseXCache, mouseYCache);
		}
		if (hasApproval) {
			T3State.Approval approval = focus.approvals().getFirst();
			int y = composerY - APPROVAL - 2;
			graphics.fill(x0 + 6, y, x1 - 6, y + APPROVAL - 2, 0x40FFB02E);
			graphics.text(font, "Approve " + approval.kind() + "?", x0 + 12, y + 5, 0xFFFFB02E, false);
			String detail = approval.detail() == null ? "" : approval.detail();
			graphics.text(font, T3Hud.ellipsize(font, detail, x1 - x0 - 24), x0 + 12, y + 18, 0xFFE5E7EB, false);
		}

		String footer = "Enter send · Shift+Enter stay · " + (hasApproval ? "Y/N answer · " : "") + "Esc play";
		graphics.text(font, T3Hud.ellipsize(font, footer, x1 - x0 - 4), x0 + 2, height - MARGIN - 10, 0xFF6B7280, false);
	}

	private int mouseXCache;
	private int mouseYCache;

	private int questionCardHeight(T3State.Question question, int width) {
		int lines = 1 + font.split(Component.literal(question.question() == null ? "" : question.question()), width).size();
		// + one line for the key hint (the input's own placeholder is hidden while it has focus).
		return 8 + lines * font.lineHeight + question.options().size() * (font.lineHeight + 2) + 4 + font.lineHeight + 2;
	}

	private void extractQuestion(GuiGraphicsExtractor graphics, T3State.Question question, int x0, int x1, int y, int mouseX, int mouseY) {
		T3State.UserInput input = currentInput();
		int width = x1 - x0 - 24;
		int height = questionCardHeight(question, width);
		graphics.fill(x0 + 6, y, x1 - 6, y + height, 0x405EA8FF);
		String step = input != null && input.questions().size() > 1 ? "  " + (questionIndex + 1) + "/" + input.questions().size() : "";
		graphics.text(font, (question.header() == null ? "Question" : question.header()) + step, x0 + 12, y + 4, 0xFF93C5FD, false);
		int lineY = y + 4 + font.lineHeight;
		for (var part : font.split(Component.literal(question.question() == null ? "" : question.question()), width)) {
			graphics.text(font, part, x0 + 12, lineY, 0xFFE5E7EB, false);
			lineY += font.lineHeight;
		}
		lineY += 2;
		for (int i = 0; i < question.options().size(); i++) {
			T3State.Option option = question.options().get(i);
			int rowTop = lineY - 1;
			int rowBottom = lineY + font.lineHeight + 1;
			boolean hovered = mouseX >= x0 + 8 && mouseX < x1 - 8 && mouseY >= rowTop && mouseY < rowBottom;
			boolean picked = multiPicked.contains(option.value());
			if (hovered || picked) graphics.fill(x0 + 8, rowTop, x1 - 8, rowBottom, picked ? 0x505EA8FF : 0x25FFFFFF);
			String key = (i < 9 ? String.valueOf(i + 1) : " ") + (question.multiSelect() ? (picked ? " [x] " : " [ ] ") : "  ");
			graphics.text(font, key, x0 + 12, lineY, 0xFFFFB02E, false);
			int labelX = x0 + 12 + font.width(key);
			String label = option.label();
			graphics.text(font, T3Hud.ellipsize(font, label, x1 - labelX - 14), labelX, lineY, 0xFFFFFFFF, false);
			if (option.description() != null && !option.description().isBlank()) {
				int descX = labelX + font.width(label) + 6;
				if (descX < x1 - 40) {
					graphics.text(font, T3Hud.ellipsize(font, "— " + option.description(), x1 - descX - 14), descX, lineY, 0xFF9CA3AF, false);
				}
			}
			optionRows.add(new int[] {x0 + 8, rowTop, x1 - 8, rowBottom, i});
			lineY += font.lineHeight + 2;
		}
		String keys = question.options().isEmpty() ? "Type your answer below, then Enter"
			: "Press 1–" + Math.min(9, question.options().size()) + (question.multiSelect() ? ", then Enter" : "")
			+ (question.allowCustomAnswer() ? ", or type your own" : "");
		graphics.text(font, T3Hud.ellipsize(font, keys, width), x0 + 12, lineY + 1, 0xFF9CA3AF, false);
	}

	private static final int PICKER_ROW = 11;
	private static final int PICKER_WIDTH = 210;

	/** The model the next send will use: the picked one, else the thread's. */
	private JsonObject effectiveModel() {
		if (pickedModel != null) return pickedModel;
		T3State.ThreadRow row = mod.state().snapshot().focusedRow();
		if (row == null || !row.raw().has("modelSelection") || !row.raw().get("modelSelection").isJsonObject()) return null;
		return row.raw().getAsJsonObject("modelSelection");
	}

	private String modelLabel(JsonObject selection) {
		if (selection == null) return "Model";
		String instanceId = selection.get("instanceId").getAsString();
		String slug = selection.get("model").getAsString();
		for (T3Api.Provider provider : mod.state().providers()) {
			if (!provider.instanceId().equals(instanceId)) continue;
			for (T3Api.Model model : provider.models()) if (model.slug().equals(slug)) return model.name();
		}
		return slug;
	}

	// The chip's right edge stays put whether or not Stop is showing.
	private int chipRight() {
		return width - MARGIN - 52;
	}

	private int chipX() {
		return chipRight() - font.width(modelLabel(effectiveModel()) + " ▾") - 10;
	}

	private boolean chipContains(double x, double y) {
		return x >= chipX() && x < chipRight() && y >= MARGIN + 4 && y < MARGIN + 20;
	}

	private void extractModelChip(GuiGraphicsExtractor graphics, T3State.Snapshot snapshot, int mouseX, int mouseY) {
		if (snapshot.focusedRow() == null) return;
		String label = modelLabel(effectiveModel()) + " ▾";
		boolean hovered = chipContains(mouseX, mouseY);
		graphics.fill(chipX(), MARGIN + 4, chipRight(), MARGIN + 20, pickerOpen || hovered ? 0x50FFFFFF : 0x28FFFFFF);
		graphics.text(font, label, chipX() + 5, MARGIN + 8, pickedModel != null ? 0xFFFFB02E : 0xFFE5E7EB, false);
	}

	/** Provider headers (String) and selectable models (PickerEntry), in display order. */
	private List<Object> pickerRows() {
		List<Object> rows = new ArrayList<>();
		T3State.ThreadRow row = mod.state().snapshot().focusedRow();
		JsonObject current = row == null ? null : row.raw().getAsJsonObject("modelSelection");
		boolean started = row != null && row.raw().has("latestTurn") && !row.raw().get("latestTurn").isJsonNull();
		String currentInstance = current == null ? null : current.get("instanceId").getAsString();
		boolean currentLocked = mod.state().providers().stream()
			.anyMatch(p -> p.instanceId().equals(currentInstance) && p.requiresNewThreadForModelChange());
		for (T3Api.Provider provider : mod.state().providers()) {
			rows.add(provider.name());
			for (T3Api.Model model : provider.models()) {
				boolean same = current != null && provider.instanceId().equals(currentInstance)
					&& model.slug().equals(current.get("model").getAsString());
				// Same rule as T3's composer: only providers that forbid switching force a new thread.
				boolean needsNewThread = !newThread && started && !same && (currentLocked || provider.requiresNewThreadForModelChange());
				rows.add(new PickerEntry(provider, model, needsNewThread));
			}
		}
		return rows;
	}

	private int[] pickerBox() {
		int x1 = chipRight();
		int x0 = x1 - PICKER_WIDTH;
		int y0 = MARGIN + 21;
		int maxBottom = height - MARGIN - 12 - COMPOSER - 4;
		int rows = Math.max(1, pickerRows().size());
		return new int[] {x0, y0, x1, Math.min(maxBottom, y0 + 4 + rows * PICKER_ROW)};
	}

	private void extractPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int[] box = pickerBox();
		graphics.fill(box[0], box[1], box[2], box[3], 0xFF16161C);
		graphics.outline(box[0], box[1], box[2] - box[0], box[3] - box[1], 0x40FFFFFF);
		List<Object> rows = pickerRows();
		if (rows.isEmpty()) {
			graphics.text(font, "Loading models…", box[0] + 6, box[1] + 4, 0xFF9CA3AF, false);
			return;
		}
		JsonObject selected = effectiveModel();
		int visible = (box[3] - box[1] - 4) / PICKER_ROW;
		graphics.enableScissor(box[0], box[1], box[2], box[3]);
		for (int i = 0; i < visible && i + pickerScroll < rows.size(); i++) {
			Object entry = rows.get(i + pickerScroll);
			int y = box[1] + 2 + i * PICKER_ROW;
			if (entry instanceof String header) {
				graphics.text(font, header, box[0] + 6, y + 2, 0xFF6B7280, false);
				continue;
			}
			PickerEntry pick = (PickerEntry) entry;
			boolean isSelected = selected != null && pick.provider().instanceId().equals(selected.get("instanceId").getAsString())
				&& pick.model().slug().equals(selected.get("model").getAsString());
			if (mouseX >= box[0] && mouseX < box[2] && mouseY >= y && mouseY < y + PICKER_ROW) {
				graphics.fill(box[0] + 1, y, box[2] - 1, y + PICKER_ROW, 0x30FFFFFF);
			}
			String suffix = pick.needsNewThread() ? "  new thread" : "";
			String name = T3Hud.ellipsize(font, pick.model().name(), PICKER_WIDTH - 24 - font.width(suffix));
			graphics.text(font, (isSelected ? "• " : "  ") + name, box[0] + 8, y + 2, isSelected ? 0xFFFFFFFF : 0xFFD1D5DB, false);
			if (!suffix.isEmpty()) graphics.text(font, suffix, box[2] - 6 - font.width(suffix), y + 2, 0xFF6B7280, false);
		}
		graphics.disableScissor();
	}

	/** Rendered conversation rows, rebuilt only when the focus snapshot or width changes. */
	private List<T3Markdown.Line> lines(T3State.Focus focus, int width) {
		if (focus == cachedFocus && width == cachedWidth) return cachedLines;
		List<T3Markdown.Line> lines = new ArrayList<>();
		for (T3State.Message message : focus.messages()) {
			boolean user = "user".equals(message.role());
			if (!lines.isEmpty()) lines.add(T3Markdown.Line.GAP);
			Component label = Component.literal(user ? "You" : "Agent").withStyle(style -> style.withBold(true)
				.withColor(user ? 0xFF93C5FD : 0xFFA7F3D0));
			T3Markdown.wrap(font, label, width, 0, null, T3Markdown.Kind.LABEL, lines);
			String text = message.text() + (message.streaming() ? " ▍" : "");
			T3Markdown.render(font, text, width - 8, user ? 0xFFD1D5DB : T3Markdown.TEXT, lines);
		}
		cachedFocus = focus;
		cachedWidth = width;
		cachedLines = lines;
		return lines;
	}
}
