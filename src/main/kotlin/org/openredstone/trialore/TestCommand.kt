package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player


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
    fun onStart(player: Player) {
        val testificate = player

        if (trialORE.testMapping.containsKey(testificate.uniqueId)) {
            throw TrialOreException("You are already testing. This is 99.9% a Bug. Contact Nick :D")
        }
        if (trialORE.database.didPass(testificate.uniqueId)) {
            player.renderMiniMessage("<green>You already passed the test!")
            return
        }
        val tests = trialORE.database.getTests(testificate.uniqueId)

        val now = System.currentTimeMillis()
        val lastThreeTests = tests.takeLast(3)
        if (lastThreeTests.size == 3) {
            val lastThreeWithin24h = lastThreeTests.all { test ->
                val testInfo = trialORE.database.getTestInfo(test)
                    ?: run {
                        player.sendMessage("Could not get last 3 tests... Report this to Staff.")
                        return@all false
                    }
                val startTimeMs = testInfo.start.toLong() * 1000L
                now - startTimeMs <= 24 * 60 * 60 * 1000L
            }
            if (lastThreeWithin24h) {
                player.renderMiniMessage("<red>Warning: Your last 3 tests were all taken within the last 24 hours! Do /test history to see them.</red>")
                return
            }
        }
        testificate.renderMessage("Starting your test!")
        testificate.renderMiniMessage("<red>Note: When answering in binary, don't use a prefix like 0b.")
        trialORE.startTest(testificate.uniqueId)
    }


    @Subcommand("list")
    @CommandPermission("trialore.list")
    @Description("Get the info of an individual from past tests")
    @CommandCompletion("@usernameCache")
    fun onList(player: Player, @Single target: String) {
        var testificate = trialORE.server.onlinePlayers.firstOrNull { it.name == target }?.uniqueId
        if (testificate == null) {
            testificate = trialORE.database.usernameToUuidCache[target]
                ?: throw TrialOreException("Invalid target $target. Please provide an online player or UUID")
        }
        val tests = trialORE.database.getTests(testificate)
        player.renderMiniMessage("<gray>$target has taken ${tests.size} tests")
        tests.forEachIndexed { index, test ->
            val testInfo = trialORE.database.getTestInfo(test)
            if (testInfo == null) {
                player.sendMessage("Test id was null. If you are Nick, have fun. If not, report to Nick.")
                return
            }
            if (testInfo.passed) {
                val startTime = testInfo.start.toLong()
                val timestamp = getRelativeTimestamp(startTime)
                val wrong = testInfo.wrong
                val correct = 25 - wrong
                val percentage = (correct.toDouble() / 25.toDouble()) * 100
                val duration = testInfo.end.toLong() - startTime
                var rotatingLight = ""
                if (duration <= 45) { rotatingLight = ":rotating_light: :rotating_light: :rotating_light: Test done in ${duration}s"}
                player.renderMiniMessage("<hover:show_text:'At <gray>${getDate(startTime)}<white>" +
                        ", $wrong wrong Answers'>Test: ${index+1} (<green>${percentage}<white>), $timestamp</hover> $rotatingLight")
            }
        }
    }

    @Subcommand("info")
    @CommandPermission("trialore.list")
    @Description("Check a user's test")
    fun onInfo(player: Player, @Single id: Int) {
        val testInfo = trialORE.database.getTestInfo(id)
        if (testInfo == null) {
            player.sendMessage("Test not existant. If you are Nick, have fun. If you believe this is an error, report to Nick.")
            return
        }
        val startTime = testInfo.start.toLong()
        val duration = testInfo.end.toLong() - startTime
        var rotatingLight = ""
        if (duration <= 45) { rotatingLight = ":rotating_light: :rotating_light: :rotating_light: Test done in ${duration}s"}
        val timestamp = getRelativeTimestamp(startTime)
        val wrong = testInfo.wrong
        val testificate = testInfo.testificate
        val correct = 25 - wrong
        val percentage = (correct.toDouble() / 25.toDouble()) * 100
        if (testInfo.passed) {
            player.renderMiniMessage("<hover:show_text:'At <gray>${getDate(startTime)}<white> by $testificate" +
                    ", $wrong wrong Answers'><green>Passed! <gray>Test: $id (<green>${percentage}<gray>), $timestamp</hover><white> $rotatingLight")
        } else {
            player.renderMiniMessage("<hover:show_text:'<red>Failed! At <gray>${getDate(startTime)}<white> by $testificate" +
                    ", $wrong wrong Answers'><red>Failed! <gray>Test: $id (<red>${percentage}<gray>), $timestamp</hover>")
        }
    }

    @Subcommand("check")
    @CommandPermission("trialore.list")
    @Description("Check if a User passed the test")
    @CommandAlias("check")
    fun onCheck(player: Player, target: String) {
        val testificate = Bukkit.getOfflinePlayer(target)
        val tests = trialORE.database.getTests(testificate.uniqueId)
        if (tests.isEmpty()) {
            player.renderMiniMessage("<red>Target has not been in any test.")
            return
        }
        tests.forEachIndexed { index, testid ->
            val testInfo = trialORE.database.getTestInfo(testid)
            if (testInfo?.passed ?: false) {
                player.renderMiniMessage("<green>Target passed the test!")
                return
            }
        }
        player.renderMiniMessage("<red>Target has failed all their tests!")
    }

    @Subcommand("history")
    @CommandPermission("trialore.test")
    @Description("Get your test history")
    fun onHistory(player: Player) {
        val testificate = player.uniqueId
        val tests = trialORE.database.getTests(testificate)
        player.renderMiniMessage("<gray>You have taken ${tests.size} tests")
        tests.forEachIndexed { index, testid ->
            val testInfo = trialORE.database.getTestInfo(testid)
            if (testInfo == null) {
                player.sendMessage("Test id was null. Report this to Staff.")
                return
            }
            var state = ""
            var color = ""
            if (testInfo.passed) {
                state = "Passed"
                color = "<green>"
            } else {
                state = "Failed"
                color = "<red>"
            }
            val startTime = testInfo.start.toLong()
            val timestamp = getRelativeTimestamp(startTime)
            val wrong = testInfo.wrong
            val correct = 25 - wrong
            val percentage = (correct.toDouble() / 25.toDouble()) * 100
            player.renderMiniMessage("<hover:show_text:'At <gray>${getDate(startTime)}<white>" +
                    " (State: $color${state}<white>), with ${wrong} wrong Answers'><gray>Test ${index+1} (ID: $testid), $timestamp</hover>: $color$percentage%<gray>")
        }
    }

    @CommandAlias("stoptest")
    @Subcommand("stop")
    @Conditions("testing")
    @Description("Stop a test")
    fun onStop(player: Player, testMeta: TestMeta) {
        player.renderMessage("You have exited your test")
        trialORE.endTest(player.uniqueId, testMeta.session.startingtime, false, wrong = 25)
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
}
