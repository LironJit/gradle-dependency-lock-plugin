package nebula.plugin.dependencylock

import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Unroll

class DependencyLockPluginWithCoreVerifierSpec extends AbstractDependencyLockPluginSpec {
    private static final String BASELINE_LOCKFILE_CONTENTS = """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
test.nebula:a:1.1.0
test.nebula:b:1.1.0
""".stripIndent()

    private static final String MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES = """ \
        dependencies {
            compile 'test.nebula:d:1.0.0'
            testCompile 'test.nebula:c' // missing from a BOM, for example
            testRuntime 'test.nebula:e' // missing from a BOM, for example
            testRuntime 'has.missing.transitive:a:1.0.0' // transitive dep not found
            testRuntime 'not.available:a:1.0.0' // has version number, but not found
        }
        """.stripIndent()

    def setup() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:e:1.0.0')
                .addModule('test.nebula:f:1.0.0')
                .addModule(new ModuleBuilder('has.missing.transitive:a:1.0.0').addDependency('transitive.not.available:a:1.0.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def transitiveNotAvailableDep = new File(mavenrepo.getMavenRepoDir(), "transitive/not/available/a")
        transitiveNotAvailableDep.deleteDir() // to create a missing transitive dependency
    }

    @Unroll
    def 'fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'warn when dependency is unresolvable upon update via #lockArg and project requests warnings only via property'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasks(*tasks(lockArg), '-PdependencyLock.unresolvedDependenciesFailTheBuild=false')

        then:
        results.output.contains("Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'fail when dependency is missing from the lock state'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << """ \
        dependencies {
            compile 'test.nebula:d:1.0.0'
        }
        """.stripIndent()

        def results = runTasksAndFail('dependencies')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Resolved dependencies were missing from the lock state')
        results.output.contains('Resolved \'test.nebula:d:1.0.0\' which is not part of the dependency lock state')
    }

    @Unroll
    def 'multiproject: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true))

        then:
        results.output.contains('FAILURE')

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: fail when dependency is missing from the lock state'() {
        given:
        createMultiProjectBaseline()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:e:1.0.0')
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        when:
        new File(projectDir, 'sub1/build.gradle') << """ \
        dependencies {
            compile 'test.nebula:d:1.0.0'
        }
        """.stripIndent()
        new File(projectDir, 'sub2/build.gradle') << """ \
        dependencies {
            compile 'test.nebula:e:1.0.0'
        }
        """.stripIndent()

        def results = runTasksAndFail('dependenciesForAll')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Resolved dependencies were missing from the lock state')
        results.output.contains('Resolved \'test.nebula:d:1.0.0\' which is not part of the dependency lock state')
        results.output.contains('Resolved \'test.nebula:e:1.0.0\' which is not part of the dependency lock state')
    }

    @Unroll
    def 'provide useful error message when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: provide useful error message when dependency is unresolvable upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies('sub1'))

        results.output.contains("""
       1) Failed to resolve 'not.available:a:1.0.0' for project 'sub2'
       2) Failed to resolve 'test.nebula:c' for project 'sub2'
       3) Failed to resolve 'test.nebula:e' for project 'sub2'
       4) Failed to resolve 'transitive.not.available:a:1.0.0' for project 'sub2'""")

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'update lockfiles for resolvable configurations only upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        def secondRunCompileLockfile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        secondRunCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS + "test.nebula:d:1.0.0\n"

        def secondRunTestCompileLockfile = new File(projectDir, '/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert secondRunTestCompileLockfile.exists()
        secondRunTestCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: update lockfiles for resolvable configurations only upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true))

        then:
        def secondRunCompileLockfile = new File(projectDir, 'sub1/gradle/dependency-locks/compileClasspath.lockfile')
        secondRunCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS + "test.nebula:d:1.0.0\n"

        def secondRunTestCompileLockfile = new File(projectDir, 'sub1/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert secondRunTestCompileLockfile.exists()
        secondRunTestCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'scala: fail when dependency is unresolvable upon update via #lockArg (defining dependencies on base configuration "#conf")'() {
        // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context, and extend from `compile` and `implementation`
        // https://github.com/gradle/gradle/blob/master/subprojects/scala/src/main/java/org/gradle/api/plugins/scala/ScalaBasePlugin.java#L143
        given:
        createSingleProjectBaseline('scala', conf)

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        conf             | lockArg
        'compile'        | "write-locks"
        'compile'        | "update-locks"
        'implementation' | "write-locks"
        'implementation' | "update-locks"
    }

    @Unroll
    def 'groovy: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('groovy')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'java-library: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('java-library')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'nebula.kotlin: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('nebula.kotlin')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'nebula.clojure: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('nebula.clojure')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    def createSingleProjectBaseline(String languagePlugin = 'java', String conf = '') {
        if (languagePlugin == 'nebula.kotlin') {
            createKotlinSingleProjectBaseline()
            return
        }
        if (languagePlugin == 'nebula.clojure') {
            createClojureSingleProjectBaseline()
            return
        }
        if (languagePlugin == 'scala') {
            createScalaSingleProjectBaseline(conf)
            return
        }
        if (languagePlugin != 'java') {
            updateSingleProjectFor(languagePlugin)
        }
        writeHelloWorld()
        writeUnitTest()

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }
        def firstRunCompileLockfile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        assert firstRunCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS

        def firstRunTestCompileLockfile = new File(projectDir, '/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert firstRunTestCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS

        if (languagePlugin == 'nebula.kotlin') {
            System.setProperty("ignoreDeprecations", "false")
        }
    }

    def createMultiProjectBaseline() {
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """
            plugins {
                id 'java'
            }
            subprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
            }
            """.stripIndent()

        def subProjectBuildFileContent = """
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
            """.stripIndent()

        addSubproject("sub1", subProjectBuildFileContent)
        addSubproject("sub2", subProjectBuildFileContent)

        writeHelloWorld(new File(projectDir, 'sub1'))
        writeHelloWorld(new File(projectDir, 'sub2'))
        writeUnitTest(new File(projectDir, 'sub1'))
        writeUnitTest(new File(projectDir, 'sub2'))

        runTasks('dependenciesForAll', '--write-locks') // baseline dependency locks

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert sub1ActualLocks.contains(it)
        }
        sub1ActualLocks.each {
            assert expectedLocks.contains(it)
        }

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert sub2ActualLocks.contains(it)
        }
        sub2ActualLocks.each {
            assert expectedLocks.contains(it)
        }

        def firstRunCompileLockfile = new File(projectDir, 'sub1/gradle/dependency-locks/compileClasspath.lockfile')
        assert firstRunCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS

        def firstRunTestCompileLockfile = new File(projectDir, 'sub1/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert firstRunTestCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS
    }

    def createScalaSingleProjectBaseline(String conf) {
        setupScalaProject(conf)

        buildFile << """
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
            """.stripIndent()

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }
        def firstRunCompileLockfile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        assert firstRunCompileLockfile.text ==
                """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
