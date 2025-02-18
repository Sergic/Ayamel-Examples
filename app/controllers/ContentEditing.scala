package controllers

import play.api.mvc.{Request, Controller}
import controllers.authentication.Authentication
import play.api.libs.json.{JsString, JsArray, Json}
import dataAccess.ResourceController
import models.{User, Course, Content}
import service._
import java.net.URL
import java.io.IOException
import javax.imageio.ImageIO
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import play.api.libs.json.JsArray
import play.api.Logger
import scala.Some

/**
 * Controller that deals with the editing of content
 */
object ContentEditing extends Controller {

  /**
   * Sets the metadata for a particular content object
   * @param id The ID of the content
   */
  def setMetadata(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {  content =>
          // Make sure the user is able to edit
          if (content isEditableBy user) {
            // Get the info from the form
            val title = request.body("title")(0)
            val description = request.body("description")(0)
            val categories = request.body.get("categories").map(_.toList).getOrElse(Nil)
            val labels = request.body.get("labels").map(_.toList).getOrElse(Nil)
            val keywords = labels.mkString(",")
            val languages = request.body.get("languages").map(_.toList).getOrElse(List("eng"))

            // Update the name and labels of the content
            content.copy(name = title, labels = labels).save

            // Create the JSON object
            val obj = Json.obj(
              "title" -> title,
              "description" -> description,
              "keywords" -> keywords,
//                "categories" -> JsArray(categories.map(c => JsString(c))),
              "languages" -> Json.obj(
                "iso639_3" -> languages
              )
            )

            // Save the metadata
            ResourceController.updateResource(content.resourceId, obj)

            Redirect(routes.ContentController.view(id)).flashing("success" -> "Metadata updated.")
          } else
            Errors.forbidden
        }
  }

  /**
   * Helper function for setSettings
   * @param content The content whose settings are being set
   */
  def recordSettings(content: Content, data: Map[String, Seq[String]]) {
    content.setSetting("captionTrack", data.get("captionTracks").getOrElse(Nil))
    content.setSetting("annotationDocument", data.get("annotationDocs").getOrElse(Nil))
    content.setSetting("targetLanguages", data.get("targetLanguages").getOrElse(Nil))

    content.setSetting("aspectRatio", List(data.get("aspectRatio").map(_(0)).getOrElse("1.7778")))

    content.setSetting("showCaptions", List(data.get("showCaptions").map(_(0)).getOrElse("false")))
    content.setSetting("showAnnotations", List(data.get("showAnnotations").map(_(0)).getOrElse("false")))
    content.setSetting("allowDefinitions", List(data.get("allowDefinitions").map(_(0)).getOrElse("false")))
    content.setSetting("showTranscripts", List(data.get("showTranscripts").map(_(0)).getOrElse("false")))
  }

