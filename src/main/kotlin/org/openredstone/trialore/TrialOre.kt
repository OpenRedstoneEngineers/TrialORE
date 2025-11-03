package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.CommandIssuer
import co.aikar.commands.PaperCommandManager
import co.aikar.commands.RegisteredCommand
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import java.util.logging.Level

val VERSION = "1.1"

const val baseMessage = "<dark_gray>[<gray>TrialORE<dark_gray>]<white> <message>"

fun Player.renderMessage(value: Component) = this.sendMessage(
    MiniMessage.miniMessage().deserialize(
        baseMessage,
        Placeholder.component("message", value)
    )
)
fun Player.renderMessage(value: String) = renderMessage(Component.text(value))
fun Player.renderMiniMessage(value: String) = renderMessage(MiniMessage.miniMessage().deserialize(value))

internal fun <T> Optional<T>.toNullable(): T? = this.orElse(null)

data class TrialOreConfig(
    val studentGroup: String = "student",
    val testificateGroup: String = "testificate",
    val builderGroup: String = "builder",
    val webhook: String = "webhook",
    val abandonForgiveness: Long = 6000
)

data class TrialMeta(
    val testificate: UUID,
    val trialId: Int
)

class TrialOre : JavaPlugin(), Listener {
    lateinit var database: Storage
    lateinit var luckPerms: LuckPerms
    lateinit var config: TrialOreConfig
    val trialMapping: MutableMap<UUID, Pair<UUID, Int>> = mutableMapOf()
    private val mapper = ObjectMapper(YAMLFactory())
    override fun onEnable() {
        loadConfig()
        database = Storage(this.dataFolder.resolve("trials.db").toString())
        luckPerms = LuckPermsProvider.get()
        config = loadConfig()
        server.pluginManager.registerEvents(this, this)
        PaperCommandManager(this).apply {
            commandConditions.addCondition("notTrialing") {
                // Condition "notTrialing" will fail if the person is trialing
                if (trialMapping.containsKey(it.issuer.player.uniqueId)) {
                    throw TrialOreException("You are already in the act of trialing")
                }
            }
            commandConditions.addCondition("trialing") {
                // Condition "trialing" will fail if the person is not trialing
                if (!trialMapping.containsKey(it.issuer.player.uniqueId)) {
                    throw TrialOreException("You are not trialing anyone")
                }
            }
            commandContexts.registerIssuerOnlyContext(TrialMeta::class.java) { context ->
                val meta = trialMapping[context.player.uniqueId]
                    ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
                TrialMeta(meta.first, meta.second)
            }
            commandCompletions.registerCompletion("usernameCache") { database.usernameToUuidCache.keys }
            registerCommand(TrialCommand(this@TrialOre, VERSION))
            setDefaultExceptionHandler(::handleCommandException, false)
        }
    }

    override fun onDisable() {
        trialMapping.forEach { (trialer, meta) ->
            val testificate = meta.first
            val trialId = meta.second
            endTrial(testificate, trialId, false,
                "This trial was automatically ended as the server went offline")
        }
    }

    private fun loadConfig(): TrialOreConfig {
        if (!dataFolder.exists()) {
            logger.info("No resource directory found, creating directory")
            dataFolder.mkdir()
        }
        val configFile = File(dataFolder, "config.yml")
        // does not overwrite or throw
        configFile.createNewFile()
        val config = mapper.readTree(configFile)
        val loadedConfig = mapper.treeToValue(config, TrialOreConfig::class.java)
        logger.info("Loaded config.yml")
        return loadedConfig
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        database.ensureCachedUsername(event.player.uniqueId, event.player.name)
        trialMapping.forEach { (trialer, meta) ->
            val testificate = meta.first
            if ( testificate == event.player.uniqueId ) {
                setLpParent(testificate, config.testificateGroup)
            }
        }
    }

