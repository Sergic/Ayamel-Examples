package service

import java.awt.image.{AffineTransformOp, BufferedImage}
import java.awt.{RenderingHints, Graphics2D, Color}
import scala.concurrent.{ExecutionContext, Future}
import javax.imageio.ImageIO
import java.net.URL
import java.io.{File, IOException}
import play.api.{Logger, Play}
import play.api.Play.current
import java.awt.geom.AffineTransform
import models.Content
import dataAccess.ResourceController
import play.api.libs.json.JsArray
import ExecutionContext.Implicits.global

/**
 * Created with IntelliJ IDEA.
 * User: camman3d
 * Date: 4/3/13
 * Time: 10:37 AM
 * To change this template use File | Settings | File Templates.
 */
object ImageTools {

  def scaleImage(image: BufferedImage, width: Int, height: Int, background: Color): Option[BufferedImage] = {
    if(image == null) None
    else {
      // Maintain aspect ratio
      var newWidth = width
      var newHeight = height
      val imgWidth = image.getWidth
      val imgHeight = image.getHeight
      if (imgWidth*height < imgHeight*width) {
        newWidth = imgWidth*height/imgHeight
      } else {
        newHeight = imgHeight*width/imgWidth
      }

      Logger.debug(s"Original Image: $imgWidth x $imgHeight; New Image: $newWidth x $newHeight")

      // Write the image to a new image of a different size
      val newImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = newImage.createGraphics()
      try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setBackground(background)
        graphics.clearRect(0, 0, newWidth, newHeight)
        graphics.drawImage(image, 0, 0, newWidth, newHeight, null)
        Some(newImage)
      } catch {
        case _: Throwable => None
      } finally {
        graphics.dispose()
      }
    }
  }

  def makeThumbnail(image: BufferedImage): Option[BufferedImage] = scaleImage(image, 250, 250, Color.black)

  def generateThumbnail(source: Any): Future[Option[String]] = {
    val orig = source match {
      case url:String => ImageIO.read(new URL(url))
      case file:File => ImageIO.read(file)
      case _ => throw new IOException("unknown source type")
    }
    makeThumbnail(orig) match {
      case Some(image) =>
        val filename = FileUploader.uniqueFilename("thumbnail.jpg")
        FileUploader.uploadImage(image, filename)
      case None => Future(None)
    }
  }

  def getNormalizedImageFromFile(file: File): BufferedImage = {
    //TODO: Implement proper error checking
    val image = ImageIO.read(file)
    val width = math.min(image.getWidth, Play.configuration.getInt("media.image.maxWidth").get)
    val height = math.min(image.getHeight, Play.configuration.getInt("media.image.maxHeight").get)
    scaleImage(image, width, height, Color.black).get
  }

  def crop(image: BufferedImage, top: Double, left: Double, bottom: Double, right: Double): BufferedImage = {
    val x = (image.getWidth * left).toInt
    val y = (image.getHeight * top).toInt
    val width = (image.getWidth * right).toInt - x
    val height = (image.getHeight * bottom).toInt - y
    image.getSubimage(x, y, width, height)
  }

  def rotate(image: BufferedImage, stepsClockwise: Int): BufferedImage = {
    // Create the new image
    val newWidth = if (stepsClockwise % 2 == 0) image.getWidth else image.getHeight
    val newHeight = if (stepsClockwise % 2 == 0) image.getHeight else image.getWidth
    val newImage = new BufferedImage(newWidth, newHeight, image.getType)

    // Set up the transformations (translate original to middle of the new then rotate using the new middle as anchor)
    val graphics = newImage.getGraphics.asInstanceOf[Graphics2D]
    val originalMiddle = (image.getWidth / 2, image.getHeight / 2)
    val newMiddle = (newImage.getWidth / 2, newImage.getHeight / 2)
    graphics.translate(newMiddle._1 - originalMiddle._1, newMiddle._2 - originalMiddle._2)
    graphics.rotate(Math.toRadians(stepsClockwise * 90), originalMiddle._1, originalMiddle._2)

    // Render the new image and return it
    graphics.drawImage(image, 0, 0, image.getWidth, image.getHeight, null)
    newImage
  }

  def loadImageFromContent(content: Content): Future[Option[BufferedImage]] = {
    // Load the resource
    ResourceController.getResource(content.resourceId).map { response =>
      response.map { json =>
        // Look at the files to find the one that has the URL we want
        val files = json \ "resource" \ "content" \ "files"
        val file = files.as[JsArray].value.find(obj => (obj \ "representation").as[String] == "original").get

        // Load the image from the URL
        val url = new URL((file \ "downloadUri").as[String])
        ImageIO.read(url)
      }
    }
  }
}
