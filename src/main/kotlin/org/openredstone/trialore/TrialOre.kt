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
    val abandonForgiveness: Long = 6000,
    val sendFailedTests: Boolean = true
)

data class TrialMeta(
    val testificate: UUID,
    val trialId: Int
)

data class TestMeta(
    val testificate: UUID,
    val session: TrialOre.TestSession
)

class TrialOre : JavaPlugin(), Listener {
    lateinit var database: Storage
    lateinit var luckPerms: LuckPerms
    lateinit var config: TrialOreConfig
    val trialMapping: MutableMap<UUID, Pair<UUID, Int>> = mutableMapOf()
    val testMapping: MutableMap<UUID, TestSession> = mutableMapOf()
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
            commandConditions.addCondition("notTesting") {
                // Condition "notTesting" will fail  if the person is taking a test
                if (testMapping.containsKey(it.issuer.player.uniqueId)) {
                    throw TrialOreException("You are already taking a Test")
                }
            }
            commandConditions.addCondition("testing") {
                // Condition "notTesting" will fail  if the person is taking a test
                if (!testMapping.containsKey(it.issuer.player.uniqueId)) {
                    throw TrialOreException("You are not taking a Test")
                }
            }
            commandContexts.registerIssuerOnlyContext(TrialMeta::class.java) { context ->
                val meta = trialMapping[context.player.uniqueId]
                    ?: throw TrialOreException("Invalid trial mapping. This is likely a bug")
                TrialMeta(meta.first, meta.second)
            }
            commandContexts.registerIssuerOnlyContext(TestMeta::class.java) { context ->
                val meta = testMapping[context.player.uniqueId]
                    ?: throw TrialOreException("Invalid test mapping. This is likely a bug")
                TestMeta(context.player.uniqueId, meta)
            }
            commandCompletions.registerCompletion("usernameCache") { database.usernameToUuidCache.keys }
            registerCommand(TrialCommand(this@TrialOre, VERSION))
            registerCommand(TestCommand(this@TrialOre))
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
        if (!configFile.exists() || configFile.length() == 0L) {
            configFile.createNewFile()
            val defaultConfig = TrialOreConfig()
            mapper.writeValue(configFile, defaultConfig)
        }