    @EventHandler
    fun onLeave(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        trialMapping.forEach { (trialer, meta) ->
            val testificate = meta.first
            val trialId = meta.second
            if (uuid == testificate) {
                server.onlinePlayers.firstOrNull { it.uniqueId == trialer} ?.renderMessage(
                    "The testificate has left. They have 5 minutes to rejoin before this trial is " +
                        "automatically invalidated"
                )
                setLpParent(testificate, config.studentGroup)
                this.server.scheduler.runTaskLater(this, Runnable {
                    if (this.server.onlinePlayers.map { it.uniqueId }.contains(testificate)) {
                        // Testificate has reconnected, don't end
                        return@Runnable
                    }
                    // END TRIAL!!!
                    endTrial(
                        trialer, trialId, false,
                        "The trial was automatically ended due to the trialer or testificate leaving"
                    )
                    server.onlinePlayers.firstOrNull { it.uniqueId == trialer }?.renderMessage(
                        "The trial was automatically failed as the testificate has left for longer than 5 minutes"
                    )
                }, config.abandonForgiveness)
            }
            if (uuid == trialer) {
                server.onlinePlayers.firstOrNull { it.uniqueId == testificate }?.renderMessage(
                    "The trialer has left. They have 5 minutes to rejoin before this trial is " +
                        "automatically invalidated"
                )
                this.server.scheduler.runTaskLater(this, Runnable {
                    if (this.server.onlinePlayers.map { it.uniqueId }.contains(trialer)) {
                        // Trialer has reconnected, don't end
                        return@Runnable
                    }
                    // END TRIAL!!!
                    endTrial(
                        trialer, trialId, false,
                        "The trial was automatically ended due to the trialer or testificate leaving"
                    )
                    server.onlinePlayers.firstOrNull { it.uniqueId == testificate }?.renderMessage(
                        "The trial was automatically failed as the trialer has left for longer than 5 minutes"
                    )
                }, config.abandonForgiveness)
            }
        }
    }

    fun startTrial(trialer: UUID, testificate: UUID, app: String) {
        val trialId = this.database.insertTrial(trialer, testificate, app)
        this.trialMapping[trialer] = Pair(testificate, trialId)
        setLpParent(testificate, config.testificateGroup)
    }

    fun endTrial(trialer: UUID, trialId: Int, passed: Boolean, finalNote: String? = null) {
        if (finalNote != null) {
            this.database.insertNote(trialId, finalNote)
        }
        this.database.endTrial(trialId, passed)
        val testificate = this.trialMapping[trialer]?.first
            ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
        this.trialMapping.remove(trialer)
        if (passed) {
            setLpParent(testificate, config.builderGroup)
        } else {
            setLpParent(testificate, config.studentGroup)
        }
        sendReport(database.getTrialInfo(trialId, database.getTrialCount(testificate))) // jank
    }

    fun getParent(uuid: UUID): String? = luckPerms.userManager.getUser(uuid)?.primaryGroup

    private fun setLpParent(uuid: UUID, parent: String) {
        luckPerms.userManager.getUser(uuid)?.let { user ->
            val oldNode = InheritanceNode.builder(user.primaryGroup).value(true).build()
            user.data().remove(oldNode)
            val newNode = InheritanceNode.builder(parent).value(true).build()
            user.data().add(newNode)
            user.setPrimaryGroup(parent)
            luckPerms.userManager.saveUser(user).thenRun {
                luckPerms.messagingService.toNullable()?.pushUserUpdate(user)
            }
        }
    }

    private fun sendReport(trialInfo: TrialInfo) {
        val lines = mutableListOf(
            "**Trialer**: ${database.uuidToUsernameCache[trialInfo.trialer]}",
            "**Attempt**: ${trialInfo.attempt}",
            "**Start**: <t:${trialInfo.start}:F>",
            "**End**: <t:${trialInfo.end}:F>",
            "**Notes**:"
        )
        trialInfo.notes.forEach { note ->
            lines.add("* $note")
        }
        val result = if (trialInfo.passed) {
            "*Passed*"
        } else {
            "*Failed*"
        }
        val color = if(trialInfo.passed) {
            0x5fff58
        } else {
            0xff5858
        }
        val payload = mapOf(
            "embeds" to listOf(
                mapOf(
                    "title" to database.uuidToUsernameCache[trialInfo.testificate],
                    "description" to lines.joinToString("\n"),
                    "url" to trialInfo.app,
                    "color" to color,
                    "fields" to listOf(
                        mapOf(
                            "name" to "State",
                            "value" to result
                        )
                    )
                )
            )
        )
        khttp.post(config.webhook, json = payload)
    }

    private fun handleCommandException(
        command: BaseCommand,
        registeredCommand: RegisteredCommand<*>,
        sender: CommandIssuer,
        args: List<String>,
        throwable: Throwable
    ): Boolean {
        val exception = throwable as? TrialOreException ?: run {
            logger.log(Level.SEVERE, "Error while executing command", throwable)
            return false
        }
        val message = exception.message ?: "Something went wrong!"
        val player = server.getPlayer(sender.uniqueId)!!
        player.renderMiniMessage("<red>$message</red>")
        return true
    }
}