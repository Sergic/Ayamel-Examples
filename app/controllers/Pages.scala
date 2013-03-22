package controllers

import play.api.mvc._
import service.Authentication

/**
 * Created with IntelliJ IDEA.
 * User: camman3d
 * Date: 2/19/13
 * Time: 10:05 AM
 * To change this template use File | Settings | File Templates.
 */
object Pages extends Controller {

  def levelSelect(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Ok(views.html.pages.levelSelect(id))
  }

  def level1(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
//        val video = Video.findById(id).get
//        Ok(views.html.pages.level1(video))
      Ok("l1")
  }

  def level2(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
//        val video = Video.findById(id).get
//        Ok(views.html.pages.level2(video))
      Ok("l2")
  }

  def level3(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
//        val video = Video.findById(id).get
//        Ok(views.html.pages.level3(video))
      Ok("l3")
  }

  def level4(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
//        val video = Video.findById(id).get
//        Ok(views.html.pages.level4(video))
      Ok("l4")
  }

  def level5(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Ok(views.html.pages.level5())
  }

  def level6(id: Long) = service.Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Ok(views.html.pages.level6())
  }

}
