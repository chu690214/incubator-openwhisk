/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database.memory

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import pureconfig.loadConfigOrThrow
import spray.json.{DefaultJsonProtocol, DeserializationException, JsObject, JsString, RootJsonFormat}
import whisk.common.{Logging, LoggingMarkers, TransactionId}
import whisk.core.ConfigKeys
import whisk.core.database.StoreUtils._
import whisk.core.database._
import whisk.core.entity.Attachments.Attached
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.Messages

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object MemoryArtifactStoreProvider extends ArtifactStoreProvider {
  override def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {
    makeArtifactStore(MemoryAttachmentStoreProvider.makeStore())
  }

  def makeArtifactStore[D <: DocumentSerializer: ClassTag](attachmentStore: AttachmentStore)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {

    val classTag = implicitly[ClassTag[D]]
    val (dbName, handler, viewMapper) = handlerAndMapper(classTag)
    val inliningConfig = loadConfigOrThrow[InliningConfig](ConfigKeys.db)
    new MemoryArtifactStore(dbName, handler, viewMapper, inliningConfig, attachmentStore)
  }

  private def handlerAndMapper[D](entityType: ClassTag[D])(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): (String, DocumentHandler, MemoryViewMapper) = {
    entityType.runtimeClass match {
      case x if x == classOf[WhiskEntity] =>
        ("whisks", WhisksHandler, WhisksViewMapper)
      case x if x == classOf[WhiskActivation] =>
        ("activations", ActivationHandler, ActivationViewMapper)
      case x if x == classOf[WhiskAuth] =>
        ("subjects", SubjectHandler, SubjectViewMapper)
    }
  }
}

/**
 * In-memory ArtifactStore implementation to enable test setups without requiring a running CouchDB instance
 * It also serves as a canonical example of how an ArtifactStore can implemented with all the support for CRUD
 * operations and Queries etc
 */
