package impl

import types.APIKey
import models.{Pages, MimeType, RecognizedTexts, PollingUrl, Page}
import contracts.{FileContentReader, SightClient}
import models.Error
import models.Error.{DecodingFailure, ErrorResponse}
import sttp.client._
import sttp.client.circe._
import io.circe.Json
import io.circe.Encoder
import io.circe.syntax._
import io.circe.parser.decode
import io.circe.Decoder
import givens.{given Decoder[Pages], given Decoder[RecognizedTexts], given Decoder[PollingUrl]}
import sttp.client.circe.circeBodySerializer
import cats.implicits.{given _}
import scala.language.implicitConversions
import sttp.client.{SttpBackend, Identity, NothingT}

class SightClientImpl(private val apiKey: APIKey, private val fileContentReader: FileContentReader)(using SttpBackend[Identity, Nothing, NothingT]) extends SightClient(apiKey, fileContentReader)
    private case class FileContent(mimeType: MimeType, base64FileContent: String)
    private case class Payload(shouldMakeSentences: Boolean, files: Seq[FileContent])
    private given Encoder[FileContent]
        def apply(fileContent: FileContent): Json = Json.obj(
            ("mimeType", Json.fromString(fileContent.mimeType.strRep)),
            ("base64File", Json.fromString(fileContent.base64FileContent)))
    private given Encoder[Payload]
        def apply(payload: Payload): Json = Json.obj(
            ("makeSentences", Json.fromBoolean(payload.shouldMakeSentences)),
            ("files", payload.files.asJson))
    
    //side effecty function
    private def markSeen(pageSeenTracker: Array[Array[Boolean]], pages: Seq[Page]): Unit = 
        pages.filter(_.pageNumber >= 0).foreach{ page => 
            if(pageSeenTracker(page.fileIndex).size != page.numberOfPagesInFile)
                pageSeenTracker(page.fileIndex) = Array.fill(page.numberOfPagesInFile)(false)
            pageSeenTracker(page.fileIndex)(page.pageNumber) = true
        }

    private def handlePollingUrl(url: String, numberOfFiles: Int): Either[Error, Pages] = 
        var pages = List[Page]()
        val pageSeenTracker: Array[Array[Boolean]] = Array.fill(numberOfFiles)(Array(false))
        def decodePages(p: String): Either[Error, Pages] = decode[Pages](p).left.map(e => ErrorResponse(e.toString))
        var error: Option[Error] = None
        while(error.isEmpty && pageSeenTracker.forall(_.foldLeft(true)(_ && _)))
            val request = basicRequest.header("Authorization", s"Basic $apiKey").get(uri"$url")
            val response = request.send()
            response.body.fold[Either[Error, Pages]](fa = ErrorResponse(_).asLeft[Pages], fb = decodePages) match
                case Left(err) => error = Some(err)
                case Right(p) => pages = pages ++ p.pages
                    markSeen(pageSeenTracker, p.pages)
        
        error.fold(Pages(pages).asRight[Error])(_.asLeft[Pages])

    
    private def decodeResponse(response: String, numberOfFiles: Int): Either[Error, Pages] = 
        def decodePollingUrl(r: String): Either[Error, PollingUrl] = 
            decode[PollingUrl](r).left.map(e => ErrorResponse(e.toString))
        val result: Either[Error, PollingUrl] | RecognizedTexts = 
            decode[RecognizedTexts](response).fold[Either[Error,PollingUrl] | RecognizedTexts](fa = _ => decodePollingUrl(response), fb = identity)
        result match
            case rt: RecognizedTexts => 
                val page = Page(error = None, fileIndex = 0, pageNumber = 1, numberOfPagesInFile = 1, recognizedText = rt.recognizedTexts)
                val pages = Pages(Seq(page))
                Right(pages)
            case errOrPollingUrl: Either[Error, PollingUrl] => 
                errOrPollingUrl.flatMap(pu => handlePollingUrl(pu.pollingUrl, numberOfFiles))

    
    override def recognize(filePaths: Seq[String], shouldWordLevelBoundBoxes: Boolean): Either[Error, Pages] = 
        val payloadOrError: Either[Error, Payload] =  
        for 
            filePaths1 <- filePaths.asRight[Nothing]
            mimeTypes <- fileContentReader.fileMimeTypes(filePaths1)
            base64 <- fileContentReader.filesToBase64(filePaths1)
        yield 
            val fileContents = mimeTypes.zip(base64).map((m, b) => FileContent(m,b))
            Payload(shouldWordLevelBoundBoxes, fileContents)
        payloadOrError match 
            case Left(error) => Left(error)
            case Right(payload) => 
                val request = basicRequest.body(payload).header("Authorization", s"Basic $apiKey")
                    .post(uri"https://siftrics.com/api/sight/")
                val response = request.send()
                response.body.fold[Either[Error, Pages]](fa = ErrorResponse(_).asLeft[Pages], fb = decodeResponse(_, filePaths.length))