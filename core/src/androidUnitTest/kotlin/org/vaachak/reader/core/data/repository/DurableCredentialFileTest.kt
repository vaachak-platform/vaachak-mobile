package org.vaachak.reader.core.data.repository

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test

class DurableCredentialFileTest {
    private val first = byteArrayOf(1, 2, 3, 4)
    private val second = byteArrayOf(5, 6, 7, 8)

    private fun fixture(block: (File, File) -> Unit) {
        val dir = Files.createTempDirectory("credential-durability-test-").toFile()
        try { block(dir, File(dir, "synthetic.enc")) } finally { dir.deleteRecursively() }
    }

    @Test fun directoryBarrierFollowsCompleteAtomicReplacement() = fixture { dir, file ->
        var barriers = 0
        val writer = DurableCredentialFile { actual ->
            check(actual == dir)
            check(file.readBytes().contentEquals(if (barriers == 0) first else second))
            check(dir.listFiles()?.none { it.name.endsWith(".tmp") } == true)
            barriers++
        }
        writer.replace(file, first)
        writer.replace(file, second)
        check(barriers == 2)
    }

    @Test fun postRenameFailureIsNotSuccessAndRetryDoesNotRewrite() = fixture { _, file ->
        val failing = DurableCredentialFile { throw IOException("synthetic directory sync failure") }
        check(runCatching { failing.replace(file, first) }.exceptionOrNull() is IOException)
        check(file.readBytes().contentEquals(first))
        check(runCatching { failing.ensureDurable(file) }.exceptionOrNull() is IOException)
        var synced = false
        DurableCredentialFile { synced = true }.ensureDurable(file)
        check(synced && file.readBytes().contentEquals(first))
    }

    @Test fun missingFileCannotBeCreatedByRetryBarrier() = fixture { _, file ->
        var synced = false
        check(runCatching { DurableCredentialFile { synced = true }.ensureDurable(file) }.isFailure)
        check(!file.exists() && !synced)
    }

    @Test fun failedAtomicMovePreservesExistingDestinationAndCleansTemp() = fixture { dir, file ->
        check(file.mkdir())
        val child = File(file, "keep")
        child.writeBytes(first)
        var synced = false
        check(runCatching { DurableCredentialFile { synced = true }.replace(file, second) }.isFailure)
        check(!synced && child.readBytes().contentEquals(first))
        check(dir.listFiles()?.map { it.name } == listOf(file.name))
    }
}
