package com.pocketshell.app.assistant

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the safety gate migrated from the deleted CommandPlanner keeps the
 * exact forbidden-pattern surface (issue #266, D22).
 */
class CommandSafetyTest {

    @Test
    fun allows_ordinary_commands() {
        assertNull(CommandSafety.reject("git status"))
        assertNull(CommandSafety.reject("ls -la /home/dev"))
        assertNull(CommandSafety.reject("cd /home/dev/proj && npm test"))
        assertNull(CommandSafety.reject("rm file.txt"))
    }

    @Test
    fun blocks_sudo_and_su() {
        assertTrue(CommandSafety.reject("sudo apt update") != null)
        assertTrue(CommandSafety.reject("echo hi; sudo reboot") != null)
        assertTrue(CommandSafety.reject("su - root") != null)
    }

    @Test
    fun blocks_recursive_force_rm() {
        assertTrue(CommandSafety.reject("rm -rf /") != null)
        assertTrue(CommandSafety.reject("rm -fr ~/important") != null)
        assertTrue(CommandSafety.reject("git status && rm -rf build") != null)
    }

    @Test
    fun blocks_destructive_system_commands() {
        assertTrue(CommandSafety.reject("shutdown now") != null)
        assertTrue(CommandSafety.reject("reboot") != null)
        assertTrue(CommandSafety.reject("halt") != null)
        assertTrue(CommandSafety.reject("mkfs.ext4 /dev/sda1") != null)
        assertTrue(CommandSafety.reject("dd if=/dev/zero of=/dev/sda") != null)
        assertTrue(CommandSafety.reject("echo x > /dev/sda") != null)
    }

    @Test
    fun blocks_empty_and_control_chars() {
        assertTrue(CommandSafety.reject("") != null)
        assertTrue(CommandSafety.reject("   ") != null)
        assertTrue(CommandSafety.reject("echo a\nrm -rf /") != null)
    }

    @Test
    fun blocks_overlong_command() {
        assertTrue(CommandSafety.reject("echo " + "a".repeat(600)) != null)
    }
}
