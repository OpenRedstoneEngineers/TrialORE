package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.entity.Player
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

fun getDate(timestamp: Long) = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC)

fun getRelativeTimestamp(unixTimestamp: Long): String {
    val currentTime = LocalDateTime.now(ZoneOffset.UTC)
    val eventTime = getDate(unixTimestamp)

    val difference = ChronoUnit.MINUTES.between(eventTime, currentTime)

    return when {
        difference < 1 -> "just now"
        difference < 60 -> "$difference minutes ago"
        difference < 120 -> "an hour ago"
        difference < 1440 -> "${difference / 60} hours ago"
        else -> "${difference / 1440} days ago"
    }
}

@CommandAlias("trial")
@CommandPermission("trialore.trial")
class TrialCommand(
    private val trialORE: TrialOre,
    private val version: String,
) : BaseCommand() {

    @Default()
    @Subcommand("info")
    @Description("Information about a TrialORE")
    fun onInfo(player: Player) {
        player.renderMiniMessage("Current TrialORE version: <gray>$version")
        player.renderMiniMessage("For more details on commands, " +
            "<aqua><click:open_url:'https://github.com/OpenRedstoneEngineers/TrialORE/blob/main/README.md'>" +
            "<hover:show_text:'Go to README'>view the README</hover></click>")
    }

    @Subcommand("history")
    @Description("Get the info of an individual from past trials")
    @CommandCompletion("@usernameCache")
    fun onHistory(player: Player, @Single target: String) {
        var testificate = trialORE.server.onlinePlayers.firstOrNull { it.name == target }?.uniqueId
        if (testificate == null) {
            testificate = trialORE.database.usernameToUuidCache[target]
                ?: throw TrialOreException("Invalid target $target. Please provide an online player or UUID")
        }
        val trials = trialORE.database.getTrials(testificate)
        player.renderMiniMessage("<gray>$target has been in ${trials.size} trials")
        trials.forEachIndexed { index, trial ->
            val trialInfo = trialORE.database.getTrialInfo(trial)
            val state = if (trialInfo.passed) {
                "<green>Passed</green>"
            } else {
                "<red>Failed</red>"
            }
            val startTime = trialInfo.start.toLong()
            val timestamp = getRelativeTimestamp(startTime)
            val trialer = trialORE.database.uuidToUsernameCache[trialInfo.trialer] ?: "Invalid UUID??"
            player.renderMiniMessage("<hover:show_text:'At <gray>${getDate(startTime)}<white>" +
                " by <gray>$trialer<white> (State: ${state})'><gray>Trial ${index+1}, $timestamp</hover>:")
            if (trialInfo.notes.isEmpty()) {
                player.renderMiniMessage("<i>No notes")
            }
            trialInfo.notes.forEach { note ->
                player.renderMessage(note)
            }
        }
    }

    @CommandAlias("trialstart")
    @Subcommand("start")
    @Description("Start a trial")
    @Conditions("notTrialing")
    @CommandCompletion("@players app")
    fun onStart(player: Player, @Single target: String, @Single app: String) {
        val testificate = trialORE.server.onlinePlayers.firstOrNull { it.name == target }
            ?: throw TrialOreException("That individual is not online and cannot be trialed")
        if (trialORE.trialMapping.filter { (_, meta) ->
            meta.first == testificate.uniqueId
        }.isNotEmpty()) {
            throw TrialOreException("That individual is already trialing")
        }
        if (trialORE.getParent(testificate.uniqueId) != trialORE.config.studentGroup) {
            throw TrialOreException("That individual is ineligible for trial due to rank")
        }
        if (player.uniqueId == testificate.uniqueId) {
            throw TrialOreException("You cannot trial yourself")
        }
        if (!app.startsWith("https://discourse.openredstone.org/")) {
            throw TrialOreException("Invalid app: $app")
        }
        player.renderMessage("Starting trial of ${testificate.name}")
        testificate.renderMessage("Starting trial with ${player.name}")
        trialORE.startTrial(player.uniqueId, testificate.uniqueId, app)
    }

    @Subcommand("note")
    @Conditions("trialing")
    @Description("Add a note to an active trial")
    fun onNote(player: Player, note: String) {
        val trialId = trialORE.trialMapping[player.uniqueId]?.second
            ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
        trialORE.database.insertNote(trialId, note.trim())
        player.renderMiniMessage("Saving note <gray>\"$note\"")
    }

    //@Subcommand("time??")

    @Subcommand("finish")
    @Conditions("trialing")
    @Description("Finish a trial")
    inner class Finish : BaseCommand() {

        @CommandAlias("trialpass")
        @Subcommand("pass")
        @Description("Accept this testificate's trial")
        fun onPass(player: Player) {
            val trialMeta = trialORE.trialMapping[player.uniqueId]
                ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
            val trialId = trialMeta.second
            player.renderMessage("Testificate has passed their trial")
            player.renderMessage("You may now communicate this pass with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialId, true)
        }

        @CommandAlias("trialfail")
        @Subcommand("fail")
        @Description("Fail this testificate's trial")
        fun onFail(player: Player) {
            val trialMeta = trialORE.trialMapping[player.uniqueId]
                ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
            val trialId = trialMeta.second
            player.renderMessage("Testificate has failed their trial")
            player.renderMessage("You may now communicate this pass with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialId, false)
        }
    }
}

class TrialOreException : Exception {
    constructor(message: String) : super(message)
}
