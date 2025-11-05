package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import java.util.UUID


@CommandAlias("test")
@CommandPermission("trialore.test")
class TestCommand(
    private val trialORE: TrialOre,
) : BaseCommand() {

    @Default()
    @CommandAlias("starttest")
    @Subcommand("start")
    @Conditions("notTesting")
    @Description("Take a test")
    fun onStart(testificate: Player) {
        if (trialORE.testMapping.containsKey(testificate.uniqueId)) {
            throw TrialOreException("You are already testing. This is 99.9% a Bug. Contact Nick :D")
        }
        if (trialORE.database.hasPassedTheTest(testificate.uniqueId)) {
            testificate.renderMiniMessage("<green>You already passed the test!")
            return
        }
        val tests = trialORE.database.getTests(testificate.uniqueId)
        val lastThreeTests = tests.takeLast(3)
        if (lastThreeTests.size == 3) {
            val threeDaysAgo = Instant.now() - Duration.ofDays(3)
            val lastThreeWithin24h = lastThreeTests.all { testInfo ->
                testInfo.start.isAfter(threeDaysAgo)
            }
            if (lastThreeWithin24h) {
                testificate.renderMiniMessage("<red>Warning: Your last 3 tests were all taken within the last 24 hours! Do /test history to see them.</red>")
                return
            }
        }
        testificate.renderMessage("Starting your test!")
        testificate.renderMiniMessage("<red>Note: When answering in binary, don't use a prefix like 0b.")
        trialORE.startTest(testificate.uniqueId)
    }

    @CommandAlias("stoptest")
    @Subcommand("stop")
    @Conditions("testing")
    @Description("Stop a test")
    fun onStop(player: Player, testMeta: TestMeta) {
        player.renderMessage("You have exited your test")
        trialORE.endTest(player.uniqueId, testMeta.session.startTime, false, wrong = 25)
    }

    @CommandAlias("testanswer")
    @Subcommand("answer")
    @Conditions("testing")
    @Description("Answer a test question")
    fun onAnswer(player: Player, testMeta: TestMeta, answer: String) {
        val session = trialORE.testSessions[player.uniqueId]
            ?: throw TrialOreException("No active test session found. This is likely a bug.")

        val expected = session.currentAnswer.trim()
        val provided = answer.trim()

        val isCorrect = try {
            val expNum = if (expected.matches(Regex("^[01]{1,}$"))) {
                Integer.parseInt(expected, 2)
            } else Integer.parseInt(expected)
            val provNum = if (provided.matches(Regex("^[01]{1,}$"))) {
                Integer.parseInt(provided, 2)
            } else Integer.parseInt(provided)
            expNum == provNum
        } catch (e: NumberFormatException) {
            expected.equals(provided, ignoreCase = true)
        }

        if (isCorrect) {
            player.renderMiniMessage("<green>Correct!</green>")
        } else {
            player.renderMiniMessage("<red>Incorrect Answer. Expected: $expected</red>")
            session.wrong++
            // trialORE.database.setTestWrong(session.testId, session.wrong)
        }

        session.index++
        trialORE.sendNextQuestion(player, session)
    }

    @Subcommand("list")
    @CommandPermission("trialore.list")
    @Description("Get the info of an individual from past tests")
    @CommandCompletion("@usernameCache")
    fun onList(player: Player, @Single target: String, @Default("any") filter: TestFilter) {
        val testificate = trialORE.server.getPlayer(target)?.uniqueId
            ?: trialORE.database.usernameToUuidCache[target]
            ?: throw TrialOreException("Invalid target $target. Please provide an online player or UUID")
        listTests(player, testificate, "$target has", filter, true)
    }

    @Subcommand("history")
    @CommandPermission("trialore.test")
    @Description("Get your test history")
    fun onHistory(player: Player, @Default("any") filter: TestFilter) {
        listTests(player, player.uniqueId, "You have", filter, false)
    }

    private fun TestFilter.matches(passed: Boolean) = when (this) {
        TestFilter.ANY -> true
        TestFilter.FAIL -> !passed
        TestFilter.PASS -> passed
    }

    private fun listTests(
        player: Player,
        testificate: UUID,
        header: String,
        filter: TestFilter,
        includeSpeedWarning: Boolean = false,
    ) {
        val allTests = trialORE.database.getTests(testificate)
        val tests = allTests.filter { filter.matches(it.passed) }
        val showingText = when (filter) {
            TestFilter.ANY -> "all: "
            TestFilter.FAIL -> "only failed:"
            TestFilter.PASS -> "only passed:"
        }
        player.renderMiniMessage("$header taken ${allTests.size} tests (showing $showingText ${tests.size})")
        tests.forEach { player.renderMiniMessage(it.toMiniMessage(includeSpeedWarning)) }
    }

    private fun TestInfo.toMiniMessage(includeSpeedWarning: Boolean = false): String {
        val relativeTimestamp = start.toRelativeTimestamp()
        val duration = Duration.between(start, end)
        val numQuestions = 25
        val correct = numQuestions - wrong
        val percentage = "%.1f".format(100 * correct.toDouble() / numQuestions.toDouble())
        val speedLimit = Duration.ofSeconds(45)
        val speedWarning = if (includeSpeedWarning && duration < speedLimit) {
            ":rotating_light: :rotating_light: :rotating_light: Test done in ${duration.minSec()} (under ${speedLimit.minSec()})"
        } else ""
        val (state, color) = if (passed) {
            "Passed" to "<green>"
        } else {
            "Failed" to "<red>"
        }
        val info = "$color$state<gray> in <white>${duration.minSec()}! $correct<gray>/<white>$numQuestions <gray>($color$percentage%<gray>)"
        val text = "Test $attempt, $relativeTimestamp: $info $speedWarning"
        val hoverText = "<white>At <gray>${getDate(start)}<white>: $info"
        return "<hover:show_text:'$hoverText'>$text</hover>"
    }

    @Subcommand("check")
    @CommandPermission("trialore.list")
    @Description("Check if a user passed the test")
    @CommandAlias("check")
    fun onCheck(player: Player, target: String) {
        val testificate = Bukkit.getOfflinePlayer(target)
        val tests = trialORE.database.getTests(testificate.uniqueId)
        if (tests.isEmpty()) {
            player.renderMiniMessage("<red>User <white>$target <red>has not been in any test.")
            return
        }
        if (tests.any { it.passed }) {
            player.renderMiniMessage("<green>User <white>$target <green>has passed the test!")
            return
        }
        player.renderMiniMessage("<red>User <white>$target <red>has failed all their tests!")
    }
}
