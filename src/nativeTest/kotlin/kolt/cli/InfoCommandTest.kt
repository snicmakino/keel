package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCommandTest {
    private val withProject = InfoSnapshot(
        koltVersion = "0.12.0",
        koltPath = "/usr/local/bin/kolt",
        koltHomeDisplay = "~/.kolt",
        koltHomeBytes = 142L * 1024 * 1024,
        kotlin = KotlinInfo("2.3.20", "daemon", "~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc"),
        jdk = JdkInfo("21", "~/.kolt/toolchains/jdk/21/bin/java"),
        host = "linux-x86_64",
        project = ProjectInfo("my-app", "0.1.0", "app", "jvm")
    )

    @Test
    fun formatsAllFieldsWhenInsideProject() {
        val lines = formatInfo(withProject).lines()

        assertEquals("kolt        v0.12.0 (/usr/local/bin/kolt)", lines[0])
        assertEquals("kolt home   ~/.kolt (142.0 MB)", lines[1])
        assertEquals("kotlin      2.3.20 (daemon, ~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc)", lines[2])
        assertEquals("jdk         21 (~/.kolt/toolchains/jdk/21/bin/java)", lines[3])
        assertEquals("host        linux-x86_64", lines[4])
        assertEquals("", lines[5])
        assertEquals("project     my-app v0.1.0", lines[6])
        assertEquals("kind        app", lines[7])
        assertEquals("target      jvm", lines[8])
    }

    @Test
    fun omitsProjectSectionWhenOutsideProject() {
        val snap = withProject.copy(kotlin = null, jdk = null, project = null)
        val output = formatInfo(snap)

        assertTrue(output.contains("kolt        v0.12.0"))
        assertTrue(output.contains("host        linux-x86_64"))
        assertFalse(output.contains("kotlin"))
        assertFalse(output.contains("project"))
        assertFalse(output.contains("target"))
    }

    @Test
    fun hidesHomeSizeWhenKoltHomeMissing() {
        val snap = withProject.copy(koltHomeBytes = null)
        val line = formatInfo(snap).lines()[1]
        assertEquals("kolt home   ~/.kolt", line)
    }

    @Test
    fun abbreviateHomePathReplacesHomeWithTilde() {
        assertEquals("~/.kolt", abbreviateHomePath("/home/alice/.kolt", "/home/alice"))
        assertEquals("/etc/kolt", abbreviateHomePath("/etc/kolt", "/home/alice"))
        assertEquals("~", abbreviateHomePath("/home/alice", "/home/alice"))
    }
}
