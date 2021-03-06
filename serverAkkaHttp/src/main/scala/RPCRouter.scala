package wiro
package server.akkaHttp

import AutowireErrorSupport._

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive0, Directive1, ExceptionHandler, Route }

import cats.syntax.either._

import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

import FailSupport._

import io.circe.{ Json, JsonObject }
import io.circe.parser._

trait Router extends RPCServer with PathMacro with MetaDataMacro {
  def tp: Seq[String]
  def methodsMetaData: Map[String, MethodMetaData]
  def routes: autowire.Core.Router[Json]
  def path: String = tp.last

  def buildRoute: Route = handleExceptions(exceptionHandler) {
    pathPrefix(path) {
      methodsMetaData.map {
        case (k, v @ MethodMetaData(OperationType.Command(_))) => command(k, v)
        case (k, v @ MethodMetaData(OperationType.Query(_)))   => query(k, v)
      }.reduce(_ ~ _)
    }
  }

  def exceptionHandler = ExceptionHandler {
    case f: FailException[_] => complete(f.response)
  }

  private[this] val requestToken: Directive1[Option[String]] = {
    val TokenPattern = "Token token=(.+)".r
    optionalHeaderValueByName("Authorization").map {
      case Some(TokenPattern(token)) => Some(token)
      case _                         => None
    }
  }

  private[this] def operationPath(operationFullName: String): Array[String] =
    operationFullName.split('.')

  private[this] def operationName(operationFullName: String, methodMetaData: MethodMetaData): String =
    methodMetaData.operationType.name.getOrElse(operationPath(operationFullName).last)

  private[this] def autowireRequest(operationFullName: String, args: Map[String, Json]): autowire.Core.Request[Json] =
    autowire.Core.Request(path = operationPath(operationFullName), args = args)

  private[this] def autowireRequestRoute(operationFullName: String, args: Map[String, Json]): Route =
    Either.catchNonFatal(routes(autowireRequest(operationFullName, args)))
      .fold(handleUnwrapErrors, result => complete(result))

  private[this] def autowireRequestRouteWithToken(operationFullName: String, args: Map[String, Json]): Route =
    requestToken(token => autowireRequestRoute(operationFullName, args ++ token.map(tokenAsArg)))

  private[this] def routePathPrefix(operationFullName: String, methodMetaData: MethodMetaData): Directive0 =
    pathPrefix(operationName(operationFullName, methodMetaData))

  //Generates GET requests
  private[this] def query(operationFullName: String, methodMetaData: MethodMetaData): Route =
    (routePathPrefix(operationFullName, methodMetaData) & pathEnd & get & parameterMap) { params =>
      val args = params.mapValues(parseJsonOrString)
      autowireRequestRouteWithToken(operationFullName, args)
    }

  //Generates POST requests
  private[this] def command(operationFullName: String, methodMetaData: MethodMetaData): Route =
    (routePathPrefix(operationFullName, methodMetaData) & pathEnd & post & entity(as[JsonObject])) {
      request => autowireRequestRouteWithToken(operationFullName, request.toMap)
    }

  private[this] def parseJsonOrString(s: String): Json =
    parse(s).getOrElse(Json.fromString(s))

  private[this] def tokenAsArg(token: String): (String, Json) =
    "token" -> Json.obj("token" -> Json.fromString(token))
}
