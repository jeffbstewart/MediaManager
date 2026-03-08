package net.stewart.mediamanager

import org.junit.Before
import kotlin.test.*

class CommandLineFlagsTest {

    @Before
    fun resetDefaults() {
        CommandLineFlags.developerMode = false
        CommandLineFlags.port = 8080
        CommandLineFlags.h2ConsolePort = 8082
    }

    @Test
    fun `no args keeps defaults`() {
        CommandLineFlags.parseFlags(emptyArray())
        assertFalse(CommandLineFlags.developerMode)
        assertEquals(8080, CommandLineFlags.port)
        assertEquals(8082, CommandLineFlags.h2ConsolePort)
    }

    @Test
    fun `developer_mode flag sets developerMode true`() {
        CommandLineFlags.parseFlags(arrayOf("--developer_mode"))
        assertTrue(CommandLineFlags.developerMode)
    }

    @Test
    fun `port flag sets port`() {
        CommandLineFlags.parseFlags(arrayOf("--port", "9090"))
        assertEquals(9090, CommandLineFlags.port)
    }

    @Test
    fun `h2_console_port flag sets h2ConsolePort`() {
        CommandLineFlags.parseFlags(arrayOf("--h2_console_port", "9999"))
        assertEquals(9999, CommandLineFlags.h2ConsolePort)
    }

    @Test
    fun `all flags combined`() {
        CommandLineFlags.parseFlags(arrayOf("--developer_mode", "--port", "3000", "--h2_console_port", "3001"))
        assertTrue(CommandLineFlags.developerMode)
        assertEquals(3000, CommandLineFlags.port)
        assertEquals(3001, CommandLineFlags.h2ConsolePort)
    }

    @Test
    fun `port without following value keeps default`() {
        CommandLineFlags.parseFlags(arrayOf("--port"))
        assertEquals(8080, CommandLineFlags.port)
    }

    @Test
    fun `port with non-numeric value keeps default`() {
        CommandLineFlags.parseFlags(arrayOf("--port", "abc"))
        assertEquals(8080, CommandLineFlags.port)
    }
}