class MemoryArtifactStore[DocumentAbstraction <: DocumentSerializer](dbName: String,
                                                                     documentHandler: DocumentHandler,
                                                                     viewMapper: MemoryViewMapper,
                                                                     val inliningConfig: InliningConfig,
                                                                     val attachmentStore: AttachmentStore)(
  implicit system: ActorSystem,
  val logging: Logging,
  jsonFormat: RootJsonFormat[DocumentAbstraction],
  val materializer: ActorMaterializer,
  docReader: DocumentReader)
    extends ArtifactStore[DocumentAbstraction]
    with DefaultJsonProtocol
    with DocumentProvider
    with AttachmentSupport[DocumentAbstraction] {

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  private val artifacts = new TrieMap[String, Artifact]

  private val _id = "_id"
  private val _rev = "_rev"
  val attachmentScheme: String = attachmentStore.scheme

  override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
    val asJson = d.toDocumentRecord

    val id = asJson.fields(_id).convertTo[String].trim
    require(!id.isEmpty, "document id must be defined")

    val rev: Int = getRevision(asJson)
    val docinfoStr = s"id: $id, rev: $rev"
    val start = transid.started(this, LoggingMarkers.DATABASE_SAVE, s"[PUT] '$dbName' saving document: '$docinfoStr'")

    val existing = Artifact(id, rev, asJson)
    val updated = existing.incrementRev()
    val t = Try[DocInfo] {
      if (rev == 0) {
        artifacts.putIfAbsent(id, updated) match {
          case Some(_) => throw DocumentConflictException("conflict on 'put'")
          case None    => updated.docInfo
        }
      } else if (artifacts.replace(id, existing, updated)) {
        updated.docInfo
      } else {
        throw DocumentConflictException("conflict on 'put'")
      }
    }

    val f = Future.fromTry(t)

    f.onFailure({
      case _: DocumentConflictException =>
        transid.finished(this, start, s"[PUT] '$dbName', document: '$docinfoStr'; conflict.")
    })

    f.onSuccess({
      case _ => transid.finished(this, start, s"[PUT] '$dbName' completed document: '$docinfoStr'")
    })

    reportFailure(f, start, failure => s"[PUT] '$dbName' internal error, failure: '${failure.getMessage}'")
  }

  override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    checkDocHasRevision(doc)

    val start = transid.started(this, LoggingMarkers.DATABASE_DELETE, s"[DEL] '$dbName' deleting document: '$doc'")
    val t = Try[Boolean] {
      if (artifacts.remove(doc.id.id, Artifact(doc))) {
        transid.finished(this, start, s"[DEL] '$dbName' completed document: '$doc'")
        true
      } else if (artifacts.contains(doc.id.id)) {
        //Indicates that document exist but revision does not match
        transid.finished(this, start, s"[DEL] '$dbName', document: '$doc'; conflict.")
        throw DocumentConflictException("conflict on 'delete'")
      } else {
        transid.finished(this, start, s"[DEL] '$dbName', document: '$doc'; not found.")
        // for compatibility
        throw NoDocumentException("not found on 'delete'")
      }
    }

    val f = Future.fromTry(t)

    reportFailure(f, start, failure => s"[DEL] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[database] def get[A <: DocumentAbstraction](doc: DocInfo,
                                                                 attachmentHandler: Option[(A, Attached) => A] = None)(
    implicit transid: TransactionId,
    ma: Manifest[A]): Future[A] = {
    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET] '$dbName' finding document: '$doc'")

    require(doc != null, "doc undefined")

    val t = Try[A] {
      artifacts.get(doc.id.id) match {
        case Some(a) =>
          //Revision matching is enforced in deserilization logic
          transid.finished(this, start, s"[GET] '$dbName' completed: found document '$doc'")
          deserialize[A, DocumentAbstraction](doc, a.doc)
        case _ =>
          transid.finished(this, start, s"[GET] '$dbName', document: '$doc'; not found.")
          // for compatibility
          throw NoDocumentException("not found on 'get'")
      }
    }

    val f = Future.fromTry(t).recoverWith {
      case _: DeserializationException => throw DocumentUnreadable(Messages.corruptedEntity)
    }

    reportFailure(f, start, failure => s"[GET] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[core] def query(table: String,
                                     startKey: List[Any],
                                     endKey: List[Any],
                                     skip: Int,
                                     limit: Int,
                                     includeDocs: Boolean,
                                     descending: Boolean,
                                     reduce: Boolean,
                                     stale: StaleParameter)(implicit transid: TransactionId): Future[List[JsObject]] = {
    require(!(reduce && includeDocs), "reduce and includeDocs cannot both be true")
    require(!reduce, "Reduce scenario not supported") //TODO Investigate reduce
    require(skip >= 0, "skip should be non negative")
    require(limit >= 0, "limit should be non negative")

    documentHandler.checkIfTableSupported(table)

    val Array(ddoc, viewName) = table.split("/")

    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[QUERY] '$dbName' searching '$table")

    val s = artifacts.toStream
      .map(_._2)
      .filter(a => viewMapper.filter(ddoc, viewName, startKey, endKey, a.doc, a.computed))
      .map(_.doc)
      .toList

    val sorted = viewMapper.sort(ddoc, viewName, descending, s)

    val out = if (limit > 0) sorted.slice(skip, skip + limit) else sorted.drop(skip)

    val realIncludeDocs = includeDocs | documentHandler.shouldAlwaysIncludeDocs(ddoc, viewName)

    val r = out.map { js =>
      documentHandler.transformViewResult(
        ddoc,
        viewName,
        startKey,
        endKey,
        realIncludeDocs,
        js,
        MemoryArtifactStore.this)
    }.toList

    val f = Future.sequence(r).map(_.flatten)
    f.onSuccess({
      case _ => transid.finished(this, start, s"[QUERY] '$dbName' completed: matched ${out.size}")
    })
    reportFailure(f, start, failure => s"[QUERY] '$dbName' internal error, failure: '${failure.getMessage}'")

  }

  override protected[core] def count(table: String,
                                     startKey: List[Any],
                                     endKey: List[Any],
                                     skip: Int,
                                     stale: StaleParameter)(implicit transid: TransactionId): Future[Long] = {
    val f =
      query(table, startKey, endKey, skip, limit = 0, includeDocs = false, descending = true, reduce = false, stale)
    f.map(_.size)
  }

  override protected[core] def readAttachment[T](doc: DocInfo, attached: Attached, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T] = {
    val name = attached.attachmentName
    val start = transid.started(
      this,
      LoggingMarkers.DATABASE_ATT_GET,
      s"[ATT_GET] '$dbName' finding attachment '$name' of document '$doc'")

    val attachmentUri = Uri(name)
    if (isInlined(attachmentUri)) {
      memorySource(attachmentUri).runWith(sink)
    } else {
      val storedName = attachmentUri.path.toString()
      val f = attachmentStore.readAttachment(doc.id, storedName, sink)
      f.onSuccess {
        case _ =>
          transid.finished(this, start, s"[ATT_GET] '$dbName' completed: found attachment '$name' of document '$doc'")
      }
      f
    }
  }

  override protected[core] def deleteAttachments[T](doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    attachmentStore.deleteAttachments(doc.id)
  }

  override protected[database] def putAndAttach[A <: DocumentAbstraction](
    d: A,
    update: (A, Attached) => A,
    contentType: ContentType,
    docStream: Source[ByteString, _],
    oldAttachment: Option[Attached])(implicit transid: TransactionId): Future[(DocInfo, Attached)] = {
    attachToExternalStore(d, update, contentType, docStream, oldAttachment, attachmentStore)
  }

  override def shutdown(): Unit = {
    artifacts.clear()
    attachmentStore.shutdown()
  }

  override protected[database] def get(id: DocId)(implicit transid: TransactionId): Future[Option[JsObject]] = {
    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET] '$dbName' finding document: '$id'")

    val t = Try {
      artifacts.get(id.id) match {
        case Some(a) =>
          transid.finished(this, start, s"[GET] '$dbName' completed: found document '$id'")
          Some(a.doc)
        case _ =>
          transid.finished(this, start, s"[GET] '$dbName', document: '$id'; not found.")
          None
      }
    }

    val f = Future.fromTry(t)

    reportFailure(f, start, failure => s"[GET] '$dbName' internal error, doc: '$id', failure: '${failure.getMessage}'")
  }

  private def getRevision(asJson: JsObject) = {
    asJson.fields.get(_rev) match {
      case Some(JsString(r)) => r.toInt
      case _                 => 0
    }
  }

  //Use curried case class to allow equals support only for id and rev
  //This allows us to implement atomic replace and remove which check
  //for id,rev equality only
  private case class Artifact(id: String, rev: Int)(val doc: JsObject, val computed: JsObject) {
    def incrementRev(): Artifact = {
      val (newRev, updatedDoc) = incrementAndGet()
      copy(rev = newRev)(updatedDoc, computed) //With Couch attachments are lost post update
    }

    def docInfo = DocInfo(DocId(id), DocRevision(rev.toString))

    private def incrementAndGet() = {
      val newRev = rev + 1
      val updatedDoc = JsObject(doc.fields + (_rev -> JsString(newRev.toString)))
      (newRev, updatedDoc)
    }
  }

  private object Artifact {
    def apply(id: String, rev: Int, doc: JsObject): Artifact = {
      Artifact(id, rev)(doc, documentHandler.computedFields(doc))
    }

    def apply(info: DocInfo): Artifact = {
      Artifact(info.id.id, info.rev.rev.toInt)(JsObject.empty, JsObject.empty)
    }
  }
}
