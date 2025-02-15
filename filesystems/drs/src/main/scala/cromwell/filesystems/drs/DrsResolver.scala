package cromwell.filesystems.drs

import cats.data.NonEmptyList
import cats.effect.IO
import cloud.nio.impl.drs.{DrsCloudNioFileSystemProvider, DrsPathResolver, MarthaField}
import common.exception._
import cromwell.core.path.DefaultPathBuilder
import org.apache.commons.lang3.exception.ExceptionUtils
import shapeless.syntax.typeable._


object DrsResolver {
  private val GcsScheme: String = "gs"

  private val GcsProtocolLength: Int = 5 // length of 'gs://'

  private def resolveError[A](pathAsString: String)(throwable: Throwable): IO[A] = {
    IO.raiseError(
      new RuntimeException(
        s"Error while resolving DRS path: $pathAsString. Error: ${ExceptionUtils.getMessage(throwable)}"
      )
    )
  }

  private def getDrsPathResolver(drsPath: DrsPath): IO[DrsPathResolver] = {
    val drsFileSystemProviderOption = drsPath.drsPath.getFileSystem.provider.cast[DrsCloudNioFileSystemProvider]

    val noFileSystemForDrsError = s"Unable to cast file system provider to DrsCloudNioFileSystemProvider for DRS path $drsPath."

    for {
      drsFileSystemProvider <- toIO(drsFileSystemProviderOption, noFileSystemForDrsError)
    } yield drsFileSystemProvider.drsPathResolver
  }

  final case class PartialMarthaResponse(gsUri: Option[String], fileName: Option[String], bondProvider: Option[String])
  private def getGsUriFileNameBondProvider(pathAsString: String,
                                           drsPathResolver: DrsPathResolver
                                          ): IO[PartialMarthaResponse] = {
    val fields = NonEmptyList.of(MarthaField.GsUri, MarthaField.FileName, MarthaField.BondProvider)
    for {
      marthaResponse <- drsPathResolver.resolveDrsThroughMartha(pathAsString, fields)
    } yield PartialMarthaResponse(marthaResponse.gsUri, marthaResponse.fileName, marthaResponse.bondProvider)
  }

  /** Returns the `gsUri` if it ends in the `fileName` and the `bondProvider` is empty. */
  private def getSimpleGsUri(gsUriFileNameAndBondProvider: PartialMarthaResponse): Option[String] = {
    for {
      // Only return gsUri that do not use Bond
      gsUri <- if (gsUriFileNameAndBondProvider.bondProvider.isEmpty) gsUriFileNameAndBondProvider.gsUri else None
      // Only return the gsUri if there is no fileName or if gsUri ends in /fileName
      if gsUriFileNameAndBondProvider.fileName.forall(fileName => gsUri.endsWith(s"/$fileName"))
    } yield gsUri
  }

  /** Returns the `gsUri` if it ends in the `fileName` and the `bondProvider` is empty. */
  def getSimpleGsUri(pathAsString: String,
                     drsPathResolver: DrsPathResolver): IO[Option[String]] = {
    getGsUriFileNameBondProvider(pathAsString, drsPathResolver).map(getSimpleGsUri)
      .handleErrorWith(resolveError(pathAsString))
  }

  /** Returns the `gsUri` if it ends in the `fileName` and the `bondProvider` is empty. */
  def getSimpleGsUri(drsPath: DrsPath): IO[Option[String]] = {
    for {
      drsPathResolver <- getDrsPathResolver(drsPath)
      gsUri <- getSimpleGsUri(drsPath.pathAsString, drsPathResolver)
    } yield gsUri
  }

  def getContainerRelativePath(drsPath: DrsPath): IO[String] = {
    val pathIO = for {
      drsPathResolver <- getDrsPathResolver(drsPath)
      gsUriFileNameAndBondProvider <- getGsUriFileNameBondProvider(drsPath.pathAsString, drsPathResolver)
      /*
      In the DOS/DRS spec file names are safe for file systems but not necessarily the DRS URIs.
      Reuse the regex defined for ContentsObject.name, plus add "/" for directory separators.
      https://ga4gh.github.io/data-repository-service-schemas/preview/release/drs-1.0.0/docs/#_contentsobject
       */
      rootPath = DefaultPathBuilder.get(drsPath.pathWithoutScheme.replaceAll("[^/A-Za-z0-9._-]", "_"))
      fileName <- getFileName(gsUriFileNameAndBondProvider.fileName, gsUriFileNameAndBondProvider.gsUri)
      fullPath = rootPath.resolve(fileName)
      fullPathString = fullPath.pathAsString
    } yield fullPathString

    pathIO.handleErrorWith(resolveError(drsPath.pathAsString))
  }

  def getResolvedBasename(drsPath: DrsPath): IO[String] = {
    for {
      drsPathResolver <- getDrsPathResolver(drsPath)
      gsUriFileNameAndBondProvider <- getGsUriFileNameBondProvider(drsPath.pathAsString, drsPathResolver)
      filename <- getFileName(gsUriFileNameAndBondProvider.fileName, gsUriFileNameAndBondProvider.gsUri)
    } yield filename
  }

  /**
    * Return the file name returned from the martha response or get it from the gsUri
    */
  private def getFileName(fileName: Option[String], gsUri: Option[String]): IO[String] = {
    fileName match {
      case Some(actualFileName) => IO.pure(actualFileName)
      case None =>
        //Currently, Martha only supports resolving DRS paths to GCS paths
        IO
          .fromEither(gsUri.toRight(UrlNotFoundException(GcsScheme)))
          .map(_.substring(GcsProtocolLength))
          .map(DefaultPathBuilder.get(_))
          .map(_.name)
    }
  }
}