        val loadedConfig: TrialOreConfig = try {
            val config = mapper.readTree(configFile)
            mapper.treeToValue(config, TrialOreConfig::class.java)
        } catch (e: Exception) {
            logger.warning("Failed to load config.yml, using defaults. $e")
            TrialOreConfig()
        }

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
        testMapping.forEach { (testtaker, session) ->
            if (uuid == testtaker) {
                endTest(testtaker, session.startingtime, false, 25)
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
        sendReport(database.getTrialInfo(trialId), database.getTrialCount(testificate))
    }

    fun endTest(testificate: UUID, startingtime: Int, passed: Boolean, wrong: Int): Int {
        val testId = this.database.endTest(testificate, startingtime, passed, wrong)
        this.testMapping.remove(testificate)
        val testInfo = database.getTestInfo(testId)
        if (testInfo?.wrong == 25) { return 0 }
        if ((config.sendFailedTests || passed) && testInfo != null)
        sendTestReport(testInfo, database.getTestCount(testificate))
        return testId
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

    private fun sendReport(trialInfo: TrialInfo, trialCount: Int) {
        val lines = mutableListOf(
            "**Trialer**: ${database.uuidToUsernameCache[trialInfo.trialer]}",
            "**Attempt**: $trialCount",
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

    private fun sendTestReport(testInfo: TestInfo, testCount: Int) {
        val correct = 25 - testInfo.wrong
        val percentage = (correct.toDouble() / 25.toDouble()) * 100
        var rotatingLight = ""
        if (testInfo.end - testInfo.start <= 45 * 1000 ) { rotatingLight = ":rotating_light: :rotating_light: :rotating_light: <45 Seconds!!" }
        val lines = mutableListOf(
            "**Testificate**: ${database.uuidToUsernameCache[testInfo.testificate]}",
            "**Attempt**: $testCount",
            "**Start**: <t:${testInfo.start}:F>",
            "**End**: <t:${testInfo.end}:F>",
            "**Wrong**: ${testInfo.wrong}",
            "**Percentage**: ${"%.2f".format(percentage)}%",
            rotatingLight
        )
        val color = if(testInfo.passed) {
            0x5fff58
        } else {
            0xff5858
        }
        val payload = mapOf(
            "embeds" to listOf(
                mapOf(
                    "title" to database.uuidToUsernameCache[testInfo.testificate],
                    "description" to lines.joinToString("\n"),
                    "color" to color,
                    "fields" to listOf(
                        mapOf(
                            "name" to "State",
                            "value" to testInfo.passed
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

    data class TestSession(
        val startingtime: Int,
        val questions: List<Int>,
        var index: Int = 0,
        var currentAnswer: String = "",
        var wrong: Int = 0,
        val used: MutableMap<Int, MutableSet<String>> = mutableMapOf()
    )

    val testSessions: MutableMap<UUID, TestSession> = mutableMapOf()
    private val rand = Random()

    fun startTest(testificate: UUID) {
        // val testId = this.database.insertTest(testificate)
        val startingtime = now()

        val categories = mutableListOf<Int>().apply {
            repeat(4) { add(1) }
            repeat(4) { add(2) }
            repeat(8) { add(3) }
            repeat(3) { add(4) }
            repeat(3) { add(5) }
            repeat(3) { add(6) }
        }
        categories.shuffle(rand)
        val session = TestSession(startingtime, categories)
        this.testMapping[testificate] = session
        testSessions[testificate] = session

        server.getPlayer(testificate)?.let { player -> sendNextQuestion(player, session)
        }

    }

    fun sendNextQuestion(player: Player, session: TestSession) {
        if (session.index >= session.questions.size) {
            val passed = session.wrong <= 2
            val testId = endTest(player.uniqueId, session.startingtime, passed, session.wrong)
            testSessions.remove(player.uniqueId)
            if (passed) {
                player.renderMiniMessage("<green>Test (${testId}) finished. Wrong: ${session.wrong}</green>")
            } else {
                player.renderMiniMessage("<red> You failed the test. Wrong: ${session.wrong}</red>")
            }
            return
        }

        val cat = session.questions[session.index]
        val (qText, expected) = generateQuestion(cat, session.used)
        session.currentAnswer = expected
        player.renderMiniMessage("<yellow>Question ${session.index + 1}/25:</yellow> $qText")
    }

    fun generateQuestion(category: Int, usedPerCategory: MutableMap<Int, MutableSet<String>>): Pair<String, String> {
        val used = usedPerCategory.getOrPut(category) { mutableSetOf() }
        fun makeKey(vararg parts: Any) = parts.joinToString(":")

        fun randDecimal(): Int {
            val options = (1..14).filterNot { it in listOf(0,1,2,4,8) }
            return options[rand.nextInt(options.size)]
        }

        fun randTwoSC(): Int {
            val options = (-8..7).filter { it != 0 }
            return options[rand.nextInt(options.size)]
        }

        when(category) {
            1 -> {
                var attempts = 0
                while (attempts++ < 200) {
                    val value = randDecimal()
                    val bin = value.toString(2).padStart(4,'0')
                    val key = makeKey("conv-bin->dec", bin)
                    if (key in used) continue
                    used.add(key)
                    return Pair("Convert binary $bin to decimal", value.toString())
                }
                return Pair("Convert binary 0101 to decimal", "5")
            }

            2 -> {
                var attempts = 0
                while (attempts++ < 200) {
                    val value = randDecimal()
                    val bin = value.toString(2).padStart(4,'0')
                    val key = makeKey("conv-dec->bin", value)
                    if (key in used) continue
                    used.add(key)
                    return Pair("Convert decimal $value to 4-bit binary", bin)
                }
                return Pair("Convert decimal 5 to 4-bit binary", "0101")
            }

            3 -> {
                val gates = listOf("AND","NAND","OR","NOR","XOR","XNOR")
                val gateIndex = used.size % gates.size
                val op = if (gateIndex < 4) gates[gateIndex] else gates[rand.nextInt(gates.size)]
                val a = rand.nextInt(1,15)
                var b = rand.nextInt(1,15)
                if (b == a) b = (b % 14) + 1
                val aBin = (a and 0xF).toString(2).padStart(4,'0')
                val bBin = (b and 0xF).toString(2).padStart(4,'0')
                val result = when(op){
                    "AND" -> a and b
                    "NAND" -> (a and b) xor 0xF
                    "OR" -> a or b
                    "NOR" -> (a or b) xor 0xF
                    "XOR" -> a xor b
                    "XNOR" -> (a xor b) xor 0xF
                    else -> 0
                } and 0xF
                val ansBin = result.toString(2).padStart(4,'0')
                used.add(makeKey("gate",op,aBin,bBin))
                return Pair("Apply $op to $aBin and $bBin — give the 4-bit binary result", ansBin)
            }

            4 -> {
                var attempts = 0
                while (attempts++ < 200) {
                    val value = randTwoSC()
                    val twos = (value and 0xF).toString(2).padStart(4,'0')
                    val key = makeKey("to-2sc",value)
                    if (key in used) continue
                    used.add(key)
                    return Pair("Write $value as 4-bit two's complement (2sc) binary", twos)
                }
                return Pair("Write -2 as 4-bit two's complement (2sc) binary", "1110")
            }

            5 -> {
                var attempts = 0
                while (attempts++ < 200) {
                    val x = randTwoSC() and 0xF
                    val signed = if(x and 0x8 !=0) x-16 else x
                    val bin = x.toString(2).padStart(4,'0')
                    val key = makeKey("from-2sc",bin)
                    if(key in used) continue
                    used.add(key)
                    return Pair("What is 4-bit two's complement $bin equal to in decimal?", signed.toString())
                }
                return Pair("What is 4-bit two's complement 1110 equal to in decimal?","-2")
            }

            6 -> {
                var attempts = 0
                while(attempts++ < 200){
                    val v = rand.nextInt(1,9)
                    val neg = (-v) and 0xF
                    val negBin = neg.toString(2).padStart(4,'0')
                    val key = makeKey("neg-2sc",v)
                    if(key in used) continue
                    used.add(key)
                    return Pair("What is the 2's complement (4-bit) representation of -$v ?",negBin)
                }
                return Pair("What is the 2's complement (4-bit) representation of -5 ?","1011")
            }

            else -> return Pair("Invalid category","")
        }
    }
}