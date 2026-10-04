package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol-2 payloads (shapes from T3 Code's orchestrationV2 contracts) reduced to the fields the mod reads. */
class T3V2Test {
	private static JsonObject json(String text) {
		return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
	}

	private static JsonObject shell(String status, String pending, String activity) {
		return T3V2.normalizeShellThread(json("""
			{'id':'t1','projectId':'p1','title':'Fix it','status':'%s',
			 'latestRunId':'r2','activeRunId':%s,
			 'latestRunRequestedAt':'2026-10-03T10:00:00.000Z','latestRunStartedAt':'2026-10-03T10:00:01.000Z',
			 'latestRunCompletedAt':'2026-10-03T10:02:00.000Z','activityRunStatus':%s,
			 'pendingRuntimeRequest':%s,'lastError':null,'latestUserMessageAt':'2026-10-03T10:00:00.000Z',
			 'modelSelection':{'instanceId':'claudeAgent','model':'claude-opus-5-5'},'archivedAt':null}
			""".formatted(status, "running".equals(status) ? "'r2'" : "null", activity == null ? "null" : "'" + activity + "'",
			pending == null ? "null" : "{'id':'q1','kind':'" + pending + "','createdAt':'2026-10-03T10:01:00.000Z'}")));
	}

	@Test
	void runningThreadIsWorking() {
		JsonObject thread = shell("running", null, "running");
		JsonObject turn = thread.getAsJsonObject("latestTurn");
		assertEquals("r2", turn.get("turnId").getAsString());
		assertEquals("running", turn.get("state").getAsString());
		assertEquals("2026-10-03T10:00:01.000Z", turn.get("startedAt").getAsString());
		assertTrue(turn.get("completedAt").isJsonNull());
		assertEquals("running", thread.getAsJsonObject("session").get("status").getAsString());
		assertFalse(thread.get("hasPendingApprovals").getAsBoolean());
	}

	@Test
	void pendingRequestsNeedYou() {
		assertTrue(shell("waiting", "command", "waiting").get("hasPendingApprovals").getAsBoolean());
		JsonObject question = shell("waiting", "user_input", "waiting");
		assertTrue(question.get("hasPendingUserInput").getAsBoolean());
		assertFalse(question.get("hasPendingApprovals").getAsBoolean());
		// Requests the game can't answer don't ping.
		JsonObject auth = shell("waiting", "auth_refresh", "waiting");
		assertFalse(auth.get("hasPendingApprovals").getAsBoolean());
		assertFalse(auth.get("hasPendingUserInput").getAsBoolean());
	}

	@Test
	void settledRunsMapToTurnStates() {
		JsonObject done = shell("completed", null, null);
		assertEquals("completed", done.getAsJsonObject("latestTurn").get("state").getAsString());
		assertEquals("2026-10-03T10:02:00.000Z", done.getAsJsonObject("latestTurn").get("completedAt").getAsString());
		assertEquals("ready", done.getAsJsonObject("session").get("status").getAsString());
		assertEquals("error", shell("failed", null, null).getAsJsonObject("latestTurn").get("state").getAsString());
		assertEquals("interrupted", shell("interrupted", null, null).getAsJsonObject("latestTurn").get("state").getAsString());
	}

	@Test
	void freshThreadHasNoTurn() {
		JsonObject thread = T3V2.normalizeShellThread(json("{'id':'t1','status':'idle','latestRunId':null,'pendingRuntimeRequest':null}"));
		assertTrue(thread.get("latestTurn").isJsonNull());
	}

	@Test
	void protocolOneThreadsPassThrough() {
		JsonObject v1 = json("{'id':'t1','latestTurn':{'turnId':'x','state':'running'},'hasPendingApprovals':false}");
		String before = v1.toString();
		assertSame(v1, T3V2.normalizeShellThread(v1));
		assertEquals(before, v1.toString());
	}

	@Test
	void boundedSnapshotBecomesThreadDetail() {
		JsonObject bounded = json("""
			{'snapshotSequence':42,'hasMoreHistory':true,'historyCursor':'c','latestLocalTurnOrdinal':9,
			 'projection':{
			  'runtimeRequests':[
			   {'id':'a1','kind':'command','status':'pending','createdAt':'2026-10-03T10:01:00.000Z'},
			   {'id':'a0','kind':'command','status':'resolved','createdAt':'2026-10-03T09:01:00.000Z'},
			   {'id':'q1','kind':'user_input','status':'pending','createdAt':'2026-10-03T10:01:00.000Z'},
			   {'id':'z1','kind':'auth_refresh','status':'pending','createdAt':'2026-10-03T10:01:00.000Z'}],
			  'turnItems':[
			   {'type':'approval_request','requestId':'a1','requestKind':'command','prompt':'npm test'}],
			  'checkpoints':[
			   {'id':'c1','capturedAt':'2026-10-03T09:00:00.000Z','files':[{'path':'old.txt','kind':'modified','additions':1,'deletions':0}]},
			   {'id':'c2','capturedAt':'2026-10-03T10:00:00.000Z','files':[{'path':'src/A.java','kind':'modified','additions':3,'deletions':1}]}],
			  'visibleTurnItems':[
			   {'position':0,'visibility':'local','item':{'type':'user_message','text':'first'}},
			   {'position':1,'visibility':'local','item':{'type':'assistant_message','text':'one','streaming':false}},
			   {'position':2,'visibility':'local','item':{'type':'error','failure':{'class':'provider','message':'old failure'}}},
			   {'position':3,'visibility':'local','item':{'type':'user_message','text':'second'}},
			   {'position':4,'visibility':'local','item':{'type':'command_execution','input':'ls'}},
			   {'position':5,'visibility':'local','item':{'type':'user_input_request','requestId':'q1',
			     'questions':[{'id':'k','header':'Pick','question':'Which?','options':[{'label':'A','description':'first'}]}]}},
			   {'position':6,'visibility':'local','item':{'type':'assistant_message','text':'two','streaming':true}}]}}
			""");
		JsonObject detail = T3V2.threadDetail("t1", bounded, 1);
		assertEquals("t1", detail.get("id").getAsString());

		var messages = detail.getAsJsonArray("messages");
		assertEquals(2, messages.size(), "only the last user turn");
		assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("second", messages.get(0).getAsJsonObject().get("text").getAsString());
		assertTrue(messages.get(1).getAsJsonObject().get("streaming").getAsBoolean());
		assertEquals(4, T3V2.threadDetail("t1", bounded, 4).getAsJsonArray("messages").size());

		var activities = detail.getAsJsonArray("activities");
		assertEquals(2, activities.size(), "pending command and question; resolved and auth requests dropped");
		JsonObject approval = activities.get(0).getAsJsonObject();
		assertEquals("approval.requested", approval.get("kind").getAsString());
		assertEquals("a1", approval.getAsJsonObject("payload").get("requestId").getAsString());
		assertEquals("command", approval.getAsJsonObject("payload").get("requestKind").getAsString());
		assertEquals("npm test", approval.getAsJsonObject("payload").get("detail").getAsString());
		JsonObject question = activities.get(1).getAsJsonObject();
		assertEquals("user-input.requested", question.get("kind").getAsString());
		assertEquals("Which?", question.getAsJsonObject("payload").getAsJsonArray("questions").get(0).getAsJsonObject()
			.get("question").getAsString());

		var files = detail.getAsJsonArray("checkpoints").get(0).getAsJsonObject().getAsJsonArray("files");
		assertEquals("src/A.java", files.get(0).getAsJsonObject().get("path").getAsString());
		// The error came before the latest prompt, so it no longer applies.
		assertTrue(detail.getAsJsonObject("session").get("lastError").isJsonNull());
	}

	@Test
	void versionMismatchSaysWhatToUpdate() {
		T3Api.ServerInfo newer = new T3Api.ServerInfo("Laptop", "0.0.99", 3);
		assertFalse(newer.supported());
		assertTrue(newer.mismatchMessage().endsWith("Update T3 Craft."));
		assertTrue(new T3Api.ServerInfo("Laptop", "0.0.45", 1).supported());
		assertTrue(new T3Api.ServerInfo("Laptop", "0.0.46-nightly", 2).supported());
	}
}
