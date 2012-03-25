/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package actor

import metadata._
import leveldb._

import com.precog.common._
import com.precog.common.util._

import blueeyes.json.Printer
import blueeyes.json.JsonParser
import blueeyes.json.JsonAST._
import blueeyes.json.xschema.{ ValidatedExtraction, Extractor, Decomposer }
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.xschema.Extractor._

import akka.actor.Actor

import scalaz._
import scalaz.effect._
import scalaz.Scalaz._

import com.weiglewilczek.slf4s.Logging

import java.io.File

class MetadataSerializationActor(checkpoints: YggCheckpoints, metadataStorage: MetadataStorage) extends Actor with Logging {
  def receive = {
    case SaveMetadata(metadata, messageClock) => 
      logger.debug("Syncing metadata")
      val errors = safelyUpdateMetadata(metadata, messageClock).unsafePerformIO
      if(!errors.isEmpty) {
        logger.error("Error saving metadata: (%d errors to follow)".format(errors.size))
        errors.zipWithIndex.foreach {
          case (t, i) => logger.error("Metadata save error #%d".format(i+1), t)
        }
      }
      logger.debug("Registering metadata checkpoint: " + messageClock)
      checkpoints.metadataPersisted(messageClock)
  }

  private def safelyUpdateMetadata(metadata: Map[ProjectionDescriptor, ColumnMetadata], clock: VectorClock): IO[List[Throwable]] = {
    import metadataStorage._
    
    val io: List[IO[Validation[Throwable, Unit]]] = metadata.map {
      case (desc, meta) => updateMetadata(desc, MetadataRecord(meta, clock))
    }(collection.breakOut)

    io.sequence[IO, Validation[Throwable, Unit]].map{ _.collect { 
      case Failure(t) => t 
    }}
  }

}

trait MetadataStorage extends FileOps {

  protected def dirMapping: ProjectionDescriptor => IO[File]

  val prevFilename = "projection_metadata.prev"
  val curFilename = "projection_metadata.cur"
  val nextFilename = "projection_metadata.next"

  def currentMetadata(desc: ProjectionDescriptor): IO[Validation[Error, MetadataRecord]] = {
    def extract(json: String): Validation[Error, MetadataRecord] = 
      JsonParser.parse(json).validated[PartialMetadataRecord].map { _.toMetadataRecord(desc) }

    dirMapping(desc) flatMap { dir =>
      val file = new File(dir, curFilename)
      read(file) map { opt =>
        opt.map { extract } getOrElse { Failure(Invalid("Unable to access the specified metadata file: " + file)) }
      }
    }
  }
  
  def updateMetadata(desc: ProjectionDescriptor, metadata: MetadataRecord): IO[Validation[Throwable, Unit]] = 
    dirMapping(desc) flatMap { dir => 
      stageNext(dir, metadata) flatMap { 
        case Success(_) => 
          stagePrev(dir) flatMap {
            case Success(_)     => rotateCurrent(dir)
            case f @ Failure(_) => IO(f)
          }
        case f @ Failure(_) => IO(f)
      }
    }

  def stageNext(dir: File, metadata: MetadataRecord): IO[Validation[Throwable, Unit]] = {
    val json = Printer.pretty(Printer.render(metadata.serialize))
    val next = new File(dir, nextFilename)
    write(next, json)
  }
  
  def stagePrev(dir: File): IO[Validation[Throwable, Unit]] = {
    val src = new File(dir, curFilename)
    val dest = new File(dir, prevFilename)
    if(src.exists) copy(src, dest) else IO{ Success(()) }
  }
  
  def rotateCurrent(dir: File): IO[Validation[Throwable, Unit]] = IO {
    Validation.fromTryCatch {
      val src = new File(dir, nextFilename)
      val dest = new File(dir, curFilename)
      rename(src, dest)
      Success(())
    }
  }

}

class FilesystemMetadataStorage(protected val dirMapping: ProjectionDescriptor => IO[File]) extends MetadataStorage with FilesystemFileOps

object MetadataUtil {
  def repairMetadata(descriptor: ProjectionDescriptor, 
                     projection: LevelDBProjection, 
                     record: MetadataRecord): IO[Validation[Throwable, ColumnMetadata]] = IO {
    Validation.fromTryCatch {
      record.clock.map.foldLeft(record.metadata) {
        case (meta, (pid, sid)) => update(meta, projection, descriptor, pid, sid, Int.MaxValue) 
      }
    }
  }

  private def update(metadata: ColumnMetadata,
                     projection: LevelDBProjection,
                     descriptor: ProjectionDescriptor,
                     pid: Int,
                     firstSID: Int,
                     lastSID: Int): ColumnMetadata = {
    // scan project from (pid, firstSID) to (pid, secondSID)
    // for each value update metadata
    // return metadata
    sys.error("todo")
  }
}

trait FileOps {
  def rename(src: File, dest: File): Unit
  def copy(src: File, dest: File): IO[Validation[Throwable, Unit]]

  def read(src: File): IO[Option[String]]
  def write(dest: File, content: String): IO[Validation[Throwable, Unit]]
}

trait FilesystemFileOps extends FileOps {
  def rename(src: File, dest: File) { src.renameTo(dest) }
  def copy(src: File, dest: File) = IOUtils.copyFile(src, dest) 

  def read(src: File) = IOUtils.readFileToString(src) 
  def write(dest: File, content: String) = IOUtils.writeToFile(content, dest)
}

sealed trait MetadataSerializationAction

case class SaveMetadata(metadata: Map[ProjectionDescriptor, ColumnMetadata], messageClock: VectorClock) extends MetadataSerializationAction

case class MetadataRecord(metadata: ColumnMetadata, clock: VectorClock)

trait MetadataRecordSerialization {

  implicit val MetadataRecordDecomposer: Decomposer[MetadataRecord] = new Decomposer[MetadataRecord] {
    override def decompose(metadata: MetadataRecord): JValue = JObject(List(
      JField("metadata", metadata.metadata.values.flatMap{ _.values }.toList.serialize),
      JField("checkpoint", metadata.clock)
    ))
  }

}

object MetadataRecord extends MetadataRecordSerialization

case class PartialMetadataRecord(metadata: List[MetadataMap], clock: VectorClock) {
  def toMetadataRecord(desc: ProjectionDescriptor): MetadataRecord = {
    val map: Map[ColumnDescriptor, MetadataMap] = (desc.columns zip metadata)(collection.breakOut)
    MetadataRecord(map, clock)   
  } 
}

trait PartialMetadataRecordSerialization {
  private def extractMetadata(v: Validation[Error, List[Set[Metadata]]]): Validation[Error, List[MetadataMap]] = {
    v.map { _.map( sm => Metadata.toTypedMap(sm) ).toList }
  }

  implicit val MetadataRecordExtractor: Extractor[PartialMetadataRecord] = new Extractor[PartialMetadataRecord] with ValidatedExtraction[PartialMetadataRecord] {
    override def validated(obj: JValue): Validation[Error, PartialMetadataRecord] =
      (extractMetadata((obj \ "metadata").validated[List[Set[Metadata]]]) |@|
       (obj \ "checkpoint").validated[VectorClock]).apply(PartialMetadataRecord(_, _))
  }
}

object PartialMetadataRecord extends PartialMetadataRecordSerialization
