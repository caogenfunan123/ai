package com.her.aimodifier.utils

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shell 命令执行工具。
 *
 * 不使用 su；如需 root 权限，由调用方包装。
 */
object ShellUtil {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    fun exec(command: List<String>, cwd: String? = null): Result {
        val pb = ProcessBuilder(command)
        if (cwd != null) pb.directory(java.io.File(cwd))
        pb.redirectErrorStream(false)
        val process = pb.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val t1 = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { it.forEach { line -> stdout.appendLine(line) } }
        }
        val t2 = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).useLines { it.forEach { line -> stderr.appendLine(line) } }
        }
        t1.start(); t2.start()

        val code = process.waitFor()
        t1.join(); t2.join()
        return Result(code, stdout.toString(), stderr.toString())
    }

    fun exec(vararg command: String, cwd: String? = null): Result =
        exec(command.toList(), cwd)
}