  /**
   * Sets the content's settings
   * @param id The ID of the content
   */
  def setSettings(id: Long) = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          val data = request.body.dataParts
          // Make sure the user is able to edit
          if (content isEditableBy user) {
            val shareability = data("shareability").lift(0)
              .map(_.toInt).getOrElse(content.shareability)
            val visibility = data("visibility").lift(0)
              .map(_.toInt).getOrElse(content.visibility)
            val newcontent = content.copy(shareability = shareability, visibility = visibility).save
            recordSettings(newcontent, data)
            Ok
          } else
            Errors.forbidden
        }
  }

  /**
   * Image editing view
   * @param id The ID of the content
   */
  def editImage(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {  content =>
          if (content isEditableBy user) {
            if (content.contentType == 'image) {
              val course = AdditionalDocumentAdder.getCourse()
              Ok(views.html.content.editImage(content, ResourceController.baseUrl, course))
            } else
              Errors.forbidden
          } else
            Errors.forbidden
        }
  }

  /**
   * Saves the image edits.
   * @param id The id of the content
   */
  def saveImageEdits(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {  content =>
          if ((content isEditableBy user) && (content.contentType == 'image)) {

            // Get the rotation and crop info
            val rotation = request.body("rotation")(0).toInt
            val cropTop = request.body("cropTop")(0).toDouble
            val cropLeft = request.body("cropLeft")(0).toDouble
            val cropBottom = request.body("cropBottom")(0).toDouble
            val cropRight = request.body("cropRight")(0).toDouble
            val redirect = Redirect(AdditionalDocumentAdder.getCourse() match {
              case Some(course) => routes.CourseContent.viewInCourse(content.id.get, course.id.get)
              case _ => routes.ContentController.view(content.id.get)
            })

            // Load the image
            Async {
              ImageTools.loadImageFromContent(content).flatMap {
                case Some(image) =>
                  // Make the changes to the image
                  val newImage = ImageTools.crop(
                    if (rotation > 0) ImageTools.rotate(image, rotation) else image,
                    cropTop, cropLeft, cropBottom, cropRight
                  )

                  // Save the new image
                  FileUploader.uploadImage(newImage, FileUploader.uniqueFilename(content.resourceId + ".jpg")).flatMap {
                    case Some(url) =>
                      // Update the resource
                      ResourceHelper.updateFileUri(content.resourceId, url).map {
                        case Some(resource) =>
                          redirect.flashing("info" -> "Image updated")
                        case None =>
                          redirect.flashing("error" -> "Failed to update image")
                      }
                    case None =>
                      Future(redirect.flashing("error" -> "Failed to update image"))
                  }
                case None =>
                  Future(redirect.flashing("error" -> "Couldn't load image"))
              }
            }
          } else
            Errors.forbidden
        }
  }

  /**
   * Sets the thumbnail for content from either a URL or a file
   * @param id The ID of the content that the thumbnail will be for
   */
  def changeThumbnail(id: Long) = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>

          val file = request.body.file("file")
          val url = request.body.dataParts("url")(0)
          val redirect = Redirect(routes.ContentController.view(id))

          try {
            (if (url.isEmpty) {
              file.map { filepart =>
                ImageTools.generateThumbnail(filepart.ref.file)
              }
            } else {
              Some(ImageTools.generateThumbnail(url))
            }) match {
              case Some(fut) => Async {
                fut.map {
                  case Some(thumbnailUrl) =>
                    content.copy(thumbnail = thumbnailUrl).save
                    redirect.flashing("info" -> "Thumbnail changed")
                  case None => redirect.flashing("error" -> "Unknown error while attempting to create thumbnail")
                }
              }
              case None => redirect.flashing("error" -> "No file provided")
            }
          } catch {
            case _: IOException => redirect.flashing("error" -> "Error reading image file")
          }
        }
  }

  /**
   * Creates a thumbnail for content from a particular point in time in a video
   * @param id The ID of the content
   * @param time The time in the video which will be used as the thumbnail
   */
  def createThumbnail(id: Long, time: Double) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          val redirect = Redirect(routes.ContentController.view(id))

          Async {
            // Get the video resource from the content
            ResourceController.getResource(content.resourceId).map { response =>
              response.map { json =>
                // Get the video file
                (json \ "resource" \ "content" \ "files") match {
                  case arr:JsArray =>
                    arr.value.find { file =>
                      (file \ "mime") match {
                        case str:JsString => str.value.startsWith("video")
                        case _ => false
                      }
                    }.map { videoObject =>
                      (videoObject \ "downloadUri") match {
                        case videoUrl:JsString => Async {
                        /*
                            The "-protocols" command will list your version of ffmpeg

                            List of supported Protocols:
                                applehttp, concat, crypto, file, gopher, http, httpproxy
                                mmsh, mmst, pipe, rtmp, rtp, tcp, udp
                            The default protocol is "file:" and you do not need to specify it in ffmpeg,
                                so we can't check to see if we are using a supported protocol. However,
                                we do know that "https:" is unsupported, so if we get one, try to convert it
                                to "http:". If it doesn't work, we'll just get a message that the thumbnail
                                could not be generated.
                        */
                         val url = if (videoUrl.value.startsWith("https://"))
                                JsString(videoUrl.value.replaceFirst("https://","http://"))
                            else
                                videoUrl

                         // Generate the thumbnail for that video
                          VideoTools.generateThumbnail(url.value, time).map {
                            case Some(thumbnailUrl) =>
                              // Save it and be done
                              content.copy(thumbnail = thumbnailUrl).save
                              redirect.flashing("info" -> "Thumbnail updated")
                            case None => redirect.flashing("error" -> "Could not generate thumbnail")
                          }
                        }
                        case _ => redirect.flashing("error" -> "No video file found. Thumbnails cannot be generated from streams.")
                      }
                    }.getOrElse {
                      redirect.flashing("error" -> "No video file found")
                    }
                  case _ => redirect.flashing("error" -> "No files found")
                }
              }.getOrElse {
                redirect.flashing("error" -> "Could not access video.")
              }
            }
          }
        }
  }

  /**
   * Sets the downloadUri of the primary resource associated with the content
   * @param id The ID of the content
   */
  def setMediaSource(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {
          content =>
            if (content isEditableBy user) {
              val url = request.body("url")(0)
              Async {
                ResourceHelper.updateFileUri(content.resourceId, url).map(json =>
                  Redirect(routes.ContentController.view(id)).flashing("info" -> "Media source updated")
                )
              }
            } else
              Errors.forbidden
        }
  }

  /**
   * Updates the settings of multiple content items
   */
  def batchUpdateContent = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        val redirect = Redirect(routes.ContentController.manageContent())
        try {
          val params = request.body.mapValues(_(0))
          val shareability = params("shareability").toInt
          val visibility = params("visibility").toInt
          val contentList = params("ids").split(",").collect {
            case id:String if !id.isEmpty => Content.findById(id.toLong)
          }.flatten

          if(contentList.forall(_.isEditableBy(user))) {
            for(content <- contentList) {
              content.copy(shareability = shareability, visibility = visibility).save
            }
            redirect.flashing("info" -> "Content updated")
          } else {
            redirect.flashing("error" -> "You do not have permission to edit some of these items")
          }
        } catch {
          case e: Throwable =>
            Logger.debug("Batch Update Error: " + e.getMessage())
            redirect.flashing("error" -> ("Error while updating: "+e.getMessage()))
        }
  }
}