org.scala-lang:scala-library:2.12.7
test.nebula:a:1.1.0
test.nebula:b:1.1.0
"""

        def firstRunTestCompileLockfile = new File(projectDir, '/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert firstRunTestCompileLockfile.text ==
                """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
junit:junit:4.12
org.hamcrest:hamcrest-core:1.3
org.scala-lang.modules:scala-xml_2.12:1.0.6
org.scala-lang:scala-library:2.12.7
org.scala-lang:scala-reflect:2.12.4
org.scalactic:scalactic_2.12:3.0.5
org.scalatest:scalatest_2.12:3.0.5
test.nebula:a:1.1.0
test.nebula:b:1.1.0
"""
    }

    def createKotlinSingleProjectBaseline() {
        buildFile.delete()
        buildFile.createNewFile()

        buildFile << """\
                buildscript {
                    repositories { maven { url "https://plugins.gradle.org/m2/" } }
                    dependencies {
                        classpath "com.netflix.nebula:nebula-kotlin-plugin:latest.release"
                    }
                }
                plugins {
                    id 'nebula.dependency-lock'
                }
                apply plugin: 'nebula.kotlin'
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                    mavenCentral()
                }
                dependencies {
                    compile 'test.nebula:a:1.+'
                    compile 'test.nebula:b:1.+'
                }
                """.stripIndent()
        System.setProperty("ignoreDeprecations", "true")

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }
        def firstRunCompileLockfile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        def lockfileContents = """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
