package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.logger
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.models.KeyCorrectnessProof
import com.luxoft.blockchainlab.hyperledger.indy.models.TailsResponse
import mu.KotlinLogging
import org.junit.Test
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import java.io.File
import java.lang.RuntimeException
import kotlin.test.assertEquals
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.Exception
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class PythonRefAgentConnectionTest {

    class InvitedPartyProcess (
            private val agentUrl: String,
            val proofSchemaId: String = "${Random().nextInt()}:::1",
            val tailsHash: String = "${Random().nextInt(Int.MAX_VALUE)}"
            ) {

        fun start(invitationString: String) {
            val rand = Random().nextInt()
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User-$rand", "pass-$rand").handle { _, ex ->
                    if (ex != null) {
                        println("Error connecting User-$rand to $agentUrl: ${ex.message!!}")
                    } else {
                        acceptInvite(invitationString).subscribe ({ master ->
                            logger.info { "!!!Client User-$rand connected to ${master.partyDID()}" }
                            val tails = master.requestTails(tailsHash).toBlocking().value().tails[tailsHash]
                            if (tails?.toString(Charsets.UTF_8) != tailsHash)
                                println("Tails file content doesn't match!!! hash $tailsHash, received $tails")
                            else {
                                val offer = CredentialOffer(proofSchemaId, ":::1", KeyCorrectnessProof("", "", emptyList()), "")
                                master.sendCredentialOffer(offer)
                                println("Client User-$rand completed successfully")
                            }
                            disconnect()
                        }, {
                            println("!!!Error accepting invite (User-$rand): $it")
                            it.printStackTrace()
                            disconnect()
                        })
                    }
                }
            }
        }
    }

    class MasterProcess (
            private val agentUrl: String,
            private val invitedPartyAgents: List<String>) {

        var testOk = false
        fun start() {
            val rand = Random().nextInt()
            val tailsDir = File("tails").apply { deleteOnExit() }
            if (!tailsDir.exists())
                tailsDir.mkdirs()
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User$rand", "pass$rand", timeoutMs = 120000).toBlocking().value()
                val invitedPartiesCompleted = mutableListOf<Observable<Boolean>>()
                invitedPartyAgents.forEach { agentUrl ->
                    val party = InvitedPartyProcess(agentUrl)
                    Paths.get("tails", party.tailsHash).toFile().apply { deleteOnExit() }
                        .writeText(party.tailsHash, Charsets.UTF_8)
                    invitedPartiesCompleted.add(Observable.create { observer ->
                        generateInvite().subscribe ({invitation ->
                            waitForInvitedParty(invitation).subscribe ({ invitedParty ->
                                logger.info { "!!!Remote user ${invitedParty.partyDID()} connected" }
                                invitedParty.handleTailsRequestsWith {
                                    TailsHelper.DefaultReader(tailsDir.absolutePath).read(it)
                                }
                                invitedParty.receiveCredentialOffer().timeout(120, TimeUnit.SECONDS).subscribe ({ credOffer ->
                                    assertEquals(credOffer?.schemaIdRaw, party.proofSchemaId)
                                    observer.onNext(true)
                                    observer.onCompleted()
                                },{
                                    println("Error while waiting for invited party: $it")
                                    it.printStackTrace()
                                    observer.onNext(false)
                                    observer.onCompleted()
                                })
                            }, {
                                observer.onNext(false)
                                println("Error while waiting for invited party: $it")
                                observer.onCompleted()
                            })
                            party.start(invitation)
                        }, {
                            println("Error generating an invitation code: $it")
                        })
                    })
                }

                val completed = Single.create<Boolean> { completedObserver ->
                    Observable.from(invitedPartiesCompleted)
                        .flatMap { it.observeOn(Schedulers.computation()) }
                        .toList().subscribe({
                            results ->
                            testOk = results.all { result -> result == true }
                            if (!testOk)
                                println("Not all invited parties have completed successfully")
                            completedObserver.onSuccess(true)
                        }, {
                            completedObserver.onError(AgentConnectionException("Some of the invited parties threw an error: $it"))
                        })
                }
                completed.toBlocking().value()
                disconnect()
            }
        }
    }

    private val invitedPartyAgents = listOf(
            "ws://127.0.0.1:8094/ws",
            "ws://127.0.0.1:8096/ws",
            "ws://127.0.0.1:8097/ws",
            "ws://127.0.0.1:8098/ws",
            "ws://127.0.0.1:8099/ws"
            )
    private val masterAgent = "ws://127.0.0.1:8092/ws"

    @Test
    fun `externalTest`() = repeat(10) {
        val master = MasterProcess(masterAgent, invitedPartyAgents).apply { start() }
        if (!master.testOk)
            throw AgentConnectionException("Master process didn't complete Ok")
    }

    class Client (private val agentUrl: String) {
        private lateinit var agentConnection : PythonRefAgentConnection
        fun connect(invitationString: String, timeoutMs: Long = 10000) : IndyPartyConnection {
            val rand = Random().nextInt()
            agentConnection = PythonRefAgentConnection()
            agentConnection.connect(agentUrl, "User$rand", "pass$rand", timeoutMs).toBlocking().value()
            return agentConnection.acceptInvite(invitationString).toBlocking().value()
        }
        fun disconnect() {
            agentConnection.disconnect()
        }
    }
    class Server (private val agentUrl: String, private val tailsHash: String) {
        private lateinit var agentConnection: PythonRefAgentConnection
        private var i = AtomicInteger(0)
        fun getInvite() : String {
            val rand = Random().nextInt()
            agentConnection = PythonRefAgentConnection()
            agentConnection.connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
            val invitationString = agentConnection.generateInvite().toBlocking().value()
            agentConnection.waitForInvitedParty(invitationString).subscribe { invitedParty ->
                val partyDid = invitedParty.partyDID()
                println("Server: client $partyDid connected")
                invitedParty.handleTailsRequestsWith {
                    println("Server received Tails request ${i.incrementAndGet()}")
                    TailsResponse(tailsHash, mapOf(tailsHash to tailsHash.toByteArray()))
                }
            }
            return invitationString
        }
        fun disconnect() {
            agentConnection.disconnect()
        }
    }
    class ExtraClient (val agentUrl: String) {

        private lateinit var agentConnection: PythonRefAgentConnection
        val username = "User-${Random().nextInt().absoluteValue}"
        fun connect(timeoutMs: Long = 10000) : Single<Unit>  {
            val rand = Random().nextInt()
            agentConnection = PythonRefAgentConnection()
            return agentConnection.connect(agentUrl, username, "pass$rand", timeoutMs)
        }
        fun disconnect() {
            agentConnection.disconnect()
        }
    }

    @Test
    fun `client reconnects to server when the connection is interrupted `() {
        val tailsHash = "${Random().nextInt(Int.MAX_VALUE)}"
        val server = Server(masterAgent, tailsHash)
        val invitationString = server.getInvite()
        val client = Client(invitedPartyAgents[0])
        val clientConnection = client.connect(invitationString)
        println("Client connected the agent. Local DID is ${clientConnection.myDID()}.")
        repeat(5) {
            val tails = clientConnection.requestTails(tailsHash).toBlocking().value()
            println("Tails received: ${tails.tails[tailsHash]?.toString()}")
        }
        val extraClient = ExtraClient(invitedPartyAgents[0]).apply {
            connect().toBlocking().value()
        }
        val tails = clientConnection.requestTails(tailsHash).toBlocking().value()
        println("Latest tails: ${tails.tails[tailsHash]?.toString()}")
        client.disconnect()
        extraClient.disconnect()
        server.disconnect()
    }

    @Test
    fun `client fails due to connection timeout (non-existent server)`() {
        var testOk = false
        val client = ExtraClient("ws://8.8.8.127:8093/ws").apply {
            connect(2000).subscribe({}, {
                if (it is TimeoutException)
                    testOk = true
            })
        }
        Thread.sleep(3000)
        client.disconnect()
        if (!testOk) throw RuntimeException("Test failed")
    }

    @Test
    fun `client fails due to refused connection (closed port)`() {
        var testOk = false
        val client = ExtraClient("ws://127.0.0.1:8093/ws").apply {
            connect().subscribe({}, {
                if(it is AgentConnectionException)
                    testOk = true
            })
        }
        Thread.sleep(1000)
        client.disconnect()
        if (!testOk) throw RuntimeException("Test failed")
    }

    class ProxyConnection(private val localPort: Int, private val remotePort: Int) {
        companion object {
            val logger = KotlinLogging.logger {}
        }
        class Proxy (private val input: Socket, private val out: Socket) : Runnable {
            override fun run() {
                logger.info { "New proxy: ${input.inetAddress.hostName}:${input.port} --> ${out.inetAddress.hostName}:${out.port}" }
                val inputStream = input.getInputStream()
                val outputStream = out.getOutputStream()
                var readBytes = 0
                val data = ByteArray(4096)
                while (readBytes != -1 && !Thread.currentThread().isInterrupted) {
                    try {
                        readBytes = inputStream.read(data)
                        if (readBytes > 0)
                            outputStream.write(data, 0, readBytes)
                    } catch (e: Exception) {
//                        logger.error { "Exception in proxy: ${input.inetAddress.hostName}:${input.port} --> ${out.inetAddress.hostName}:${out.port}" }
                        break
                    }
                }
                var bytesRemaining = inputStream.available()
                while (bytesRemaining > 0 && readBytes > 0) {
                    readBytes = inputStream.read(data, 0, bytesRemaining % 4096)
                    outputStream.write(data, 0, readBytes)
                    bytesRemaining -= readBytes
                }
                if (Thread.currentThread().isInterrupted)
                    logger.error { "!!!Proxy thread ${input.inetAddress.hostName}:${input.port} --> ${out.inetAddress.hostName}:${out.port} interrupted!!!" }
                out.shutdownOutput()
            }
        }
        class Conn(private val connection: Socket, private val ip: String, private val port: Int) : Runnable {
            override fun run() {
                logger.info { "New proxy destination: $ip:$port" }
                val serverConnection = Socket(ip, port)
                logger.info { "Creating proxy ${connection.inetAddress.hostName}:${connection.port} <-> ${serverConnection.inetAddress.hostName}:${serverConnection.port}" }
                val forwardProxy = thread { Proxy(connection, serverConnection).run() }
                val reverseProxy = thread { Proxy(serverConnection, connection).run() }
                try {
                    forwardProxy.join()
                    reverseProxy.join()
                } catch (e: Throwable) {
                    logger.error { "!!!Proxy connection interrupted!!!" }
                    forwardProxy.interrupt()
                    reverseProxy.interrupt()
                } finally {
                    logger.info { "Proxy connection to $ip:$port terminated." }
                }
            }
        }
        private lateinit var serverSocket: ServerSocket
        private lateinit var socket: Socket
        private lateinit var proxyThread: Thread
        private lateinit var listener: Thread
        private lateinit var proxyConnection: Conn
        fun connect() {
            serverSocket = ServerSocket(localPort)
            listener = thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        socket = serverSocket.accept()
                        proxyConnection = Conn(socket, "127.0.0.1", remotePort)
                        proxyThread = Thread(proxyConnection).apply { start() }
                    } catch (e: SocketException) {}
                }
                logger.error { "!!!Listener interrupted!!!" }
            }
            Thread.sleep(100)
        }
        fun disconnect() {
            listener.interrupt()
            serverSocket.close()
//            if (!socket.isClosed)
//                socket.shutdownOutput()
//            val serverConnectionSocket = proxyConnection.javaClass.getDeclaredField("serverConnection").run {
//                isAccessible = true
//                get(proxyConnection)
//            } as Socket
//            if (!serverConnectionSocket.isClosed)
//                serverConnectionSocket.shutdownInput()
//            Thread.sleep(100)
            proxyThread.interrupt()
            proxyThread.join()
        }
    }

    /**
     * Some of the messages are consumed by a different client, connected in the middle of the message exchange
     */
    @Test
    fun `the connection is lost in the middle of asynchronous message exchange, some messages are lost`() {
        val tailsHash = "${Random().nextInt(Int.MAX_VALUE)}"
        val invitationString = Server(masterAgent, tailsHash).getInvite()
        val proxyConnection = ProxyConnection(8085, 8094).apply { connect(); Thread.sleep(200) }

        val client = Client("ws://127.0.0.1:8085/ws")
        val clientConnection = client.connect(invitationString)
        println("Client connected the agent. Local DID is ${clientConnection.myDID()}.")
        val i = AtomicInteger(200)
        repeat(200) {
            /**
             * asynchronously request 100 messages
             */
            clientConnection.requestTails(tailsHash)
                    .subscribeOn(Schedulers.newThread())
                    .subscribe({ tails ->
                /**
                 * atomically decrement the counter of received message upon receipt
                 */
                println("Tails received: ${tails.tails[tailsHash]?.toString()}, ${i.decrementAndGet()} messages left")
            }, { e ->
                println("!!!Error $e\n- while receiving tails on iteration $it")
            })
        }
        /**
         * break the clientConnection in the middle of the exchange
         */
        proxyConnection.disconnect()
        Thread.sleep(500)
        proxyConnection.connect()
        Thread.sleep(7000)

        client.disconnect()
        /**
         * None of the messages should have been lost
         */
        assert(i.get() == 0)
    }

    class RandomFailuresProcess(private val proxiesList: List<Pair<Int, Int>>) {
        private var proxyConnections: MutableList<ProxyConnection> = mutableListOf()
        private var randomFailures: Thread? = null
        fun start() {
            proxiesList.forEach { pair ->
                val (input, output) = pair
                proxyConnections.add(ProxyConnection(input, output).apply { connect() })
            }
            randomFailures = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(5000 + Random().nextLong().absoluteValue % 5000)
                        randomReset()
                    }
                } catch (e: InterruptedException) {
                }
            }.apply { start() }
        }
        private fun randomReset() {
//            proxyConnections[Random().nextInt().absoluteValue % proxyConnections.size].apply {
            proxyConnections[0].apply {
                disconnect()
                Thread.sleep(500)
                connect()
            }
        }
        fun stop() {
            if (randomFailures?.isAlive == true)
                    randomFailures?.interrupt()
            proxyConnections.forEach {
                it.disconnect()
            }
            proxyConnections.removeAll { true }
        }
    }

    private val invitedPartyAgentsProxies = listOf(
            "ws://127.0.0.1:8084/ws",
            "ws://127.0.0.1:8086/ws",
            "ws://127.0.0.1:8087/ws",
            "ws://127.0.0.1:8088/ws",
            "ws://127.0.0.1:8089/ws"
    )
    private val proxiesConfig = listOf(
            Pair(8084, 8093),
            Pair(8086, 8096),
            Pair(8087, 8097),
            Pair(8088, 8098),
            Pair(8089, 8099)
    )
    @Test
    fun `main load test with randomized proxy connection failures`() {
        val proxies = RandomFailuresProcess(proxiesConfig).apply { start() }
        repeat(200) {
            val master = MasterProcess(masterAgent, invitedPartyAgentsProxies).apply { start() }
            if (!master.testOk)
                throw AgentConnectionException("Master process didn't complete Ok")
        }
        proxies.stop()
    }

}
