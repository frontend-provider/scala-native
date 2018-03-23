package scala.scalanative
package build

import java.nio.file.{Path, Paths}

import nir.Global

/** An object describing how to configure the Scala Native toolchain. */
sealed trait Config {

  /** Path to the nativelib jar. */
  def nativelib: Path

  /** The driver to use for the optimizer. */
  def driver: OptimizerDriver

  /** The reporter that receives messages from the linker. */
  def linkerReporter: LinkerReporter

  /** The reporter that receives messages from the optimizer. */
  def optimizerReporter: OptimizerReporter

  /** Entry point for linking. */
  def entry: String

  /** Sequence of all NIR locations. */
  def paths: Seq[Path]

  /** Directory to emit intermediate compilation results. */
  def workdir: Path

  /** The path to the `clang` executable. */
  def clang: Path

  /** The path to the `clang++` executable. */
  def clangpp: Path

  /** Target triple. */
  def target: String

  /** The options passed to LLVM's linker. */
  def linkingOptions: Seq[String]

  /** The compilation options passed to LLVM. */
  def compileOptions: Seq[String]

  /** The garbage collector to use. */
  def gc: GC

  /** Should stubs be linked? */
  def linkStubs: Boolean

  /** The logger used by the toolchain. */
  def logger: Logger

  /** Create a new config with given path to nativelib. */
  def withNativelib(value: Path): Config

  /** Create a new config with given driver. */
  def withDriver(value: OptimizerDriver): Config

  /** Create a new config with given linker reporter. */
  def withLinkerReporter(value: LinkerReporter): Config

  /** Create a new config with given optimizer reporter. */
  def withOptimizerReporter(value: OptimizerReporter): Config

  /** Create new config with given entry point. */
  def withEntry(value: String): Config

  /** Create a new config with given nir paths. */
  def withPaths(value: Seq[Path]): Config

  /** Create a new config with given directory. */
  def withWorkdir(value: Path): Config

  /** Create a new config with given path to clang. */
  def withClang(value: Path): Config

  /** Create a new config with given path to clang++. */
  def withClangPP(value: Path): Config

  /** Create a new config with given target triple. */
  def withTarget(value: String): Config

  /** Create a new config with given linking options. */
  def withLinkingOptions(value: Seq[String]): Config

  /** Create a new config with given compilation options. */
  def withCompileOptions(value: Seq[String]): Config

  /** Create a new config with given garbage collector. */
  def withGC(value: GC): Config

  /** Create a new config with given behavior for stubs. */
  def withLinkStubs(value: Boolean): Config

  /** Create a new config with the given logger. */
  def withLogger(value: Logger): Config
}

object Config {

  /**
   * The default configuration for the Scala Native toolchain. The path
   * to `clang`, `clangpp` and the target triple will be detected automatically.
   *
   * @param nativelib Path to the nativelib jar.
   * @param paths     Sequence of all NIR locations.
   * @param entry     Entry point for linking.
   * @param workdir   Directory to emit intermediate compilation results.
   * @param logger    The logger used by the toolchain.
   * @return A `Config` that uses the default `Mode`, linking and compiling options and
   *         automatically detects the path to `clang`, `clangpp` and the target triple.
   */
  def default(nativelib: Path,
              paths: Seq[Path],
              entry: String,
              workdir: Path,
              logger: Logger): Config = {
    val clang   = LLVM.discover("clang", LLVM.clangVersions)
    val clangpp = LLVM.discover("clang++", LLVM.clangVersions)
    val target  = LLVM.detectTarget(clang, workdir, logger)
    val mode    = Mode.default

    LLVM.checkThatClangIsRecentEnough(clang)
    LLVM.checkThatClangIsRecentEnough(clangpp)

    empty
      .withNativelib(nativelib)
      .withDriver(OptimizerDriver(mode))
      .withEntry(entry)
      .withPaths(paths)
      .withWorkdir(workdir)
      .withClang(clang)
      .withClangPP(clangpp)
      .withTarget(target)
      .withLinkingOptions(LLVM.defaultLinkingOptions)
      .withCompileOptions(LLVM.defaultCompileOptions)
      .withLogger(logger)
  }

  /**
   * Default empty config object.
   *
   * This is intended to create a new `Config` where none of the values are filled.
   * To get a `Config` with default values, use `Config.default`.
   *
   * @see Config.default
   */
  val empty: Config =
    Impl(
      nativelib = Paths.get(""),
      driver = OptimizerDriver.empty,
      linkerReporter = LinkerReporter.empty,
      optimizerReporter = OptimizerReporter.empty,
      entry = "",
      paths = Seq.empty,
      workdir = Paths.get(""),
      clang = Paths.get(""),
      clangpp = Paths.get(""),
      target = "",
      linkingOptions = Seq.empty,
      compileOptions = Seq.empty,
      gc = GC.default,
      linkStubs = false,
      logger = Logger.default
    )

  private final case class Impl(nativelib: Path,
                                driver: OptimizerDriver,
                                linkerReporter: LinkerReporter,
                                optimizerReporter: OptimizerReporter,
                                entry: String,
                                paths: Seq[Path],
                                workdir: Path,
                                clang: Path,
                                clangpp: Path,
                                target: String,
                                linkingOptions: Seq[String],
                                compileOptions: Seq[String],
                                gc: GC,
                                linkStubs: Boolean,
                                logger: Logger)
      extends Config {
    def withNativelib(value: Path): Config =
      copy(nativelib = value)

    def withDriver(value: OptimizerDriver): Config =
      copy(driver = value)

    def withLinkerReporter(value: LinkerReporter): Config =
      copy(linkerReporter = value)

    def withOptimizerReporter(value: OptimizerReporter): Config =
      copy(optimizerReporter = value)

    def withEntry(value: String): Config =
      copy(entry = value)

    def withPaths(value: Seq[Path]): Config =
      copy(paths = value)

    def withWorkdir(value: Path): Config =
      copy(workdir = value)

    def withClang(value: Path): Config =
      copy(clang = value)

    def withClangPP(value: Path): Config =
      copy(clangpp = value)

    def withTarget(value: String): Config =
      copy(target = value)

    def withLinkingOptions(value: Seq[String]): Config =
      copy(linkingOptions = value)

    def withCompileOptions(value: Seq[String]): Config =
      copy(compileOptions = value)

    def withGC(value: GC): Config =
      copy(gc = value)

    def withLinkStubs(value: Boolean): Config =
      copy(linkStubs = value)

    def withLogger(value: Logger): Config =
      copy(logger = value)
  }
}
