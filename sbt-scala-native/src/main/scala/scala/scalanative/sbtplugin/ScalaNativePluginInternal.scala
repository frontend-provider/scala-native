package scala.scalanative
package sbtplugin

import java.lang.System.{lineSeparator => nl}
import java.io.ByteArrayInputStream
import java.nio.file.Files

import scala.util.Try

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._
import sbt.testing.Framework
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

import scalanative.nir
import scalanative.build
import scalanative.build.{BuildException, LLVM}
import scalanative.io.VirtualDirectory
import scalanative.util.{Scope => ResourceScope}
import scalanative.sbtplugin.Utilities._
import scalanative.sbtplugin.TestUtilities._
import scalanative.sbtplugin.ScalaNativePlugin.autoImport._
import scalanative.sbtplugin.SBTCompat.{Process, _}
import scalanative.sbtplugin.testinterface.ScalaNativeFramework

object ScalaNativePluginInternal {

  val nativeWarnOldJVM =
    taskKey[Unit]("Warn if JVM 7 or older is used.")

  val nativeTarget =
    taskKey[String]("Target triple.")

  val nativeLinkerReporter =
    settingKey[build.LinkerReporter](
      "A reporter that gets notified whenever a linking event happens.")

  val nativeOptimizerReporter =
    settingKey[build.OptimizerReporter](
      "A reporter that gets notified whenever an optimizer event happens.")

  val nativeOptimizerDriver =
    taskKey[build.OptimizerDriver]("Pass manager for the optimizer.")

  val nativeWorkdir =
    taskKey[File]("Working directory for intermediate build files.")

  val nativeConfig =
    taskKey[build.Config]("Aggregate config object that's used for tools.")

