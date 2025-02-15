package wom.expression

import cats.data.Validated._
import cats.effect.IO
import common.validation.ErrorOr.ErrorOr
import wom.expression.IoFunctionSet.IoElement
import wom.types._
import wom.values._

import scala.concurrent.{ExecutionContext, Future}

object FileEvaluation {
  def requiredFile(file: WomFile): FileEvaluation = FileEvaluation(file = file, optional = false, secondary = false)
}

case class FileEvaluation(file: WomFile, optional: Boolean, secondary: Boolean)

trait WomExpression {
  def sourceString: String

  /**
    * Produce a String suitable for caching, i.e. should not include references to memory locations or ephemeral, UUID-containing
    * file paths, and should have all the essentials for determining if two `WomExpression` are conceptually the same.
    */
  def cacheString = sourceString

  def inputs: Set[String]

  def evaluateValue(inputValues: Map[String, WomValue], ioFunctionSet: IoFunctionSet): ErrorOr[WomValue]

  def evaluateType(inputTypes: Map[String, WomType]): ErrorOr[WomType]

  def evaluateFiles(inputValues: Map[String, WomValue], ioFunctionSet: IoFunctionSet, coerceTo: WomType): ErrorOr[Set[FileEvaluation]]

  /** Returns `true` if all file types within the specified `WomType` are optional. If not all the file types are
    * optional, return `false` since the current file evaluation structure doesn't allow for mapping individual
    * output files to their corresponding primitives within a non-primitive `WomType`. */
  protected def areAllFileTypesInWomTypeOptional(womType: WomType): Boolean = {
    def innerAreAllFileTypesInWomTypeOptional(womType: WomType): Boolean = womType match {
      case WomOptionalType(_: WomPrimitiveFileType) => true
      case _: WomPrimitiveFileType => false
      case _: WomPrimitiveType => true // WomPairTypes and WomCompositeTypes may have non-File components here which is fine.
      case WomArrayType(inner) => innerAreAllFileTypesInWomTypeOptional(inner)
      case WomMapType(_, inner) => innerAreAllFileTypesInWomTypeOptional(inner)
      case WomPairType(leftType, rightType) => innerAreAllFileTypesInWomTypeOptional(leftType) && innerAreAllFileTypesInWomTypeOptional(rightType)
      case WomCompositeType(typeMap, _) => typeMap.values.forall(innerAreAllFileTypesInWomTypeOptional)
      case _ => false
    }

    // At the outermost level, primitives are never optional.
    womType match {
      case _: WomPrimitiveType => false
      case _ => innerAreAllFileTypesInWomTypeOptional(womType)
    }
  }
}

/**
  * It looks and acts like an expression, but it's really just a value.
  */
final case class ValueAsAnExpression(value: WomValue) extends WomExpression {
  override def sourceString: String = value.valueString
  override def evaluateValue(inputValues: Map[String, WomValue], ioFunctionSet: IoFunctionSet): ErrorOr[WomValue] = Valid(value)
  override def evaluateType(inputTypes: Map[String, WomType]): ErrorOr[WomType] = Valid(value.womType)
  override def evaluateFiles(inputTypes: Map[String, WomValue], ioFunctionSet: IoFunctionSet, coerceTo: WomType): ErrorOr[Set[FileEvaluation]] = Valid(Set.empty)
  override val inputs: Set[String] = Set.empty
}

/**
  * Functions only requiring path manipulation and NO I/O
  */
trait PathFunctionSet {
  /**
    * Similar to java.nio.Path.resolveSibling with
    * of == a string representation of a java.nio.Path
    */
  def sibling(of: String, other: String): String

  /**
    * Similar to java.nio.Path.isAbsolute
    */
  def isAbsolute(path: String): Boolean

  /**
    * Similar to sibling only if "of" IS an absolute path and "other" IS NOT an absolute path, otherwise return other
    */
  def absoluteSibling(of: String, other: String): String = if (isAbsolute(of) && !isAbsolute(other)) sibling(of, other) else other

  /**
    * If path is relative, prefix it with the _host_ call root.
    */
  def relativeToHostCallRoot(path: String): String

  /**
    * Similar to java.nio.Path.getFileName.
    *
    * Note: Does NOT run DRS resolution so will return the wrong value for DRS files.
    */
  def name(path: String): String

  /**
    * Path to stdout
    */
  def stdout: String

  /**
    * Path to stderr
    */
  def stderr: String
}

object IoFunctionSet {
  /**
    * Simple wrapper class providing information on whether a path is a File or a Directory
    * Avoids repeated calls to isDirectory.
    */
  sealed trait IoElement {
    def path: String
  }
  case class IoFile(path: String) extends IoElement
  case class IoDirectory(path: String) extends IoElement
}

/**
  * Utility functions to perform various I/O and path related operations
  * Because at this time WOM does not assume anything in terms of implementation,
  * all the paths are of type String.
  */
trait IoFunctionSet {
  // Functions that do NOT necessitate network I/O but are only manipulating paths
  def pathFunctions: PathFunctionSet

  /**
    * Get the basename of this path. If a DRS path, resolve to a real URL and get the basename
    * @param path The input path
    * @return The base filename of the object at the (fully resolved) path
    */
  def resolvedFileBasename(path: String): Future[String]

  // Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
  /**
    * Read the content of a file
    * @param path path of the file to read from
    * @param maxBytes maximum number of bytes that can be read
    * @param failOnOverflow if true, the Future will fail if the files has more than maxBytes
    * @return the content of the file as a String
    */
  def readFile(path: String, maxBytes: Option[Int], failOnOverflow: Boolean): Future[String]

  /**
    * Write "content" to the specified "path" location
    */
  def writeFile(path: String, content: String): Future[WomSingleFile]

  /**
    * Creates a temporary directory. This must be in a place accessible to the backend.
    * In a world where then backend is not known at submission time this will not be sufficient.
    */
  def createTemporaryDirectory(name: Option[String]): Future[String]

  /**
    * Copy pathFrom to targetName
    * @return destination as a WomSingleFile
    */
  def copyFile(source: String, destination: String): Future[WomSingleFile]

  /**
    * Glob files and directories using the provided pattern.
    * @return the list of globbed paths
    */
  def glob(pattern: String): Future[Seq[String]]

  /**
    * Recursively list all files (and only files, not directories) under "dirPath"
    * dirPath MUST BE a directory
    * @return The list of all files under "dirPath"
    */
  def listAllFilesUnderDirectory(dirPath: String): Future[Seq[String]]

  /**
    * List entries in a directory non recursively. Includes directories
    */
  def listDirectory(path: String)(visited: Vector[String] = Vector.empty): Future[Iterator[IoElement]]

  /**
    * Return true if path points to a directory, false otherwise
    */
  def isDirectory(path: String): Future[Boolean]

  /**
    * Return the size of the file located at "path"
    */
  def size(path: String): Future[Long]

  /**
    * To map/flatMap over IO results
    */
  implicit def ec: ExecutionContext

  implicit def cs = IO.contextShift(ec)

  /**
    * Returns an IO function set where input specific functions have been turned on. This allows backends such as the sfs
    * backend to use a different set of functions when evaluating inputs.
    * @return an IoFunctionSet
    */
  def makeInputSpecificFunctions(): IoFunctionSet = this
}
