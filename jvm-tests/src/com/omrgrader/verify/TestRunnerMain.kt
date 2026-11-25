package com.omrgrader.verify

fun main() {
    registerCoreTests()
    registerDomainTests()
    registerExportTests()
    registerArchiveTests()
    registerOmrSuiteTests()
    val code = TestRunner.run()
    if (code != 0) kotlin.system.exitProcess(code)
}