  lazy val scalaNativeDependencySettings: Seq[Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      "org.scala-native" %%% "nativelib"      % nativeVersion,
      "org.scala-native" %%% "javalib"        % nativeVersion,
      "org.scala-native" %%% "auxlib"         % nativeVersion,
      "org.scala-native" %%% "scalalib"       % nativeVersion,
      "org.scala-native" %%% "test-interface" % nativeVersion % Test
    ),
    addCompilerPlugin(
      "org.scala-native" % "nscplugin" % nativeVersion cross CrossVersion.full)
  )

  lazy val scalaNativeBaseSettings: Seq[Setting[_]] = Seq(
    crossVersion := ScalaNativeCrossVersion.binary,
    platformDepsCrossVersion := ScalaNativeCrossVersion.binary,
    nativeClang := interceptBuildException {
      val clang = LLVM.discover("clang", LLVM.clangVersions)
      LLVM.checkThatClangIsRecentEnough(clang)
      clang.toFile
    },
    nativeClang in NativeTest := (nativeClang in Test).value,
    nativeClangPP := interceptBuildException {
      val clang = LLVM.discover("clang++", LLVM.clangVersions)
      LLVM.checkThatClangIsRecentEnough(clang)
      clang.toFile
    },
    nativeClangPP in NativeTest := (nativeClangPP in Test).value,
    nativeCompileOptions := LLVM.defaultCompileOptions,
    nativeCompileOptions in NativeTest := (nativeCompileOptions in Test).value,
    nativeLinkingOptions := LLVM.defaultLinkingOptions,
    nativeLinkingOptions in NativeTest := (nativeLinkingOptions in Test).value,
    nativeMode := Option(System.getenv.get("SCALANATIVE_MODE"))
      .getOrElse(build.Mode.default.name),
    nativeMode in NativeTest := (nativeMode in Test).value,
    nativeLinkStubs := false,
    nativeLinkStubs in NativeTest := (nativeLinkStubs in Test).value,
    nativeLinkerReporter := build.LinkerReporter.empty,
    nativeLinkerReporter in NativeTest := (nativeLinkerReporter in Test).value,
    nativeOptimizerReporter := build.OptimizerReporter.empty,
    nativeOptimizerReporter in NativeTest := (nativeOptimizerReporter in Test).value,
    nativeGC := Option(System.getenv.get("SCALANATIVE_GC"))
      .getOrElse(build.GC.default.name),
    nativeGC in NativeTest := (nativeGC in Test).value
  )

  lazy val scalaNativeGlobalSettings: Seq[Setting[_]] = Seq(
    nativeWarnOldJVM := {
      val logger = streams.value.log
      Try(Class.forName("java.util.function.Function")).toOption match {
        case None =>
          logger.warn("Scala Native is only supported on Java 8 or newer.")
        case Some(_) =>
          ()
      }
    }
  )

  lazy val scalaNativeConfigSettings: Seq[Setting[_]] = Seq(
    nativeTarget := interceptBuildException {
      val logger = streams.value.log.toLogger
      val cwd    = nativeWorkdir.value.toPath
      val clang  = nativeClang.value.toPath
      LLVM.detectTarget(clang, cwd, logger)
    },
    artifactPath in nativeLink := {
      crossTarget.value / (moduleName.value + "-out")
    },
    nativeOptimizerDriver := build.OptimizerDriver(
      build.Mode(nativeMode.value)),
    nativeWorkdir := {
      val workdir = crossTarget.value / "native"
      IO.delete(workdir)
      IO.createDirectory(workdir)
      workdir
    },
    nativeConfig := {
      val mainClass = selectMainClass.value.getOrElse(
        throw new MessageOnlyException("No main class detected.")
      )
      val classpath =
        fullClasspath.value.map(_.data.toPath).filter(f => Files.exists(f))

      val nativelibJar =
        classpath.find { p =>
          val path = p.toAbsolutePath.toString
          path.contains("scala-native") && path.contains("nativelib")
        }.get
      val entry   = mainClass.toString + "$"
      val cwd     = nativeWorkdir.value.toPath
      val clang   = nativeClang.value.toPath
      val clangpp = nativeClangPP.value.toPath
      val gc      = build.GC(nativeGC.value)

      build.Config.empty
        .withNativelib(nativelibJar)
        .withDriver(nativeOptimizerDriver.value)
        .withLinkerReporter(nativeLinkerReporter.value)
        .withOptimizerReporter(nativeOptimizerReporter.value)
        .withEntry(entry)
        .withPaths(classpath)
        .withWorkdir(cwd)
        .withClang(clang)
        .withClangPP(clangpp)
        .withTarget(nativeTarget.value)
        .withLinkingOptions(nativeLinkingOptions.value)
        .withGC(gc)
        .withLinkStubs(nativeLinkStubs.value)
    },
    nativeLink := {
      val logger  = streams.value.log.toLogger
      val config  = nativeConfig.value.withLogger(logger)
      val outpath = (artifactPath in nativeLink).value

      interceptBuildException(build.build(config, outpath.toPath))

      outpath
    },
    run := {
      val env    = (envVars in run).value.toSeq
      val logger = streams.value.log
      val binary = nativeLink.value.abs
      val args   = spaceDelimited("<arg>").parsed

      logger.running(binary +: args)
      val exitCode = Process(binary +: args, None, env: _*)
        .run(connectInput = true)
        .exitValue

      val message =
        if (exitCode == 0) None
        else Some("Nonzero exit code: " + exitCode)

      message.foreach(sys.error)
    },
    nativeMissingDependencies := {
      (nativeExternalDependencies.value.toSet --
        nativeAvailableDependencies.value.toSet).toList.sorted
    },
    nativeAvailableDependencies := {
      val fcp = fullClasspath.value
      ResourceScope { implicit scope =>
        val globals = fcp
          .collect { case p if p.data.exists => p.data.toPath }
          .flatMap(p =>
            build.LinkerPath(VirtualDirectory.real(p)).globals.toSeq)

        globals.map(_.show).sorted
      }
    },
    nativeExternalDependencies := {
      val forceCompile = compile.value
      val classDir     = classDirectory.value.toPath

      ResourceScope { implicit scope =>
        val globals = linker.ClassPath(VirtualDirectory.real(classDir)).globals
        val config  = build.Config.empty.withPaths(Seq(classDir))
        val result  = (linker.Linker(config)).link(globals.toSeq)

        result.unresolved.map(_.show).sorted
      }
    }
  )

  lazy val scalaNativeCompileSettings: Seq[Setting[_]] =
    scalaNativeConfigSettings

  lazy val scalaNativeTestSettings: Seq[Setting[_]] =
    scalaNativeConfigSettings ++ Seq(
      test := (test in NativeTest).value,
      testOnly := (testOnly in NativeTest).evaluated,
      testQuick := (testQuick in NativeTest).evaluated
    )

  lazy val NativeTest = config("nativetest").extend(Test).hide

  lazy val scalaNativeNativeTestSettings: Seq[Setting[_]] =
    Defaults.testSettings ++
      scalaNativeConfigSettings ++ Seq(
      classDirectory := (classDirectory in Test).value,
      dependencyClasspath := (dependencyClasspath in Test).value,
      parallelExecution in test := false,
      sourceGenerators += Def.task {
        val frameworks = (loadedTestFrameworks in Test).value.map(_._2).toSeq
        val tests      = (definedTests in Test).value
        val output     = sourceManaged.value / "FrameworksMap.scala"
        IO.write(output, makeTestMain(frameworks, tests))
        Seq(output)
      }.taskValue,
      loadedTestFrameworks := {
        val frameworks = (loadedTestFrameworks in Test).value
        val logger     = streams.value.log
        val testBinary = nativeLink.value
        val envVars    = (Keys.envVars in (Test, test)).value
        (frameworks.zipWithIndex).map {
          case ((tf, f), id) =>
            (tf, new ScalaNativeFramework(f, id, logger, testBinary, envVars))
        }
      },
      definedTests := (definedTests in Test).value
    )

  lazy val scalaNativeProjectSettings: Seq[Setting[_]] =
    scalaNativeDependencySettings ++
      scalaNativeBaseSettings ++
      inConfig(Compile)(scalaNativeCompileSettings) ++
      inConfig(Test)(scalaNativeTestSettings) ++
      inConfig(NativeTest)(scalaNativeNativeTestSettings)

  /** Run `op`, rethrows `BuildException`s as `MessageOnlyException`s. */
  private def interceptBuildException[T](op: => T): T = {
    try op
    catch {
      case ex: BuildException => throw new MessageOnlyException(ex.getMessage)
    }
  }
}