org.jetbrains.kotlin:kotlin-stdlib-common:1.3.50
org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.3.50
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.50
org.jetbrains.kotlin:kotlin-stdlib:1.3.50
org.jetbrains:annotations:13.0
test.nebula:a:1.1.0
test.nebula:b:1.1.0
"""
        assert firstRunCompileLockfile.text == lockfileContents

        def firstRunTestCompileLockfile = new File(projectDir, '/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert firstRunTestCompileLockfile.text == lockfileContents
    }

    def createClojureSingleProjectBaseline() {
        buildFile.delete()
        buildFile.createNewFile()

        buildFile << """\
                buildscript {
                    repositories { maven { url "https://plugins.gradle.org/m2/" } }
                    dependencies {
                        classpath "com.netflix.nebula:nebula-clojure-plugin:latest.release"
                    }
                }
                plugins {
                    id 'nebula.dependency-lock'
                }
                apply plugin: 'nebula.clojure'
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                    mavenCentral()
                }
                dependencies {
                    compile 'org.clojure:clojure:1.8.0'
                    compile 'test.nebula:a:1.+'
                    compile 'test.nebula:b:1.+'
                }
                """.stripIndent()
        System.setProperty("ignoreDeprecations", "true")

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }
        def firstRunCompileLockfile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        def lockfileContents = """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
org.clojure:clojure:1.8.0
test.nebula:a:1.1.0
test.nebula:b:1.1.0
"""
        assert firstRunCompileLockfile.text == lockfileContents

        def firstRunTestCompileLockfile = new File(projectDir, '/gradle/dependency-locks/testCompileClasspath.lockfile')
        assert firstRunTestCompileLockfile.text == lockfileContents
    }

    def updateSingleProjectFor(String languagePlugin) {
        buildFile.delete()
        buildFile.createNewFile()
        if (languagePlugin == 'groovy') {
            buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'groovy'
            }
            
        """.stripIndent()
        } else if (languagePlugin == 'java-library') {
            buildFile << """\
                plugins {
                    id 'nebula.dependency-lock'
                    id 'java-library'
                }
                """.stripIndent()

            writeHelloWorld()
            writeUnitTest()
        }

        buildFile << """\
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()
    }

    def tasks(String lockArg, Boolean isMultiProject = false) {
        def tasks = []
        isMultiProject ? tasks.add('dependenciesForAll') : tasks.add('dependencies')

        tasks.addAll('--rerun-tasks', '--warning-mode', 'all')

        lockArg == 'write-locks'
                ? tasks.add("--$lockArg")
                : tasks.addAll("--$lockArg".toString(), 'test.nebula:d,test.nebula:c,test.nebula:e,has.missing.transitive:a,not.available:a')

        return tasks
    }

    private String failedResolutionDependencies(String subprojectName = '') {
        def project = subprojectName != '' ? subprojectName : projectName
        return """
  1) Failed to resolve 'not.available:a:1.0.0' for project '$project'
  2) Failed to resolve 'test.nebula:c' for project '$project'
  3) Failed to resolve 'test.nebula:e' for project '$project'
  4) Failed to resolve 'transitive.not.available:a:1.0.0' for project '$project'"""
    }
}
